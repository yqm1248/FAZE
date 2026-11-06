/*
 * 职责：article 表的数据访问。
 * 调用方：modules/content/service/ArticleService。
 * 数据来源：faze.article。
 * 注意：
 *   1. 继承 BaseMapper，单表条件用 LambdaQueryWrapper；本域不写 XML。
 *   2. **查询必须自带 `deleted_at IS NULL` 且 `status='published'`**（C 端只读已发布）：
 *      draft / archived 的文章不能出现在 C 端，漏一条就是内容事故。
 *   3. 列表排序 `published_at DESC, id DESC`：同一天发布的文章要有稳定顺序，
 *      否则分页会重复/漏条。
 */
package com.faze.modules.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.content.entity.Article;

/**
 * 文章 Mapper。
 */
public interface ArticleMapper extends BaseMapper<Article> {
}
