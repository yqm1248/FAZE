-- ============================================================================
-- 迁移：V400__create_document_tables.sql
-- 所属段：第 4 段（文书中心）
-- 做什么：建 doc_template（13 字段）/ document（16 字段）/ signature（15 字段）/
--         file_record（14 字段）四张表，**不含任何数据**。
--         5 份模板是运营在 A-18 上传的运行时数据，不是种子数据（§三 A1 第 1 条）。
-- 依赖：V1 的 app_user（逻辑引用，本库不建物理外键——见《数据表规范》附录 C 第 10 条）、
--       V200 的 task_queue（DOC_GENERATE 任务的 biz_no = document.id）。
-- 可回滚：见 db/rollback/V400_1__drop_document_tables.sql（**手动执行，不进 flyway_schema_history**）。
-- 编写：2026-11-07
-- 依据：
--   《第 4 段工作流》§三 A1（doc_template / document 的字段逐字按该节，不许自己加）；
--   技术路线 §5.6（signature 15 字段）/ §5.11（file_record 14 字段）原文照抄；
--   《数据表规范》§四（四类表公共字段）、§六（COLLATE utf8mb4_0900_ai_ci）、§七（字段注释）。
--
-- 与旧 DDL 的差异（都是段 4 的裁决，见工作流 §零.3）：
--   ① doc_template：template_url / oss_key 删掉，改 template_html（MEDIUMTEXT，HTML 存库不存 OSS）；
--      preview_url → preview_oss_key；补 version（同一 code+lang 多版本并存）。
--   ② document：word_url 整个删掉（不出 Word）；pdf_url/signed_pdf_url → *_oss_key；
--      status 增加 failed（让「失败不留半成品」可查，而不是靠「没有记录」推断）。
--   ③ signature：技术路线原文照抄，**document_id 必须可空**（空=签名库，非空=某份文书上的这枚签名）。
--   ④ file_record：技术路线原文照抄，本段是它第一次真正使用。
--
-- 版本号：按《数据表规范》§8.1，第 4 段用 V400~V499。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 文书模板（D 基础数据表）
--    **模板正文存库不存 OSS**：HTML 通常 5~50KB，入库便于版本管理与占位符扫描
--    （要在保存时扫 {{}} 与 field_schema 一一对应，存 OSS 就得先下载再扫）。
--    版本留痕：改模板不是原地 UPDATE，而是 INSERT 一条 (code, lang, version+1) 新行、
--    把旧行置 inactive。旧行永不删、永不改——历史文书重渲染靠 (template_id, template_version) 找回。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS doc_template (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  code             VARCHAR(50)  NOT NULL COMMENT '模板编码，如 LAWSUIT-001',
  name             VARCHAR(100) NOT NULL COMMENT '模板名',
  category         VARCHAR(50)           DEFAULT NULL COMMENT '分类：诉讼文书 / 合同文书 / 劳动文书 / 跨境电商',
  template_html    MEDIUMTEXT   NOT NULL COMMENT '模板 HTML 全文（含 {{占位符}} 与 <style>）；必须 well-formed XHTML',
  preview_oss_key  VARCHAR(500)          DEFAULT NULL COMMENT '模板预览缩略图对象键（渲染后第一页转 PNG），A-18 列表用',
  field_schema     JSON                  DEFAULT NULL COMMENT '字段定义（6 种 type：text/textarea/number/date/select/array）',
  lang             VARCHAR(20)  NOT NULL DEFAULT 'zh-Hans' COMMENT '模板语言；段 4 固定 zh-Hans（文书只做中文）',
  version          INT          NOT NULL DEFAULT 1 COMMENT '模板版本；每次保存 template_html 就 +1，旧版本行另存且置 inactive',
  sort_order       INT                   DEFAULT 0 COMMENT 'App 端排序',
  status           VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / active 启用 / inactive 停用（停用而非删除）',
  created_at       DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at       DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_template_code_lang_version (code, lang, version),
  KEY idx_template_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文书模板；HTML 存库不存 OSS；改版插新版本行、旧行置 inactive，历史文书按 template_version 复现';

-- ---------------------------------------------------------------------------
-- 2. 文书记录（C 状态机表）
--    status：draft → generated → signed；失败为 failed（不是「没有记录」）。
--    form_snapshot 是「生成成功那一刻」的表单冻结，之后 form_data 怎么改都不动它，
--    这是「模板/表单热更新不改历史文书」锚点（4-A4）的另一半。
--    *_oss_key 指向私有存储的对象键，**不存完整 URL**（《数据表规范》附录 B）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id            BIGINT       NOT NULL COMMENT '归属用户，逻辑引用 app_user.id；所有按 id 的读写必须带此条件',
  template_id        BIGINT                DEFAULT NULL COMMENT '引用 doc_template.id；可空（模板停用后历史文书仍在）',
  template_version   INT                   DEFAULT NULL COMMENT '生成时用的模板版本（复现依据）',
  lang               VARCHAR(20)  NOT NULL DEFAULT 'zh-Hans' COMMENT '文书语言；段 4 固定 zh-Hans',
  title              VARCHAR(200) NOT NULL COMMENT '文书标题（模板名 + 当事人）',
  doc_type           VARCHAR(30)           DEFAULT NULL COMMENT '冗余模板分类，我的文书列表筛选用',
  form_data          JSON                  DEFAULT NULL COMMENT '当前表单数据（草稿可改）',
  form_snapshot      JSON                  DEFAULT NULL COMMENT '生成瞬间的表单快照，不可变',
  status             VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft / generated / signed / failed',
  pdf_oss_key        VARCHAR(500)          DEFAULT NULL COMMENT '生成的 PDF 对象键；status=failed 时必须为 NULL',
  signed_pdf_oss_key VARCHAR(500)          DEFAULT NULL COMMENT '叠完签名的 PDF 对象键；不覆盖 pdf_oss_key',
  lawyer_id          BIGINT                DEFAULT NULL COMMENT '分享给律师时记；段 4 只写不读（律师端在段 5）',
  created_at         DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at         DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at         DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 表示未删',
  PRIMARY KEY (id),
  KEY idx_document_user (user_id, deleted_at, created_at),
  KEY idx_document_template (template_id, template_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文书记录；失败落 status=failed 且两个 *_oss_key 为 NULL（失败不留半成品）';

-- ---------------------------------------------------------------------------
-- 3. 电子签章（B 追加型表）
--    **一表两用，所有查询必须显式带 document_id IS NULL / IS NOT NULL**：
--      document_id IS NULL     → 用户存的签名图（28-doc-seal 管理页）
--      document_id IS NOT NULL → 某份文书上的这枚签名（含坐标与哈希）
--    写成「按 user_id 查」会把两件事混在一起，是本段最容易出的数据错误。
--    时间：B 类只有 created_at；signed_at 是业务时间（同一文书多次签署时区分）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS signature (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id        BIGINT       NOT NULL COMMENT '归属用户，逻辑引用 app_user.id',
  image_oss_key  VARCHAR(500) NOT NULL COMMENT '签名 PNG 对象键（私有，透明底），不存公开 URL',
  document_id    BIGINT                DEFAULT NULL COMMENT 'NULL=签名库；非空=这份文书上的这枚签名。**不许写成按 user_id 查全部**',
  page_no        INT                   DEFAULT NULL COMMENT '第几页（从 1 开始）',
  pos_x_pt       INT                   DEFAULT NULL COMMENT '相对页面左上角，单位 pt（1pt = 1/72 inch）',
  pos_y_pt       INT                   DEFAULT NULL COMMENT '屏幕坐标系；PDF 原点在左下，落图时要换算',
  width_pt       INT                   DEFAULT NULL COMMENT '签名图在页面上的宽度（pt）',
  height_pt      INT                   DEFAULT NULL COMMENT '签名图在页面上的高度（pt）',
  base_page_w_pt INT                   DEFAULT NULL COMMENT '渲染时页面宽度（A4 = 595pt），用于校验缩放',
  base_page_h_pt INT                   DEFAULT NULL COMMENT '渲染时页面高度（A4 = 842pt）',
  document_hash  VARCHAR(128)          DEFAULT NULL COMMENT '对「盖签前」内容流算的哈希，用于事后比对',
  hash_algo      VARCHAR(20)           DEFAULT 'SHA-256' COMMENT '哈希算法',
  signed_at      DATETIME     NOT NULL COMMENT '签署时间（UTC，应用层填）；同一文书多次签署时区分',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  PRIMARY KEY (id),
  KEY idx_signature_user (user_id, document_id),
  KEY idx_signature_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='电子签章；document_id 空=签名库、非空=文书上的签名，查询必须显式区分';

-- ---------------------------------------------------------------------------
-- 4. 上传文件登记（A 业务实体表）
--    段 1 裁决「实名影像直接存 realname_auth 字段」，所以这张表到段 4 才第一次真正使用：
--    签名 PNG（biz_type=signature）、文书 PDF（document）、模板预览图（doc_template）。
--    业务表只存 oss_key，不存完整 URL（《数据表规范》附录 B L599）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_record (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  oss_key       VARCHAR(500) NOT NULL COMMENT '对象键（私有存储的相对路径）',
  bucket        VARCHAR(100) NOT NULL COMMENT '所在桶/根；本地实现固定为 local',
  original_name VARCHAR(255)          DEFAULT NULL COMMENT '原始文件名（只做展示，不参与路径）',
  content_type  VARCHAR(100)          DEFAULT NULL COMMENT 'MIME 类型',
  size_bytes    BIGINT                DEFAULT NULL COMMENT '字节数',
  md5           VARCHAR(32)           DEFAULT NULL COMMENT '内容 MD5，用于去重与完整性校验',
  biz_type      VARCHAR(30)           DEFAULT NULL COMMENT 'doc_template / document / signature',
  biz_id        BIGINT                DEFAULT NULL COMMENT '对应业务表主键',
  uploader_id   BIGINT       NOT NULL COMMENT '上传者（用户 id；服务端生成的记文书归属用户，模板预览记 0）',
  is_private    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否私有（1 私有，本项目恒为 1）',
  status        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active / orphan（孤儿文件，供清理任务）',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  deleted_at    DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_oss_key (oss_key),
  KEY idx_file_biz (biz_type, biz_id),
  KEY idx_file_uploader (uploader_id, created_at),
  KEY idx_file_orphan (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='上传文件登记；业务表存 oss_key，不存完整 URL';
