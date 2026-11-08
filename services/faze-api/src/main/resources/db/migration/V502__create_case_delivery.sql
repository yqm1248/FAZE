-- ============================================================================
-- 迁移：V502__create_case_delivery.sql
-- 所属段：第 5 段（送达）
-- 做什么：建 case_delivery（13 字段）——文书送达记录。
-- 依赖：V500 的 legal_case、V400 的 document（document_id 指向它，且该文书必须有 PDF）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.7 末尾「17a. 文书送达（v1.30 段 5 新增）」DDL 原文照抄；
--   《第 5 段工作流》§零之二 第 2、15 项与 §三 C 组。
--
-- 边界（写进表注释，防止后人扩成法院转达）：
--   只送**平台自有文书**（段 4 生成的 PDF），不做法院文书转达；
--   回执收据号 SD 是**平台凭证**，不是《人民法院送达回证》。
-- ============================================================================

CREATE TABLE IF NOT EXISTS case_delivery (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  case_id     BIGINT       NOT NULL COMMENT 'legal_case.id',
  document_id BIGINT       NOT NULL COMMENT 'document.id（该文书必须已生成 PDF，否则不允许送达）',
  user_id     BIGINT       NOT NULL COMMENT '收件人 user_id（冗余一列：按用户查送达列表时不必 join legal_case）',
  method      VARCHAR(20)  NOT NULL COMMENT 'platform 平台内电子送达 / sms_pickup 短信通知自行查看 / mail 邮寄纸质',
  sent_at     DATETIME     NOT NULL COMMENT '发起送达时间（UTC）；送达时间即证据',
  signed_at   DATETIME              DEFAULT NULL COMMENT '签收时间（UTC）；未签收为 NULL',
  receipt_no  VARCHAR(32)  NOT NULL COMMENT '回执号 SD+yyyyMMdd+6位，发起送达时即生成（唯一）',
  sign_device VARCHAR(100)          DEFAULT NULL COMMENT '签收设备（型号 + OS），签收时写入',
  ip          VARCHAR(45)           DEFAULT NULL COMMENT '签收 IP（VARCHAR(45) 兼容 IPv6）',
  status      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待签收 / signed 已签收',
  created_at  DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_delivery_receipt (receipt_no),
  KEY idx_delivery_user (user_id, status, sent_at),
  KEY idx_delivery_case (case_id),
  KEY idx_delivery_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文书送达记录；只送平台自有文书，不做法院转达；回执号是平台凭证';
