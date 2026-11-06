/*
 * 职责：C 端律师浏览 HTTP 入口（G3.1 列表；G3.2 详情/评价/关注在后续提交扩展）。
 * 调用方：APP「找律师」页（02-lawyer.html）、「律师详情」页（14-lawyer-detail.html）。
 * 数据来源：LawyerBrowseService。
 * 注意：
 *   1. **列表与详情是公开接口**（`/pub` 前缀？这里用 `/app` 且放行匿名）：找律师是浏览行为，
 *      未登录用户也应该能看律师列表（否则首页的「找律师」入口对游客就是死的）。
 *      放行规则在 SecurityConfig 里，本类不含鉴权逻辑。
 *   2. 本类不注入 Mapper、不写业务、不开事务（后端规范 §2.1）。
 *   3. 金额一律整数分；`specialties` 出参是数组。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.bo.LawyerListQueryBO;
import com.faze.modules.account.service.LawyerBrowseService;
import com.faze.modules.account.vo.LawyerCardVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 律师浏览接口。
 */
@RestController
@RequestMapping("/api/v1/app/lawyers")
@RequiredArgsConstructor
public class LawyerBrowseController {

    private final LawyerBrowseService lawyerBrowseService;

    /**
     * 律师列表（分类 / 搜索 / 分页）。
     *
     * @param query 分页、分类与关键词
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<LawyerCardVO>> list(LawyerListQueryBO query) {
        return R.ok(lawyerBrowseService.list(query));
    }
}
