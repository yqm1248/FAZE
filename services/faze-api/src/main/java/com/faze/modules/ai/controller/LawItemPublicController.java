/*
 * 职责：`GET /api/v1/pub/law-items` 公开法条检索接口（B2）——同时服务「未登录时的法条查询」
 *       与「AI 关闭后的降级检索」，内部复用 LawSearchService 的四路径内核。
 * 调用方：APP 法条检索页、AI 降级检索界面（3-A7）、游客；无鉴权（`pub` 前缀）。
 * 数据来源：LawSearchService（→ law_item）。
 * 注意：
 *   1. **`pub` 前缀 + 无鉴权是设计如此，不要加鉴权**：法条是公开信息，
 *      既要给游客用，也要在 AI 关闭时承担降级检索（工作流 B2 第 1 条）。
 *      这是**面向公网的无鉴权读接口**，所以必须限流（见 LawSearchRateLimiter，每 IP 60 次/分钟）。
 *   2. 参数：`kw` 1~100 字（可为空；1 个字允许，因为 B1 有 LIKE 兜底）/ `lawCode` /
 *      `pageNum` 默认 1 / `pageSize` 默认 20、最大 50（C 端 list 分页，接口规范 §5.2）。
 *      超上限 **clamp 不报错**（回 401001 会让用户莫名其妙）；非法 kw 长度才 401001。
 *   3. 出参 `R<PageResult<LawItemBrief>>`；**不返回 score/hitReason**（B2 第 3 条）。
 *   4. Controller 只做收参/校验/调 Service/包 R：不注入 Mapper、不写业务分支、不开事务
 *      （后端代码规范 §2.2/§3.2）。
 *   5. `kw` 的 Bean Validation 在这里用不上：空串要当「未传」处理，`@Size(min=1)` 会把
 *      `?kw=` 判成 401001；所以手写规整（blank→null）+ 长度校验。
 */
package com.faze.modules.ai.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.common.util.RequestUtils;
import com.faze.modules.ai.dto.LawItemBrief;
import com.faze.modules.ai.service.LawSearchService;
import com.faze.modules.ai.support.LawSearchRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开法条检索接口。
 */
@RestController
@RequestMapping("/api/v1/pub/law-items")
@RequiredArgsConstructor
public class LawItemPublicController {

    /** kw 最大长度（B2 第 2 条：1~100）。 */
    public static final int MAX_KW_LENGTH = 100;

    private final LawSearchService lawSearchService;
    private final LawSearchRateLimiter lawSearchRateLimiter;

    /**
     * 分页检索法条。
     *
     * <p>先限流再校验：非法参数同样消耗一次配额，防止用错误参数探测接口。</p>
     *
     * @param kw       关键词（可空；1~100 字）
     * @param lawCode  法律简称码（可空）
     * @param pageNum  页码（可空，默认 1）
     * @param pageSize 每页条数（可空，默认 20，最大 50）
     * @param request  当前请求（取客户端 IP 用于限流）
     * @return 分页结果
     * @throws BusinessException 401007 触发限流；401001 kw 超长
     */
    @GetMapping
    public R<PageResult<LawItemBrief>> list(@RequestParam(required = false) String kw,
                                            @RequestParam(required = false) String lawCode,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize,
                                            HttpServletRequest request) {
        lawSearchRateLimiter.check(RequestUtils.clientIp(request));
        String keyword = normalizeKw(kw);
        return R.ok(lawSearchService.searchPage(keyword, lawCode, pageNum, pageSize));
    }

    /**
     * 规整并校验关键词：空串/纯空白视为未传；超 100 字 401001。
     *
     * @param kw 原始关键词
     * @return 规整后的关键词；未传返回 null
     * @throws BusinessException 401001 超过 100 字
     */
    private String normalizeKw(String kw) {
        if (!StringUtils.hasText(kw)) {
            return null;
        }
        String value = kw.trim();
        if (value.length() > MAX_KW_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "kw: 长度不能超过 " + MAX_KW_LENGTH);
        }
        return value;
    }
}
