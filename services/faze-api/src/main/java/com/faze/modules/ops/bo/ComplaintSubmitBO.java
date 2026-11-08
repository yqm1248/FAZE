/*
 * 职责：APP 提交投诉的入参。
 * 调用方：modules/ops 的 ComplaintController（`POST /api/v1/app/complaints`）。
 * 数据来源：APP 57 投诉页。
 * 注意：
 *   1. **律师投诉必须关联已完成订单**（稿面明写）：`targetType='lawyer'` 时 `targetId`
 *      必须是「当前用户的、状态为 completed 的订单 id」，Service 里校验。
 *   2. `images` 是 oss_key 数组（≤3 张）：先走 `POST /app/upload/token` 直传拿到 key 再提交，
 *      **不是**在提交接口里传文件。
 *   3. `reason` ≤500：表列宽 500，超了会被 DB 截断（静默截断比报 400 更糟）。
 */
package com.faze.modules.ops.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 投诉提交入参。
 */
@Data
public class ComplaintSubmitBO {

    /** 投诉对象类型：lawyer / order / platform。 */
    @NotBlank(message = "投诉对象类型不能为空")
    @Pattern(regexp = "lawyer|order|platform", message = "投诉对象类型只能是 lawyer / order / platform")
    private String targetType;

    /** 投诉对象主键（律师投诉 = 订单 id；订单投诉 = 订单 id；平台投诉可空）。 */
    private Long targetId;

    /** 投诉原因与诉求（原文）。 */
    @NotBlank(message = "投诉内容不能为空")
    @Size(max = 500, message = "投诉内容不能超过 500 字")
    private String reason;

    /** 截图 oss_key（≤3 张）。 */
    @Size(max = 3, message = "截图最多 3 张")
    private List<String> images;
}
