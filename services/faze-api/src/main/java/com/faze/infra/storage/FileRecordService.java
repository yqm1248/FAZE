/*
 * 职责：文件产物的落盘 + 登记（段 4 A3）——把「签名 PNG / 文书 PDF / 模板预览图」统一进 file_record。
 * 调用方：modules/document（C3 生成 PDF）、modules/document（D1 签名图）、A-18 模板预览（B1）。
 * 数据来源：FileStorage（本地实现 LocalFileStorage）+ faze.file_record。
 * 注意：
 *   1. **客户端直传的文件不经过这里**：签名图由客户端走段 1 的 `POST /app/upload/token` 直传，
 *      本类只负责它上传成功后的**登记**（`recordSignatureImage`）。服务端生成的文件（PDF/预览图）
 *      才走 `storeGenerated`（生成 key → put → 登记）。
 *   2. `storeGenerated` 里「落盘」与「登记」不在一个事务里（put 是文件系统操作，不参与 DB 事务）。
 *      如果 put 成功、INSERT 失败，那个对象就是孤儿——由段 7 的清理任务按 file_record 反查处理，
 *      **不要在这里 try/catch 后假装成功**。
 *   3. 日志**不打完整 oss_key**（数据表规范 §L4：连 ossKey 都不打完整），只打业务类型与主键。
 *   4. 下载 URL 一律现签（60 秒），**不缓存**：缓存 URL 等于把过期时间钉死在缓存里
 *      （工作流 §三 A3 第 4 条）。
 */
