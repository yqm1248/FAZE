/*
 * 职责：E2 企业认证「查询状态」的出参结构（与 APP 企业认证页四态一一对应）。
 * 调用方：modules/account/service/EnterpriseService#query → EnterpriseController#query。
 * 数据来源：faze.enterprise_auth（一对一，uk_enterprise_user）。
 * 注意：
 *   1. `status` 取值 `none / pending / approved / rejected`：**`none` 表示库里没有这一行**，
 *      不是某个存储值。不要在库里造一条 status='none' 的记录。
 *   2. `submitted` 只在「有提交记录」时非 null；`none` 时为 null（前端可据此判断能否填写）。
 *   3. `reviewedAt` 只在 approved / rejected 有值（= `audited_at`）；pending 时为 null。
 *   4. 时间按 `Asia/Shanghai` 格式化成 `yyyy-MM-dd HH:mm:ss` 字符串：这是 APP 企业认证页
 *      直接展示「提交时间 / 审核时间」的字段，前端不做换算（与本接口的契约由 T2 前端确认）。
 *   5. **以实际表字段为准**：`enterprise_auth` 没有独立的「人工填写的提交时间」列，
 *      `submittedAt` 取 `updated_at`（每次重新提交都会更新它，符合「最近一次提交时间」语义）。
 */
package com.faze.modules.account.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 企业认证状态（含已提交内容）。
 */
@Data
public class EnterpriseAuthVO {

    /** 状态：none / pending / approved / rejected。 */
    private String status;

    /** 驳回原因；只有 rejected 时有值。 */
    private String rejectReason;

    /** 已提交内容；status=none 时为 null。 */
    private Submitted submitted;

    /**
     * 已提交内容（只读展示用）。
     */
    @Data
    public static class Submitted {

        /** 企业名称。 */
        private String companyName;

        /** 统一社会信用代码。 */
        private String creditCode;

        /** 法定代表人姓名。 */
        private String legalPerson;

        /** 营业执照对象 key（页面换短时签名 URL 展示）。 */
        private String licenseOssKey;

        /** 最近一次提交时间（Asia/Shanghai，`yyyy-MM-dd HH:mm:ss`）。 */
        private String submittedAt;

        /** 审核时间（Asia/Shanghai，`yyyy-MM-dd HH:mm:ss`）；未审核为 null。 */
        private String reviewedAt;
    }

    /**
     * 便捷构造：把库内 UTC 时间格式化成展示字符串。
     *
     * @param utc 库内 UTC 时间
     * @return `yyyy-MM-dd HH:mm:ss`（+08:00）；null 返回 null
     */
    public static String formatTime(LocalDateTime utc) {
        if (utc == null) {
            return null;
        }
        return utc.plusHours(8).toString().replace('T', ' ').substring(0, 19);
    }
}
