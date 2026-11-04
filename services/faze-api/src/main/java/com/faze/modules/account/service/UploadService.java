/*
 * 职责：上传凭证的签发与下载地址生成（第 1 段 C 组）。
 * 调用方：modules/account/controller/UploadController。
 * 数据来源：infra/storage/FileStorage（本地目录实现，段 4 换 OSS）。
 * 注意：
 *   1. **key 一律由服务端生成**，客户端只提交 scene + 文件名（只用来取扩展名）。
 *      让客户端传路径是存储层最经典的漏洞来源。
 *   2. 下载地址做**归属校验**：key 里的 userId 必须等于当前用户。
 *      没有这一步，任何登录用户改一个 key 就能下载别人的身份证影像。
 *   3. 段 1 只有「本人看自己的影像」这一种场景；审核员看别人的影像走 PC 管理端
 *      （F2），那条路的鉴权与审计在 F 组单独做，不要在这里开口子。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.FileStorage;
import com.faze.infra.storage.StorageKeyRules;
import com.faze.modules.account.bo.UploadBO;
import com.faze.modules.account.vo.UploadTokenVO;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 上传服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private static final DateTimeFormatter YYYY_MM = DateTimeFormatter.ofPattern("yyyyMM");

    private final FileStorage fileStorage;

    /**
     * 签发上传凭证。
     *
     * <p>前置：scene 与扩展名都在白名单内，size 不超过上限。</p>
     * <p>副作用：无（只生成签名，不落盘）。</p>
     *
     * @param request 上传申请
     * @param userId  当前用户
     * @return 上传凭证
     * @throws BusinessException 401001 场景/扩展名/大小非法
     */
    public UploadTokenVO presign(UploadBO request, Long userId) {
        // 任务 1：生成 key（场景与扩展名在 StorageKeyRules 里白名单校验）。
        String key;
        try {
            key = StorageKeyRules.build(request.getScene(), userId,
                    LocalDate.now(ZoneOffset.UTC).format(YYYY_MM),
                    StorageKeyRules.extensionOf(request.getFileName()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        // 任务 2：签发上传地址。
        FileStorage.PresignedUpload presigned;
        try {
            presigned = fileStorage.presignUpload(key, request.getContentType(), request.getSize());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        UploadTokenVO vo = new UploadTokenVO();
        vo.setKey(key);
        vo.setUploadUrl(presigned.url());
        vo.setExpiresIn(presigned.expiresIn());
        log.info("签发上传凭证 userId={} scene={} key={}", userId, request.getScene(), key);
        return vo;
    }

    /**
     * 生成短时下载地址。
     *
     * <p>前置：key 合法且属于当前用户。</p>
     *
     * @param key    对象 key
     * @param userId 当前用户
     * @return 下载地址与有效期
     * @throws BusinessException 401001 key 非法 / 403 不是本人的文件
     */
    public FileStorage.PresignedUrl downloadUrl(String key, Long userId) {
        if (!StorageKeyRules.isValid(key)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        Long owner = StorageKeyRules.ownerOf(key);
        if (owner == null || !owner.equals(userId)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN);
        }
        return fileStorage.presignDownload(key);
    }
}
