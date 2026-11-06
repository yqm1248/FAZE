/*
 * 职责：游标分页的统一响应结构（IM 历史消息、律师列表等「不能用页码」的场景）。
 * 调用方：modules/conversation/controller/ConversationController、
 *        modules/conversation/ws/handler/ChatHistoryHandler。
 * 数据来源：无（纯结构体）。
 * 注意：
 *   1. **`hasMore` 由服务端算好**：前端不要拿 list.size() 推——最后一段恰好满页时
 *      该推理会多请求一次（多一次空响应，体验上是「闪一下」）。
 *   2. `nextCursor` 就是下一页要传的游标值（IM 消息场景 = 本批最小 `seq`）；
 *      `hasMore=false` 时必须是 null，客户端据此关掉下拉加载。
 *   3. 游标是字符串（与 Long 型 ID 一律序列化为字符串的口径一致）：避免 JS 大整数精度丢失。
 */
package com.faze.common.pojo;

import java.util.List;
import lombok.Data;

/**
 * 游标分页结果。
 *
 * @param <T> 元素类型
 */
@Data
public class CursorResult<T> {

    /** 本页数据。 */
    private List<T> list;

    /** 下一页游标；没有下一页时为 null。 */
    private String nextCursor;

    /** 是否还有更多。 */
    private boolean hasMore;

    /**
     * 构造游标分页结果。
     *
     * @param list       本页数据
     * @param nextCursor 下一页游标（没有则 null）
     * @param hasMore    是否还有更多
     * @param <T>        元素类型
     * @return 结果对象
     */
    public static <T> CursorResult<T> of(List<T> list, String nextCursor, boolean hasMore) {
        CursorResult<T> result = new CursorResult<>();
        result.setList(list == null ? List.of() : list);
        result.setNextCursor(hasMore ? nextCursor : null);
        result.setHasMore(hasMore);
        return result;
    }
}
