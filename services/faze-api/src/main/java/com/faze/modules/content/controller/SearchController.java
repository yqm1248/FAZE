/*
 * 职责：App 全局搜索的 HTTP 入口（40-search，第 5 段 G3）。
 * 调用方：apps/mobile-app 的 `features/content/pages/SearchPage.tsx`。
 * 数据来源：SearchService（跨四域）。
 * 注意：
 *   1. **响应必须分域**（lawyers / articles / cases / documents 四个对象），
 *      不许拼成一个大数组——5-A8 的失败判据就是这个。
 *   2. `type` 缺省 = 综合（四域各前 3 条 + 总数）；`type=lawyer|article|case|document` = 单域。
 *   3. 「搜索历史」与「大家都在搜」**不走服务端**：历史存本地 AsyncStorage，
 *      热门词 V1 用前端固定列表（工作流 G3 第 7 条：搜索历史不上服务端）。
 */
package com.faze.modules.content.controller;

import com.faze.common.R;
import com.faze.modules.content.service.SearchService;
import com.faze.modules.content.vo.SearchResponseVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 全局搜索接口。
 */
@RestController
@RequestMapping("/api/v1/app/search")
public class SearchController {

    private final SearchService searchService;

    /**
     * 构造控制器。
     *
     * @param searchService 搜索业务
     */
    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 全局搜索（分域返回）。
     *
     * @param kw   关键词
     * @param type 单域类型；空表示综合
     * @return 分域结果
     */
    @GetMapping
    public R<SearchResponseVO> search(@RequestParam(required = false) String kw,
                                      @RequestParam(required = false) String type) {
        return R.ok(searchService.search(kw, type));
    }
}
