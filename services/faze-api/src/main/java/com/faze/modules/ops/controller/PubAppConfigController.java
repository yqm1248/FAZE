/*
 * 职责：APP 端「聚合公开配置」接口——一次返回客服在线时段 + 客服电话，
 *       供 APP 55（在线客服）/ 56（常见问题）/「关于」页直接渲染。
 * 调用方：apps/mobile-app 的客服域页面（C5 三页共用一份配置，见第 7 段 C5）。
 * 数据来源：faze.sys_config 的 `service.online_hours` / `service.phone`，
 *           经 SysConfigQueryService 只读查询（每次实时查库，不缓存）。
 * 注意：
 *   1. **为什么单独开聚合接口，不复用 `/pub/config/{configKey}`**（四个理由，缺一不可）：
 *      a) **缺失语义相反**。`/pub/config/{key}` 服务的是「法律文本正文」，键不存在或
 *         正文为空一律抛 401004，让页面走「没有这份协议」的空态——这是对的，渲染一张
 *         空白协议比报错更糟。而客服电话/在线时段**允许缺失**：缺了应当显示默认时段、
 *         隐藏电话行。同一个接口没法同时表达「没有就报错」和「没有就用默认」。
 *      b) **页面要两个值**。复用单键接口意味着 APP 发 2 次请求、还要自己拼默认值；
 *         默认值口径一旦落到客户端，四个语言版本各写一遍必然漂移。默认值只能有一份，
 *         放在服务端（见下方常量）。
 *      c) **不能稀释白名单的语义**。`PubConfigController.PUBLIC_CONFIG_KEYS` 的定义是
 *         「本来就是给用户看的长文本」，白名单越宽，将来审计时越难说清「谁该在里面」。
 *         短配置走独立聚合接口，白名单保持只装法务文本。
 *      d) 出参形状不同：长文本需要 `updatedAt` 给页面显示「最后更新时间」，短值不需要。
 *   2. **键缺失/为空一律给默认值，不抛错**（任务硬要求：不许 500）。`getString` 内部
 *      已把 `null` 与空白都判为缺失并回退默认值，所以这里不需要再判一次。
 *   3. **电话缺失时返回空串而不是占位号**。`0999-XXXXXXX` 这类占位号一旦被渲染给用户，
 *      用户会拨到空号——宁可前端隐藏这一行，也不给假号码。
 *   4. **免登录**：`/api/v1/pub/**` 在 SecurityConfig 里是 permitAll，所以本接口不接收
 *      `@AuthenticationPrincipal`、不写登录态、不做限流之外的处理。
 *   5. 纯查询，按《审计与日志规范》§3.2 不标 `@OperationLog`。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.modules.ops.service.SysConfigQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * APP 端聚合公开配置接口。
 */
@RestController
@RequestMapping("/api/v1/pub/app")
@RequiredArgsConstructor
public class PubAppConfigController {

    /** 客服在线时段的配置键（V703 种子，值形如 `09:00-20:00`）。 */
    private static final String KEY_ONLINE_HOURS = "service.online_hours";

    /** 客服电话的配置键（V707 起为正式号 `002-00854967`）。 */
    private static final String KEY_PHONE = "service.phone";

    /**
     * 在线时段的默认值。
     *
     * <p>与 V703 种子值一致。键被删掉时退回它，而不是让页面显示「暂无在线时段」
     * ——客服时段是稳定业务事实，不是可选内容。</p>
     */
    private static final String DEFAULT_ONLINE_HOURS = "09:00-20:00";

    /**
     * 电话的默认值：空串。
     *
     * <p>**故意不给占位号**：键缺失说明运营还没配，此时前端应隐藏电话行；
     * 给一个假号码比不给更坏。</p>
     */
    private static final String DEFAULT_PHONE = "";

    /** 平台参数只读查询。 */
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 取 APP 需要的公开配置。
     *
     * <p>无副作用（只读 sys_config）。两个键都缺失时仍返回 200 + 默认值，
     * 不会因为配置没准备好让 APP 端启动即报错。</p>
     *
     * @return 客服在线时段与客服电话
     */
    @GetMapping("/config")
    public R<AppPublicConfigVO> config() {
        AppPublicConfigVO vo = new AppPublicConfigVO();
        vo.setOnlineHours(sysConfigQueryService.getString(KEY_ONLINE_HOURS, DEFAULT_ONLINE_HOURS));
        vo.setPhone(sysConfigQueryService.getString(KEY_PHONE, DEFAULT_PHONE));
        return R.ok(vo);
    }

    /**
     * 出参：APP 端公开配置。
     *
     * <p>字段名与 APP 侧 `features/support/api/appConfig.ts` 的 `AppPublicConfig` 一一对应，
     * 改字段名必须同步改前端（段 7 C5 的约定）。</p>
     */
    public static class AppPublicConfigVO {

        /** 客服在线时段（如 `09:00-20:00`），永远有值。 */
        private String onlineHours;

        /** 客服电话；空串表示「未配置」，前端应隐藏电话行。 */
        private String phone;

        /**
         * 取在线时段。
         *
         * @return 在线时段
         */
        public String getOnlineHours() {
            return onlineHours;
        }

        /**
         * 设置在线时段。
         *
         * @param onlineHours 在线时段
         */
        public void setOnlineHours(String onlineHours) {
            this.onlineHours = onlineHours;
        }

        /**
         * 取客服电话。
         *
         * @return 客服电话；空串表示未配置
         */
        public String getPhone() {
            return phone;
        }

        /**
         * 设置客服电话。
         *
         * @param phone 客服电话
         */
        public void setPhone(String phone) {
            this.phone = phone;
        }
    }
}
