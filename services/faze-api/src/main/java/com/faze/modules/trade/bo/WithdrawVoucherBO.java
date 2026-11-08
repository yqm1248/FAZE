/*
 * 职责：PC 上传出款凭证的请求体（`POST /admin/withdrawals/{id}/voucher`）。
 * 调用方：modules/trade/admin/controller/AdminWithdrawController。
 * 数据来源：A-10 审核弹窗的「上传凭证」控件。
 * 注意：
 *   1. **文件用 base64 传**：见 FileRecordService#storeWithdrawVoucher 的注释——
 *      工作流原话是「走 /app/upload/token 直传」，但那是 C 端用户身份入口，
 *      管理员 token 走不通且 PC 无直传链路，本段改为服务端落盘（已记偏差）。
 *   2. `fileName` 只用来取扩展名（白名单 jpg/jpeg/png/webp/pdf），
 *      客户端给的名字不可信，不落库原名以外的任何路径信息。
 *   3. 体积上限由 Spring 的 `spring.servlet.multipart.max-request-size` 兜（10MB），
 *      BO 不再重复限制：凭证是小截图，10MB 足够，写死更小的数反而会误拒扫描件。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 出款凭证上传入参。
 */
@Data
public class WithdrawVoucherBO {

    /** 原始文件名（取扩展名用）。 */
    @NotBlank(message = "文件名不能为空")
    @Size(max = 200, message = "文件名不能超过 200 字")
    private String fileName;

    /** MIME 类型（可空，空则按二进制流处理）。 */
    @Size(max = 100, message = "MIME 类型过长")
    private String contentType;

    /** 文件内容（base64 编码，不含 data URL 前缀）。 */
    @NotBlank(message = "文件内容不能为空")
    private String contentBase64;
}
