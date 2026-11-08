/*
 * 职责：提交订单评价的入参（G5.1，`POST /app/orders/{id}/review`）。
 * 调用方：modules/trade/controller/OrderController#review。
 * 数据来源：请求体。
 * 注意：
 *   1. `rating` **必填且 1~5**：星级是评价的唯一硬信息，其余都能省（设计稿 34-review.html
 *      明确「只有星级必填」，标签与文字选填）。
 *   2. `tags` 最多 6 个、每个 ≤20 字：设计稿只给 6 个预设标签，字段是 JSON 数组，
 *      服务端不锁枚举（运营改文案不用发版），但必须拦住超长/超量——JSON 列不做长度校验。
 *   3. `content` ≤500 与 `review.content VARCHAR(500)` 对齐；**前端设计稿按 200 字截断**
 *      （34-review.html 的 maxlength=200），服务端取表宽上限，两条口径都合法：
 *      客户端更严不会写入超长，服务端更宽不会误拒历史客户端。
 *   4. `isAnonymous` 缺省按 false（不匿名）：匿名是用户的主动选择，默认匿名会让评价区
 *      全是「匿名用户」，反而降低可信度。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * 评价提交入参。
 */
@Data
public class ReviewSubmitBO {

    /** 评分 1~5（必填）。 */
    @NotNull(message = "评分不能为空")
    private Integer rating;

    /** 标签数组（可空，最多 6 个）。 */
    @Size(max = 6, message = "标签最多选 6 个")
    private List<String> tags;

    /** 评价正文（可空，≤500 字）。 */
    @Size(max = 500, message = "评价正文最长 500 字")
    private String content;

    /** 是否匿名（缺省 false）。 */
    private Boolean isAnonymous;
}
