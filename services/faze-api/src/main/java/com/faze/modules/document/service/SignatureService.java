/*
 * 职责：签名库的业务编排（段 4 D1）——列表、新增、删除。
 * 调用方：modules/document/controller/AppSignatureController；DocDeliveryService 复用 `requireLibrary`。
 * 数据来源：faze.signature（**只碰 `document_id IS NULL` 的行**）+ faze.file_record。
 * 注意：
 *   1. **每条查询都显式带 `document_id IS NULL`**：本表一表两用（签名库 / 文书上的签名），
 *      写成「按 user_id 查全部」会把盖章记录混进签名库（工作流 §六 R5）。
 *   2. 每个用户最多 **5 个**签名（防当图床）；第 6 个抛 406005。
 *   3. 删除：**已被任何文书引用**（存在 `document_id IS NOT NULL` 且 `image_oss_key` 相同的行）→ 406006。
 *      未被引用时**物理 DELETE**：本表没有 `deleted_at`（15 字段是技术路线原文，不许加），
 *      所以工作流 D1 写的「软删」无法按字面实现；替代做法是把对应 `file_record` 置 `orphan`
 *      （图片文件不删，留给段 7 的清理任务），已在验收证据里登记为偏离。
 *   4. 对象键必须由 `StorageKeyRules` 校验并**核对归属**：否则可以把别人的签名图 key 塞进来。
 *   5. 事务只包 DB；`file_record` 的登记不在事务里（它是审计链，失败也不该回滚签名行的情况——
 *      实际顺序是先插签名行再登记，登记失败由调用方看到异常，签名行随事务回滚）。
 */
package com.faze.modules.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.document.entity.Signature;
import com.faze.modules.document.mapper.SignatureMapper;
import com.faze.modules.document.vo.SignatureVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 签名库服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignatureService {

    /** 每用户签名上限（工作流 D1 第 2 条）。 */
    public static final int MAX_PER_USER = 5;

    /** 签名图的对象键场景前缀（`StorageKeyRules` 的白名单场景）。 */
    private static final String SCENE_PREFIX = "private/signature/";

    private final SignatureMapper signatureMapper;
    private final FileRecordService fileRecordService;

    /**
     * 签名库列表（新→旧）。
     *
     * @param userId 当前用户
     * @return 签名列表（`imageUrl` 为 60 秒现签地址）
     */
    public List<SignatureVO> listLibrary(Long userId) {
        List<Signature> rows = signatureMapper.selectList(Wrappers.<Signature>lambdaQuery()
                .eq(Signature::getUserId, userId)
                .isNull(Signature::getDocumentId)
                .orderByDesc(Signature::getId));
        return rows.stream().map(row -> {
            SignatureVO vo = new SignatureVO();
            vo.setId(row.getId());
            vo.setImageUrl(fileRecordService.presignDownload(row.getImageOssKey()));
            vo.setCreatedAt(row.getCreatedAt());
            return vo;
        }).toList();
    }

    /**
     * 新增签名（幂等：同一个对象键重复提交不会占两个名额）。
     *
     * @param userId      当前用户
     * @param imageOssKey 客户端直传拿到的对象键
     * @return 签名主键
     * @throws BusinessException 401001（键格式非法）/ 401008（不是自己的键）/ 406005（超过 5 个）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, String imageOssKey) {
        if (!StorageKeyRules.isValid(imageOssKey) || !imageOssKey.startsWith(SCENE_PREFIX)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "签名图对象键非法");
        }
        Long owner = StorageKeyRules.ownerOf(imageOssKey);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        Signature exists = signatureMapper.selectOne(Wrappers.<Signature>lambdaQuery()
                .eq(Signature::getUserId, userId)
                .isNull(Signature::getDocumentId)
                .eq(Signature::getImageOssKey, imageOssKey)
                .last("LIMIT 1"));
        if (exists != null) {
            log.debug("签名已存在，幂等返回 signatureId={}", exists.getId());
            return exists.getId();
        }
        long count = signatureMapper.selectCount(Wrappers.<Signature>lambdaQuery()
                .eq(Signature::getUserId, userId)
                .isNull(Signature::getDocumentId));
        if (count >= MAX_PER_USER) {
            throw new BusinessException(ErrorCode.SIGNATURE_LIMIT_EXCEEDED);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Signature row = new Signature();
        row.setUserId(userId);
        row.setImageOssKey(imageOssKey);
        row.setDocumentId(null);
        row.setHashAlgo("SHA-256");
        row.setSignedAt(now);
        row.setCreatedAt(now);
        signatureMapper.insert(row);
        // 客户端直传的文件在这里补登记（file_record 是审计链，必须有一行）
        fileRecordService.recordSignatureImage(userId, imageOssKey, null, null);
        log.info("签名已入库 signatureId={} 当前数量={}", row.getId(), count + 1);
        return row.getId();
    }

    /**
     * 删除签名库里的一个签名（物理删 + 文件置孤儿）。
     *
     * @param userId 当前用户
     * @param id     签名主键
     * @throws BusinessException 406007（不存在/不是自己的）/ 406006（已被文书引用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        Signature row = signatureMapper.selectOne(Wrappers.<Signature>lambdaQuery()
                .eq(Signature::getId, id)
                .eq(Signature::getUserId, userId)
                .isNull(Signature::getDocumentId));
        if (row == null) {
            throw new BusinessException(ErrorCode.SIGNATURE_NOT_FOUND);
        }
        long used = signatureMapper.selectCount(Wrappers.<Signature>lambdaQuery()
                .isNotNull(Signature::getDocumentId)
                .eq(Signature::getImageOssKey, row.getImageOssKey()));
        if (used > 0) {
            throw new BusinessException(ErrorCode.SIGNATURE_IN_USE);
        }
        signatureMapper.deleteById(row.getId());
        fileRecordService.markOrphan(row.getImageOssKey());
        log.info("签名已删除 signatureId={}（文件置 orphan，等待段 7 清理）", row.getId());
    }

    /**
     * 取签名库里的一个签名行（叠章用）。
     *
     * @param userId 当前用户
     * @param id     签名主键
     * @return 签名行（`document_id` 必为 NULL）
     * @throws BusinessException 406007
     */
    public Signature requireLibrary(Long userId, Long id) {
        Signature row = signatureMapper.selectOne(Wrappers.<Signature>lambdaQuery()
                .eq(Signature::getId, id)
                .eq(Signature::getUserId, userId)
                .isNull(Signature::getDocumentId));
        if (row == null) {
            throw new BusinessException(ErrorCode.SIGNATURE_NOT_FOUND);
        }
        return row;
    }
}
