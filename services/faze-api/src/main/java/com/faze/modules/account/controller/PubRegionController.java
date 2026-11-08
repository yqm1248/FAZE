/*
 * 职责：公开接口——返回「用户所在地区」可选值列表，供 APP 注册页渲染地区选择器。
 * 调用方：apps/mobile-app 注册页（**免登录**：注册时用户还没拿到 token）。
 * 数据来源：`sys_config.user.region_options`（JSON 数组字符串），经 RegionOptionsService 读取，
 *           配置缺失或写坏时回退内置兜底清单。
 * 注意：
 *   1. **免登录**：`/api/v1/pub/**` 在 SecurityConfig 里是 permitAll，所以本接口不接收
 *      `@AuthenticationPrincipal`、不写登录态。注册页在登录前就要调它，必须放这个前缀下。
 *   2. **出参形状固定为 `R<List<String>>`**：`data` 直接是字符串数组
 *      （`{"code":200,"data":["伊宁市",...]}`），**不包一层 `{regions:[...]}`**。
 *      这是与前端约定好的形状：包一层只会让前端多一次解引用，没有信息量增益。
 *      **改形状必须同时通知前端**。
 *   3. **列表顺序即展示顺序**（配置里的顺序）：不排序、不去重后重排——
 *      「伊宁市」放第一个是运营决定的（州府优先），不是巧合。
 *   4. 不缓存：`sys_config` 小表命中即毫秒级，缓存会造成「运营加了县但 APP 还看不到」的漂移。
 *   5. 纯查询，按《审计与日志规范》§3.2 不标 `@OperationLog`。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.modules.account.service.RegionOptionsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开地区枚举接口。
 */
@RestController
@RequestMapping("/api/v1/pub")
@RequiredArgsConstructor
public class PubRegionController {

    /** 地区枚举服务。 */
    private final RegionOptionsService regionOptionsService;

    /**
     * 取注册页可选的地区列表。
     *
     * <p>无副作用（只查 sys_config）。配置异常时返回内置兜底清单，不会 500——
     * 注册页拿到空数组就没法提交表单，那等于把注册功能一起拖下水。</p>
     *
     * @return 地区名数组，按配置顺序；永不为空
     */
    @GetMapping("/regions")
    public R<List<String>> list() {
        return R.ok(regionOptionsService.listOptions());
    }
}
