-- ============================================================
-- 迁移：V1__baseline.sql
-- 所属段：第 0 段（可安装底座 + 三项探针）
-- 做什么：建 app_user + law_item 两张表结构，不含数据
-- 依赖：无（首个迁移；库由 database/00-create-database.sql 先建好）
-- 可回滚：本地库可 flyway clean 重建；已进 main 后不可改，只能追加 V1_1__fix_*.sql
-- 编写：2026-11-02
-- 依据：《法泽FAZE_V1基线表结构_v1.0.md》§三 / §四（逐字抄，不自行加字段、不调索引）
-- ============================================================

-- ============================================================================
-- 表名：app_user
-- 职责：C 端用户主表。C 端用户与律师共用此表，律师身份在 lawyer 表扩展
-- 调用方：account 域（登录、注册、实名、资料）；后续 8 个域通过 user_id 引用
-- 数据来源：App 注册 / PC 后台创建
-- 注意：PC 后台管理员见 RuoYi 的 sys_user，**两者不混用**
-- ============================================================================
CREATE TABLE app_user (
  id                  BIGINT       PRIMARY KEY AUTO_INCREMENT       COMMENT '主键',
  phone               VARCHAR(20)  NOT NULL                         COMMENT '手机号，登录账号，唯一',
  password_hash       VARCHAR(100)                                  COMMENT '密码哈希，BCrypt 60 字符；第三方登录用户可为 NULL',
  nickname            VARCHAR(50)                                   COMMENT '昵称',
  avatar_url          VARCHAR(500)                                  COMMENT '头像地址',
  gender              TINYINT      DEFAULT 0                        COMMENT '0未知 1男 2女',
  birth_date          DATE                                          COMMENT '出生日期，用于未成年人识别',
  role                VARCHAR(20)  DEFAULT 'user'                   COMMENT 'user / lawyer / customer_service / admin',
  lang                VARCHAR(20)  DEFAULT 'zh-Hans'                COMMENT '语言偏好：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN',
  realname_status     VARCHAR(20)  DEFAULT 'none'                   COMMENT 'none / pending / approved / rejected',
  is_minor            TINYINT      NOT NULL DEFAULT 0               COMMENT '不满 18 周岁即置 1；注册不限年龄',
  guardian_phone      VARCHAR(20)                                   COMMENT '监护人手机号，未成年人必填',
  guardian_consent_at DATETIME                                      COMMENT '监护人同意时间',
  status              VARCHAR(20)  DEFAULT 'active'                 COMMENT 'active / blocked / cancelled',
  last_login_at       DATETIME                                      COMMENT '最后登录时间',
  last_login_ip       VARCHAR(50)                                   COMMENT '最后登录 IP',
  created_at          DATETIME     NOT NULL                         COMMENT '创建时间',
  updated_at          DATETIME     NOT NULL                         COMMENT '更新时间',
  deleted_at          DATETIME                                      COMMENT '软删除时间；NULL 表示未删除',
  UNIQUE KEY uk_app_user_phone (phone),
  KEY idx_app_user_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='C 端用户表；PC 后台管理员见 sys_user，两者不混用';

-- ============================================================================
-- 表名：law_item
-- 职责：本地法条库。AI 只能引用本表存在的条文（幻觉防护的物理基础）
-- 调用方：ai 域（检索增强）、document 域（文书引用条文）、content 域（文章绑定条文）
-- 数据来源：Flyway 基础数据迁移（V2__init_law_items.sql），**不是种子数据**
-- 注意：全量 2000–3000 条在第 3 段导入；第 0 段只灌 5 条用于验证链路
-- ============================================================================
CREATE TABLE law_item (
  id             BIGINT       PRIMARY KEY AUTO_INCREMENT  COMMENT '主键',
  law_code       VARCHAR(50)  NOT NULL                    COMMENT '法律简称码：civil_code / civil_procedure / labor_contract 等',
  law_name       VARCHAR(100) NOT NULL                    COMMENT '法律全称：中华人民共和国民法典',
  article_no     VARCHAR(50)  NOT NULL                    COMMENT '条号文本：第六百八十条',
  article_seq    INT                                      COMMENT '条号数字序号，用于排序与范围查询',
  content        MEDIUMTEXT   NOT NULL                    COMMENT '条文原文',
  effective_date DATE         NOT NULL                    COMMENT '生效日期，参与唯一键',
  expire_date    DATE                                     COMMENT '失效日期；NULL 表示现行有效',
  status         VARCHAR(20)  NOT NULL DEFAULT 'active'   COMMENT 'active / amended / repealed',
  keywords       VARCHAR(500)                             COMMENT '检索用关键词，人工维护',
  created_at     DATETIME     NOT NULL                    COMMENT '创建时间',
  updated_at     DATETIME     NOT NULL                    COMMENT '更新时间',
  UNIQUE KEY uk_law_article (law_code, article_no, effective_date),
  KEY idx_law_code_seq (law_code, article_seq),
  FULLTEXT KEY ft_law_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='法条库；AI 只能引用本表存在的条文';
