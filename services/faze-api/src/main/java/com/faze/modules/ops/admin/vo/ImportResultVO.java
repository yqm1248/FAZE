/*
 * 职责：A-25 批量导入的结果出参。
 * 调用方：modules/ops 的 AdminSensitiveWordService / AdminSensitiveWordController。
 * 数据来源：CSV 解析与逐行校验的产出。
 * 注意：
 *   1. **一次收全所有错误**（工作流 B1 第 2 条）：不是遇到第一行错就中断，
 *      而是把每行的问题都收集起来一次返回，让运营改一次就能重传。
 *   2. `errors` 有上限（默认 200 条）：5000 行全错时不能把响应刷爆；
 *      超过上限时 `truncated=true` 提示「错误太多，只显示前 N 条」。
 *   3. **全对才写**：只要 `errors` 非空，`success` 必为 0、库里一行都不写。
 *      这是「事务内全量校验 → 全量写入」的语义，不是「跳过错误行继续导」。
 */
package com.faze.modules.ops.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * 导入结果。
 */
@Data
public class ImportResultVO {

    /** 成功写入行数；有错误时恒为 0。 */
    private int success;

    /** 失败行数。 */
    private int failed;

    /** 错误明细（行号从 2 开始：第 1 行是表头）。 */
    private List<ErrorItem> errors;

    /** 错误是否被截断（超过上限只返回前 N 条）。 */
    private boolean truncated;

    /**
     * 单行错误。
     */
    @Data
    public static class ErrorItem {

        /** CSV 行号（1 基，第 1 行是表头）。 */
        private int line;

        /** 原因。 */
        private String reason;

        /**
         * 构造错误项。
         *
         * @param line   行号
         * @param reason 原因
         * @return 错误项
         */
        public static ErrorItem of(int line, String reason) {
            ErrorItem item = new ErrorItem();
            item.setLine(line);
            item.setReason(reason);
            return item;
        }
    }
}
