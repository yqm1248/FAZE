/*
 * 职责：document 域的只读查询出口，供其它业务域跨域读取文书（段 5 送达需要）。
 * 调用方：caseflow 的 DeliveryService（校验文书有 PDF、取标题与归属）。
 * 数据来源：document 表。
 * 注意：
 *   1. **只读**：本接口不提供任何写方法。跨域写一律回各自域，不在这里开口子（后端代码规范 §2.3）。
 *   2. 返回实体而不是再包一层 DTO：与 `LawyerQueryService.findById` 的既有做法一致，
 *      避免为几个字段造一堆平行 DTO。
 *   3. 调用方必须自己判 `deleted_at`（本接口的查询已带 `IS NULL`，返回值天然是未删除的）。
 */
package com.faze.modules.document.service;

import com.faze.modules.document.entity.Document;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 文书只读查询。
 */
public interface DocumentQueryService {

    /**
     * 按主键取未删除的文书。
     *
     * @param id 文书主键
     * @return 文书；不存在或已删除时为空
     */
    Optional<Document> findById(Long id);

    /**
     * 按主键批量取未删除的文书（列表页避免 N+1）。
     *
     * @param ids 文书主键集合（空集合返回空列表）
     * @return 文书列表
     */
    List<Document> findByIds(Collection<Long> ids);

    /**
     * 取某律师代写的文书（律师侧列表，段 5 D2）。
     *
     * @param lawyerId 律师主键
     * @return 文书列表（按更新时间倒序）
     */
    List<Document> listByLawyerId(Long lawyerId);

    /**
     * 取某个用户名下的文书（PC 送达选文书用，段 5 C1）。
     *
     * @param userId 用户主键
     * @return 文书列表（按更新时间倒序）
     */
    List<Document> listByUserId(Long userId);
}