package com.faze.infra.storage;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.infra.storage.mapper.FileRecordMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文件产物登记服务。
 *
 * <p>业务表只存 `oss_key`，取文件时用它现签一个 60 秒 URL。本类不判断「这个文件该不该给谁看」——
 * 归属校验在调用方（C1 的越权规则）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileRecordService {

    /** 业务类型：模板预览图。 */
    public static final String BIZ_DOC_TEMPLATE = "doc_template";
    /** 业务类型：文书 PDF（含带签 PDF）。 */
    public static final String BIZ_DOCUMENT = "document";
    /** 业务类型：签名 PNG。 */
    public static final String BIZ_SIGNATURE = "signature";

    /** 本地实现的桶名；接云 OSS 后换成真实 bucket。 */
    private static final String BUCKET = "local";
    private static final String STATUS_ACTIVE = "active";
    private static final int PRIVATE_YES = 1;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Set<String> ALLOWED_BIZ = Set.of(BIZ_DOC_TEMPLATE, BIZ_DOCUMENT, BIZ_SIGNATURE);

    private final FileRecordMapper fileRecordMapper;
    private final FileStorage fileStorage;

    /**
     * 服务端生成一份文件：生成 key → 落盘 → 登记。
     *
     * <p>用于文书 PDF（`bizType=document`）与模板预览图（`bizType=doc_template`）。
     *
     * @param bizType   业务类型（三选一）
     * @param bizId     业务主键（document.id / doc_template.id）
     * @param ownerUser 归属用户；模板预览图传 0（运营侧产物）
     * @param ext       扩展名（不含点）：pdf / png
     * @param content   文件字节
     * @param mimeType  MIME 类型
     * @return 生成的 oss_key（调用方负责写回业务表）
     * @throws BusinessException 业务类型非法 / 内容为空 / 落盘失败
     */
    public String storeGenerated(String bizType, Long bizId, Long ownerUser, String ext,
                                 byte[] content, String mimeType) {
        return storeGenerated(bizType, bizId, ownerUser, ext, content, mimeType, "generated." + ext);
    }

    /**
     * 服务端生成一份文件，并指定登记的「原始文件名」。
     *
     * <p>原始文件名在本段当**产物种类的区分键**用：列表缩略图固定 `generated.png`，
     * 预览图缓存用 `pv{短哈希}:{页号}.png`（短哈希来自 PDF 对象键，换 PDF 就换前缀，
     * 旧缓存自然失效）。这是不给 `file_record` 加字段的前提下最省的做法。</p>
     *
     * @param bizType      业务类型（三选一）
     * @param bizId        业务主键
     * @param ownerUser    归属用户
     * @param ext          扩展名（不含点）
     * @param content      文件字节
     * @param mimeType     MIME 类型
     * @param originalName 登记的原始文件名
     * @return 生成的 oss_key
     */
    public String storeGenerated(String bizType, Long bizId, Long ownerUser, String ext,
                                 byte[] content, String mimeType, String originalName) {
        assertBizType(bizType);
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED);
        }
        String key = StorageKeyRules.build(bizType, ownerUser,
                YearMonth.now(ZoneOffset.UTC).format(MONTH), ext);
        try {
            fileStorage.put(key, new ByteArrayInputStream(content), content.length);
        } catch (RuntimeException e) {
            log.error("文件落盘失败 bizType={} bizId={}", bizType, bizId, e);
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED);
        }
        insert(bizType, bizId, ownerUser, key, originalName, mimeType,
                (long) content.length, md5Hex(content));
        log.info("服务端文件已登记 bizType={} bizId={} size={}", bizType, bizId, content.length);
        return key;
    }

    /**
     * 取某业务对象最新一条登记的 key（列表缩略图用）。
     *
     * @param bizType      业务类型
     * @param bizId        业务主键
     * @param originalName 原始文件名（固定名，如 `generated.png`）
     * @return 对象键；没有时 null
     */
    public String latestKey(String bizType, Long bizId, String originalName) {
        return fileRecordMapper.selectLatestKey(bizType, bizId, originalName);
    }

    /**
     * 按原始文件名前缀取登记行（预览图缓存用）。
     *
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @param prefix  裸前缀（`%` 由 Mapper 的 CONCAT 补）
     * @return 登记行列表
     */
    public List<FileRecord> recordsByPrefix(String bizType, Long bizId, String prefix) {
        return fileRecordMapper.selectByOriginalNamePrefix(bizType, bizId, prefix);
    }

    /**
     * 把文件标成孤儿（用户删掉签名后调用），文件本身不删。
     *
     * @param ossKey 对象键
     */
    public void markOrphan(String ossKey) {
        if (ossKey == null || ossKey.isBlank()) {
            return;
        }
        fileRecordMapper.markOrphan(ossKey);
    }

    /**
     * 读取对象内容（叠章要拿原 PDF 与签名 PNG 的字节）。
     *
     * @param ossKey 对象键
     * @return 文件字节
     * @throws BusinessException 501001 / 506001（对象不存在或读取失败）
     */
    public byte[] readBytes(String ossKey) {
        if (ossKey == null || ossKey.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED, "对象键为空");
        }
        try (InputStream in = fileStorage.get(ossKey)) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            log.error("读取对象失败", e);
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED);
        }
    }

    /**
     * 重新签发下载地址（60 秒）。
     *
     * @param ossKey 对象键
     * @return 可 GET 的短时地址
     */
    public String presignDownload(String ossKey) {
        return fileStorage.presignDownload(ossKey).url();
    }

    /**
     * 登记客户端已直传的签名图。
     *
     * <p>签名图先经 `POST /app/upload/token`（scene=signature）直传拿到 key，再调本方法登记。
     * **不在这里 put**：文件已经由客户端传上去了。</p>
     *
     * @param userId 签名归属用户
     * @param ossKey 客户端拿到的对象键
     * @param sizeBytes 字节数（客户端声明的上限值）
     * @param md5 内容 MD5（客户端可不算，传 null）
     * @return file_record 主键
     */
    public Long recordSignatureImage(Long userId, String ossKey, Long sizeBytes, String md5) {
        return insert(BIZ_SIGNATURE, null, userId, ossKey, "signature.png", "image/png",
                sizeBytes, md5);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 写一行 file_record。
     *
     * @return 新行主键
     */
    private Long insert(String bizType, Long bizId, Long uploaderId, String ossKey,
                        String originalName, String mimeType, Long sizeBytes, String md5) {
        FileRecord record = new FileRecord();
        record.setOssKey(ossKey);
        record.setBucket(BUCKET);
        record.setOriginalName(originalName);
        record.setContentType(mimeType);
        record.setSizeBytes(sizeBytes);
        record.setMd5(md5);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setUploaderId(uploaderId == null ? 0L : uploaderId);
        record.setIsPrivate(PRIVATE_YES);
        record.setStatus(STATUS_ACTIVE);
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        fileRecordMapper.insert(record);
        return record.getId();
    }

    /**
     * 校验业务类型（与 StorageKeyRules 的场景白名单保持一致）。
     *
     * @param bizType 业务类型
     */
    private void assertBizType(String bizType) {
        if (!ALLOWED_BIZ.contains(bizType)) {
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED, "非法 bizType：" + bizType);
        }
    }

    /**
     * 计算 MD5（小写十六进制）。
     *
     * @param content 文件字节
     * @return 32 位十六进制串
     */
    private String md5Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必备算法，走到这里说明运行环境坏了
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
