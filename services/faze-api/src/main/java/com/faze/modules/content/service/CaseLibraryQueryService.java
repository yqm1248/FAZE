/*
 * 职责：App 案例库只读查询接口（04-case 列表 / 19-case-detail 详情）。
 * 调用方：modules/content/controller/AppCaseLibraryController。
 * 数据来源：case_library + case_library_translation（只读）。
 * 注意：
 *   1. **只返回 `status='published'`**：草稿与已下架都不该出现在 C 端（下架后详情返回 409002，
 *      页面走「这个案例已经下架了」空态）。
 *   2. 本接口是**跨域可调用的查询入口**（搜索服务也用它取案例域数据），所以不带写能力。
 */
package com.faze.modules.content.service;

import com.faze.common.pojo.TargetBrief;
import com.faze.modules.content.vo.CaseDetailVO;
import com.faze.modules.content.vo.CaseListPageVO;
import java.util.Collection;
import java.util.Map;

/**
 * App 案例库只读查询。
 */
public interface CaseLibraryQueryService {

    /**
     * 按大类分页取已上线案例（游标分页）。
     *
     * @param type   大类 code（civil / administrative / criminal）；空表示全部
     * @param cursor 上一页最后一条 id；空表示第一页
     * @param limit  每页条数（服务端夹到 1~50）
     * @return 分页结果
     */
    CaseListPageVO listByType(String type, Long cursor, int limit);

    /**
     * 取已上线案例详情（按语言取正文，缺译回退中文）。
     *
     * @param id     案例主键
     * @param lang   语言（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）
     * @param userId 当前登录用户（用于回填「是否已收藏」；可空）
     * @return 详情
     * @throws com.faze.common.exception.BusinessException 409002 案例不存在或已下架
     */
    CaseDetailVO detail(Long id, String lang, Long userId);

    /**
     * 按主键批量取案例简要信息（E3 收藏列表跨域组装用）。
     *
     * <p>只返回已上线且未软删的案例；标题按请求语言取译文，缺译回退简体
     * （与详情页降级口径一致）。描述取 `summary`（案情简介）。</p>
     *
     * @param caseIds 案例主键集合（null / 空集合返回空 Map）
     * @param lang    请求语言（空按 zh-Hans）
     * @return caseId → 简要信息
     */
    Map<Long, TargetBrief> mapBriefsByIds(Collection<Long> caseIds, String lang);
}
