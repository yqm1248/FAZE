/*
 * 职责：充值记录列表的游标分页结果（C 端 `cursor` 场景）。
 * 调用方：modules/trade/controller/RechargeController#list。
 * 数据来源：recharge_record。
 * 注意：
 *   1. **游标 = 上一页最后一条的 id**，下一页取 `id < cursor`（倒序）。
 *      不用 `pageNum`/offset：充值单在翻页期间会新增，offset 会漏行重行
 *      （《接口规范》C 端 `cursor` 场景，与案例列表一致）。
 *   2. `nextCursor` 声明为 `Long`（ID 类字段）→ 全局序列化成字符串，避免 JS 精度丢失；
 *      没有下一页时为 null，前端据此收起「加载更多」。
 *   3. `hasMore` 是**估算值**：Service 多查一条来判断，命中则本页舍弃第 limit+1 条。
 */
package com.faze.modules.trade.vo;

import java.util.List;
import lombok.Data;

/**
 * 充值记录分页。
 */
@Data
public class RechargeListPageVO {

    /** 本页条目（按 id 倒序）。 */
    private List<RechargeRowVO> list;

    /** 下一页游标（本页最后一条 id）；无下一页为 null。 */
    private Long nextCursor;

    /** 是否还有下一页。 */
    private boolean hasMore;
}
