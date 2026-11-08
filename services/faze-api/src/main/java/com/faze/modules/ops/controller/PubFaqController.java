/*
 * 职责：C 端常见问题接口（APP 55 客服中心 / 56 常见问题）。
 * 调用方：APP（未登录也能看，所以放在 `pub` 前缀下）。
 * 数据来源：FaqService → faze.faq。
 * 注意：
 *   1. `pub` 前缀 = 免登录（SecurityConfig 里 permitAll），FAQ 不需要登录。
 *   2. 只读接口，不标审计。
 *   3. 不返回 `status` / `lang`：调用方只该看到「当前语种的启用项」。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.modules.ops.service.FaqService;
import com.faze.modules.ops.vo.FaqVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端常见问题接口。
 */
@RestController
@RequestMapping("/api/v1/pub/faq")
@RequiredArgsConstructor
public class PubFaqController {

    /** FAQ 服务。 */
    private final FaqService faqService;

    /**
     * 按分类查常见问题。
     *
     * @param category 分类（account / pay / lawyer / case）；空则全部
     * @return 列表
     */
    @GetMapping
    public R<List<FaqVO>> list(@RequestParam(required = false) String category) {
        return R.ok(faqService.list(category));
    }
}
