/*
 * 职责：A-02「待我处理」5 类的出参。
 * 调用方：modules/ops 的 AdminDashboardService / AdminDashboardController。
 * 数据来源：DashboardMapper 的 5 个 COUNT 查询 + 超时工单数。
 * 注意：
 *   1. **`link` 由服务端给出**（带好查询参数），不让前端自己拼路由——
 *      跳转目标与筛选参数是「前后端约定」（工作流 §二.3 第 1 项），
 *      写在两处必然漂移。
 *   2. `extra` 是副文案（如「其中 2 条已超时」），没有就返回 null，前端不显示。
 *   3. 5 类的 key 固定：realname / lawyerApplication / withdraw / ticket / contentReview。
 *      前端按 key 做图标与跳转，**不要按 label 匹配**（label 会因翻译变化）。
 */
package com.faze.modules.ops.admin.vo;

import lombok.Data;

/**
 * 待办项。
 */
@Data
public class DashboardTodoVO {

    /** 固定 key：realname / lawyerApplication / withdraw / ticket / contentReview。 */
    private String key;

    /** 中文标签。 */
    private String label;

    /** 待处理条数。 */
    private Long count;

    /** 副文案（如「其中 2 条已超时」）；无则 null。 */
    private String extra;

    /** 跳转地址（含查询参数）；无跳转时为 null。 */
    private String link;

    /**
     * 构造待办项。
     *
     * @param key   固定 key
     * @param label 中文标签
     * @param count 条数
     * @param extra 副文案（可 null）
     * @param link  跳转地址（可 null）
     * @return 待办项
     */
    public static DashboardTodoVO of(String key, String label, Long count, String extra, String link) {
        DashboardTodoVO vo = new DashboardTodoVO();
        vo.setKey(key);
        vo.setLabel(label);
        vo.setCount(count);
        vo.setExtra(extra);
        vo.setLink(link);
        return vo;
    }
}
