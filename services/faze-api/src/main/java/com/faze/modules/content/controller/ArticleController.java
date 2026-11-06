/*
 * 职责：C 端文章 HTTP 入口（G8.1：列表与详情，四语言 + 降级）。
 * 调用方：APP 首页「每日学法」区块、文章列表页（12-articles）、文章详情页（13-article）。
 * 数据来源：ArticleService。
 * 注意：
 *   1. **同时挂 `/pub` 与 `/app` 两个前缀**：首页「每日学法」游客也要能看（工作流 G8.2 第 1 条），
 *      而登录用户走 `/app` 也应当可用。只挂 `/app` 会让游客首页 401；只挂 `/pub`
 *      又与工作流 §五 的接口清单（`GET /app/articles`）不符。两个前缀指向同一个实现。
 *   2. **语言来源**：`lang` 查询参数优先，其次 `Accept-Language` 请求头（客户端已统一发送，
 *      见 `shared/api/client.ts` 的 buildHeaders）。头里是 `ug-Arab-CN,zh;q=0.9` 这种形式，
 *      只取第一段。
 *   3. 不需要登录断言：文章是公开内容（`/pub` 前缀本身放行，`/app` 前缀在 Security 里要求认证，
 *      但本类不依赖任何身份，`loginUser` 不注入）。
 *   4. 本类不注入 Mapper、不写业务（《后端代码规范》§2.1）。
 */
package com.faze.modules.content.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.content.service.ArticleService;
import com.faze.modules.content.vo.ArticleCardVO;
import com.faze.modules.content.vo.ArticleDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章接口（C 端）。
 */
@RestController
@RequestMapping({"/api/v1/pub/articles", "/api/v1/app/articles"})
@RequiredArgsConstructor
public class ArticleController {

    /** `Accept-Language` 头名。 */
    private static final String HEADER_LANG = "Accept-Language";

    private final ArticleService articleService;

    /**
     * 分页查文章列表。
     *
     * @param lang      语言（可空，回退 Accept-Language）
     * @param category  分类（可空）
     * @param pageNum   页码（可空）
     * @param pageSize  每页条数（可空）
     * @param acceptLang `Accept-Language` 请求头
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<ArticleCardVO>> list(@RequestParam(required = false) String lang,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) Integer pageNum,
                                             @RequestParam(required = false) Integer pageSize,
                                             @RequestHeader(value = HEADER_LANG, required = false)
                                             String acceptLang) {
        return R.ok(articleService.list(resolveLang(lang, acceptLang), category, pageNum, pageSize));
    }

    /**
     * 查文章详情。
     *
     * @param id         文章主键
     * @param lang       语言（可空，回退 Accept-Language）
     * @param acceptLang `Accept-Language` 请求头
     * @return 文章详情（正文为块数组）
     */
    @GetMapping("/{id}")
    public R<ArticleDetailVO> detail(@PathVariable Long id,
                                     @RequestParam(required = false) String lang,
                                     @RequestHeader(value = HEADER_LANG, required = false)
                                     String acceptLang) {
        return R.ok(articleService.detail(id, resolveLang(lang, acceptLang)));
    }

    /**
     * 解析请求语言：`lang` 参数优先，其次 `Accept-Language` 的第一段。
     *
     * @param lang       查询参数
     * @param acceptLang 请求头原值
     * @return 语言码；都没有返回 null（Service 按简体处理）
     */
    private String resolveLang(String lang, String acceptLang) {
        if (StringUtils.hasText(lang)) {
            return lang.trim();
        }
        if (!StringUtils.hasText(acceptLang)) {
            return null;
        }
        String first = acceptLang.split(",")[0].trim();
        // 去掉 q 权重（`zh-Hans;q=0.9`）
        int semicolon = first.indexOf(';');
        return semicolon > 0 ? first.substring(0, semicolon).trim() : first;
    }
}
