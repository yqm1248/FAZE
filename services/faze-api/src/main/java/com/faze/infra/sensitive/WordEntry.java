/*
 * 职责：敏感词引擎的输入数据单元——一条词库记录的业务视图（与表结构解耦）。
 * 调用方：modules/ops 的 SensitiveWordLoader（从 DB 加载后传入引擎）、SensitiveWordEngine 的 reload。
 * 数据来源：`sensitive_word` 表（word / category / level / lang / id）。
 * 注意：
 *   1. **故意不引用 modules/ops 的枚举**：infra 层不能反向依赖业务域（后端规范 §2.2 分层方向）。
 *      所以 level 在这里是字符串（"block"/"review"/"warn"），由业务层负责与
 *      `SensitiveLevel` 枚举互转。
 *   2. 一个 (word, lang) 唯一（uk_word_lang），所以同一棵树里不会出现重复词条。
 *   3. 只带引擎判定必需的 5 个字段：命中回写 hit_count 用 id，展示用 category/level。
 *
 * @param id       词条 id（sensitive_word.id），命中统计回写用
 * @param word     词条原文（子串匹配，不分词）
 * @param category 分类（POLITICS / VIOLENCE / PORN / FRAUD / ILLEGAL）
 * @param level    级别（block / review / warn）
 * @param lang     语种（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）
 */
package com.faze.infra.sensitive;

/**
 * 敏感词条（引擎内部视图）。
 *
 * @param id       词条主键
 * @param word     词条原文
 * @param category 分类
 * @param level    级别
 * @param lang     语种
 */
public record WordEntry(Long id, String word, String category, String level, String lang) {
}
