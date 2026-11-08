/*
 * 职责：APP 提交意见反馈的入参。
 * 调用方：modules/ops 的 FeedbackController（`POST /api/v1/app/feedback`）。
 * 数据来源：APP 58 反馈页。
 * 注意：
 *   1. **`content` 选填**（稿面「选填，填写有助于更快处理」）：空时服务端用 `type` 的中文标签
 *      填进 `content` 列（列是 NOT NULL），列表页仍可读。
 *   2. `contact` 选填，填了必须过手机号正则——联系不上用户的反馈等于白反馈。
 *   3. `images` 选填（≤3 张）。
 */
package com.faze.modules.ops.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 反馈提交入参。
 */
@Data
public class FeedbackSubmitBO {

    /** 反馈类型：feature / bug / experience / other。 */
    @NotBlank(message = "反馈类型不能为空")
    @Pattern(regexp = "feature|bug|experience|other", message = "反馈类型取值非法")
    private String type;

    /** 具体情况（选填）。 */
    @Size(max = 500, message = "具体情况不能超过 500 字")
    private String content;

    /** 截图 oss_key（选填，≤3 张）。 */
    @Size(max = 3, message = "截图最多 3 张")
    private List<String> images;

    /** 联系方式（选填手机号）。 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "联系方式格式不正确")
    private String contact;
}
