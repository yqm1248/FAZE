/*
 * 职责：A-27 批量导入的结果。
 * 调用方：modules/ai/admin/controller/AdminLawItemController#importJson / #importFile。
 * 数据来源：LawItemAdminService#importItems（逐条校验 + 幂等判重后的统计）。
 * 注意：
 *   1. 结构按工作流 H1 第 3 条冻结：`{ total, inserted, skipped, failed: [{index, reason}] }`。
 *      `total` = 提交条数，且 `total = inserted + skipped + failed.size()` 恒成立（验收断言用）。
 *   2. `index` 是**提交数组的下标（从 0 开始）**：前端按它把错误行标到表格/预览的第 index+1 行。
 *   3. `reason` 是**稳定的中文文案**（如「lawCode 必填」「effectiveDate 格式应为 yyyy-MM-dd」），
 *      不含 SQL、异常类名、堆栈——导出给运营看也不会泄露实现。
 *   4. `skipped` 是幂等命中（`(law_code, article_no, effective_date)` 已存在或同批重复），
 *      **不报错**：重复导入同一份文件的结果是 `inserted=0, skipped=total`（工作流「算做完」）。
 */
package com.faze.modules.ai.admin.vo;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A-27 批量导入结果。
 */
@Data
public class AdminLawItemImportResultVO {

    /** 提交条数。 */
    private int total;

    /** 实际插入条数。 */
    private int inserted;

    /** 幂等跳过条数（键已存在 / 同批重复）。 */
    private int skipped;

    /** 校验或写入失败的条目（index 从 0 开始）。 */
    private List<FailedItem> failed = new ArrayList<>();

    /**
     * 单条失败明细。
     */
    @Data
    public static class FailedItem {

        /** 提交数组下标（从 0 开始）。 */
        private int index;

        /** 稳定的中文失败原因（不含内部细节）。 */
        private String reason;

        /**
         * 构造失败明细。
         *
         * @param index  提交数组下标（从 0 开始）
         * @param reason 失败原因（稳定中文文案）
         * @return 明细对象
         */
        public static FailedItem of(int index, String reason) {
            FailedItem item = new FailedItem();
            item.setIndex(index);
            item.setReason(reason);
            return item;
        }
    }
}
