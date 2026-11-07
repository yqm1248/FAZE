/*
 * 职责：file_record 表的实体映射（段 4 A3，这张表第一次真正使用）。
 * 调用方：infra/storage/mapper/FileRecordMapper、infra/storage/FileRecordService。
 * 数据来源：V400__create_document_tables.sql 建的 file_record（14 字段）。
 * 注意：
 *   1. **业务表只存 oss_key，不存完整 URL**（《数据表规范》附录 B L599）：
 *      完整 URL 里的签名会过期，存进库第二天就是死链。
 *   2. `biz_type` 固定三个值：`doc_template` / `document` / `signature`（段 4 A3 第 3 条）。
 *      新增取值必须同时改 `StorageKeyRules` 的场景白名单，否则 key 生成会被拒。
 *   3. `status` 目前只有 `active`；孤儿文件清理（段 7）会把无主记录标 `orphan`，
 *      **不删行**——文件登记是审计链的一部分。
 */
package com.faze.infra.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上传文件登记。
 */
@Data
@TableName("file_record")
public class FileRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对象键（私有存储的相对路径）。 */
    private String ossKey;

    /** 所在桶/根；本地实现固定 `local`。 */
    private String bucket;

    /** 原始文件名（只做展示，不参与路径）。 */
    private String originalName;

    /** MIME 类型。 */
    private String contentType;

    /** 字节数。 */
    private Long sizeBytes;

    /** 内容 MD5（小写十六进制）。 */
    private String md5;

    /** 业务类型：doc_template / document / signature。 */
    private String bizType;

    /** 业务主键。 */
    private Long bizId;

    /** 上传者用户 id；服务端生成的文件记归属用户，模板预览记 0。 */
    private Long uploaderId;

    /** 是否私有（本项目恒为 1）。 */
    private Integer isPrivate;

    /** 状态：active / orphan。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
