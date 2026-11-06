/*
 * 职责：PC A-11 订单列表 / A-12 订单详情 / 状态流转的 HTTP 入口（G9.1 / G9.2）。
 * 调用方：`pc/admin-web/src/views/biz/trade/order/index.vue` 与 `detail.vue`
 *         （经 `/biz-api` 前缀代理到 8081）。
 * 数据来源：AdminOrderService（只读）。
 * 注意：
 *   1. **列表返回 RuoYi 口径**（顶层 `rows` / `total`，不包 `R<T>`）：PC 前端页面直接读
 *      `res.rows` / `res.total`（接口规范 §5.2 裁决 4）。详情/流水返回 `R<T>`。
 *   2. 整个 `/api/v1/admin/**` 在 SecurityConfig 里要求 `ROLE_ADMIN`（管理端 token 由
 *      RuoYi 签发、`AdminAuthenticationFilter` 还原角色），本类不再做权限判断。
 *   3. 本类不注入 Mapper、不写业务（《后端代码规范》§2.1）。
 *   4. 三个接口都是只读，**不标 `@OperationLog`**（纯查询不审计）。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.trade.admin.bo.AdminOrderQueryBO;
import com.faze.modules.trade.admin.service.AdminOrderService;
import com.faze.modules.trade.admin.vo.AdminOrderDetailVO;
import com.faze.modules.trade.admin.vo.AdminOrderRowVO;
import com.faze.modules.trade.admin.vo.AdminOrderStatusLogVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 订单接口。
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    /**
     * 订单列表（A-11）。
     *
     * @param query 状态 / 时间 / 律师 / 用户 / 分页
     * @return RuoYi 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminOrderRowVO> list(AdminOrderQueryBO query) {
        return adminOrderService.list(query);
    }

    /**
     * 订单详情（A-12）。
     *
     * @param id 订单主键
     * @return 详情（含支付记录、退款单、关联会话）
     */
    @GetMapping("/{id}")
    public R<AdminOrderDetailVO> detail(@PathVariable Long id) {
        return R.ok(adminOrderService.detail(id));
    }

    /**
     * 状态流转（A-12 时间线）。
     *
     * @param id 订单主键
     * @return 流水（时间正序）
     */
    @GetMapping("/{id}/status-log")
    public R<List<AdminOrderStatusLogVO>> statusLog(@PathVariable Long id) {
        return R.ok(adminOrderService.statusLog(id));
    }
}
