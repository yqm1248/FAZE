/*
 * 职责：PC 管理端列表响应结构（对齐 RuoYi 的 TableDataInfo）。
 * 调用方：所有 `/api/v1/admin/**` 的分页查询接口（A-03~A-09）。
 * 数据来源：MyBatis-Plus 的 IPage 或手工统计。
 * 注意：
 *   1. **顶层就是 rows / total，不能再包一层 `R<T>`**。RuoYi 前端页面的 `getList()` 直接读
 *      `res.rows` / `res.total`（接口规范 §5.2 与附录 C 裁决 4）。包成 `data.rows` 会让
 *      A-03~A-09 七个页面的表格全是空数据，而且不报错——最难查的那种错。
 *   2. `total` 用**原生 long**（不是 Long）：JacksonConfig 只把包装类 Long 转字符串，
 *      原生 long 保持 JSON 数字，前端分页器要的就是数字。
 *   3. 行对象里的 ID 字段仍必须是 `Long`（会被序列化成字符串，见接口规范 §5.3 裁决 5）。
 *   4. `msg` 固定「查询成功」，与 RuoYi 一致，前端只在失败分支显示 msg。
 */
package com.faze.common.pojo;

import java.io.Serializable;
import java.util.List;
import lombok.Data;

/**
 * PC 管理端分页响应。
 *
 * @param <T> 行对象类型
 */
@Data
public class TableDataInfo<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功码，固定 200（RuoYi 前端只在 code !== 200 时报错）。 */
    private int code;

    /** 提示文案。 */
    private String msg;

    /** 总条数（原生 long，保持 JSON 数字）。 */
    private long total;

    /** 当前页数据（**字段名必须是 rows**）。 */
    private List<T> rows;

    /**
     * 组装分页响应。
     *
     * @param rows  当前页数据，null 视为空集合（接口规范 §5.3：空集合返回 [] 不返回 null）
     * @param total 总条数
     * @param <T>   行对象类型
     * @return 分页响应
     */
    public static <T> TableDataInfo<T> of(List<T> rows, long total) {
        TableDataInfo<T> info = new TableDataInfo<>();
        info.setCode(200);
        info.setMsg("查询成功");
        info.setTotal(total);
        info.setRows(rows == null ? List.of() : rows);
        return info;
    }
}
