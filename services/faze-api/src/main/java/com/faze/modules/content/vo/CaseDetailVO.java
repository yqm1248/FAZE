/*
 * 职责：App 案例详情（19-case-detail）。
 * 调用方：CaseLibraryQueryService#detail、AppCaseLibraryController。
 * 数据来源：case_library + case_library_translation（按 lang 取译文，缺译回退中文）。
 * 注意：
 *   1. **不返回法院案号、不返回法院全名**（19-case-detail 的红线，工作流 G2 第 4 条）。
 *   2. 正文按语言取：`lang=zh-Hans` 读主表；其它语言读译文，缺译时回退中文并把
 *      `translated=false`，页面据此提示「该案例暂无本语言版本」。
 *   3. `lawRefs` 里给 `lawName` + `articleNo` + `lawItemId`：前端拼成
 *      「《中华人民共和国民法典》第七百三十三条」，点击时用 `lawItemId` 查原文。
 *   4. `disclaimer` 由服务端给固定文案，前端不自己拼（i18n 时只改一处）。
 */
package com.faze.modules.content.vo;

import java.util.List;
import lombok.Data;

/**
 * App 案例详情。
 */
@Data
public class CaseDetailVO {

    /** 主键。 */
    private Long id;

    /** 标题（按语言）。 */
    private String title;

    /** 关键词标签（原样字符串，前端按逗号切）。 */
    private String keywords;

    /** 来源说明（脱敏后）。 */
    private String source;

    /** 裁判年份（详情页「来源·年份」用）；无裁判日期时为 null。 */
    private Integer judgmentYear;

    /** 浏览量。 */
    private Integer viewCount;

    /** 案情简介。 */
    private String summary;

    /** 争议焦点。 */
    private String disputeFocus;

    /** 裁判结果。 */
    private String judgment;

    /** 案例启示。 */
    private String insight;

    /** 关联法条。 */
    private List<LawRefVO> lawRefs;

    /** 是否命中了请求语言的译文；false 表示回退到中文。 */
    private Boolean translated;

    /** 当前用户是否已收藏（未登录恒 false；收藏走 social_action，不新建收藏表）。 */
    private Boolean favorited;

    /** 免责声明（固定文案，来自 i18n 的前端展示仍需走 t()，这里给出中文源串供参考）。 */
    private String disclaimer;

    /** 关联法条项（App 渲染用）。 */
    @Data
    public static class LawRefVO {

        /** 法律全称。 */
        private String lawName;

        /** 条号原文。 */
        private String articleNo;

        /** 法条库主键（点开原文用）。 */
        private Long lawItemId;

        /**
         * 构造法条项。
         *
         * @param lawName    法律全称
         * @param articleNo  条号原文
         * @param lawItemId  法条库主键
         */
        public LawRefVO(String lawName, String articleNo, Long lawItemId) {
            this.lawName = lawName;
            this.articleNo = articleNo;
            this.lawItemId = lawItemId;
        }
    }
}
