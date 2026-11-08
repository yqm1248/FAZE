/*
 * 职责：全局搜索（40-search）的分域响应（第 5 段 G3）。
 * 调用方：modules/content/controller/SearchController、apps/mobile-app 的 SearchPage。
 * 数据来源：lawyer / article / case_library / doc_template 四个域各一条查询。
 * 注意：
 *   1. **必须分域**，不许拼成一个大列表——这是总纲 §5.6「必须做」第 4 条的原话，
 *      也是 5-A8 的失败判据（「四类混在一个列表里 = 不过」）。
 *   2. 单域搜索时其余三个字段为 **null**（`@JsonInclude(NON_NULL)` 不输出），
 *      前端据「哪个字段有值」决定渲染单域页还是综合页。
 *   3. 每组都带 `total`：综合页要显示「查看全部 N 条」，不能只给前 3 条的条数。
 *   4. 「文书」域搜的是**模板**（doc_template），不是用户自己的文书，且**不显示价格**
 *      （V1 不作文书付费，工作流 G3 第 3 条）。
 */
package com.faze.modules.content.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 全局搜索分域响应。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchResponseVO {

    /** 律师域。 */
    private Group<LawyerHit> lawyers;

    /** 文章域。 */
    private Group<ArticleHit> articles;

    /** 案例域。 */
    private Group<CaseHit> cases;

    /** 文书模板域。 */
    private Group<DocTemplateHit> documents;

    /**
     * 分域结果容器。
     *
     * @param <T> 条目类型
     */
    @Data
    public static class Group<T> {

        /** 本组条目（综合页最多 3 条，单域页按分页给）。 */
        private List<T> list;

        /** 本组命中总数。 */
        private long total;

        /**
         * 构造分组。
         *
         * @param list  条目
         * @param total 总数
         * @param <T>   条目类型
         * @return 分组
         */
        public static <T> Group<T> of(List<T> list, long total) {
            Group<T> group = new Group<>();
            group.setList(list);
            group.setTotal(total);
            return group;
        }
    }

    /** 律师命中：跳段 2 的律师详情页。 */
    @Data
    public static class LawyerHit {

        /** 律师主键。 */
        private Long id;

        /** 姓名。 */
        private String name;

        /** 律所。 */
        private String lawFirm;

        /** 专长（最多 2 个，逗号已拆好的字符串）。 */
        private String specialtiesText;

        /** 头像 URL（可空，前端回退首字头像）。 */
        private String avatarUrl;
    }

    /** 文章命中：跳段 2 的文章详情页。 */
    @Data
    public static class ArticleHit {

        /** 文章主键。 */
        private Long id;

        /** 标题。 */
        private String title;

        /** 分类。 */
        private String category;

        /** 发布时间（UTC）。 */
        private LocalDateTime publishedAt;
    }

    /** 案例命中：跳本段的案例详情页。 */
    @Data
    public static class CaseHit {

        /** 案例主键。 */
        private Long id;

        /** 标题。 */
        private String title;

        /** 细分分类中文名。 */
        private String categoryLabel;

        /** 来源说明。 */
        private String source;
    }

    /** 文书模板命中：跳段 4 的模板表单页（带 templateId）。 */
    @Data
    public static class DocTemplateHit {

        /** 模板主键。 */
        private Long id;

        /** 模板名（如「民事起诉状」）。 */
        private String name;

        /** 分类（如「民间借贷」）。 */
        private String category;
    }
}
