/*
 * 职责：文书只读查询实现。
 * 调用方：caseflow 的 DeliveryService、workbench 的 WorkbenchService。
 * 数据来源：document 表（MyBatis-Plus LambdaQueryWrapper，单表不需要 XML）。
 * 注意：所有查询都带 `deleted_at IS NULL`，调用方不需要再判软删。
 */
package com.faze.modules.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.mapper.DocTemplateMapper;
import com.faze.modules.document.mapper.DocumentMapper;
import com.faze.modules.document.service.DocumentQueryService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文书只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class DocumentQueryServiceImpl implements DocumentQueryService {

    private final DocumentMapper documentMapper;
    private final DocTemplateMapper docTemplateMapper;

    @Override
    public Optional<Document> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getId, id)
                .isNull(Document::getDeletedAt)));
    }

    @Override
    public List<Document> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .in(Document::getId, ids)
                .isNull(Document::getDeletedAt));
    }

    @Override
    public List<Document> listByLawyerId(Long lawyerId) {
        if (lawyerId == null) {
            return List.of();
        }
        return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .eq(Document::getLawyerId, lawyerId)
                .isNull(Document::getDeletedAt)
                .orderByDesc(Document::getUpdatedAt));
    }

    @Override
    public List<Document> listByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .eq(Document::getUserId, userId)
                .isNull(Document::getDeletedAt)
                .orderByDesc(Document::getUpdatedAt));
    }

    /**
     * 按名称关键词搜已启用模板（全局搜索的「文书」域）。
     *
     * <p>只查 `status='active'`：段 4 的模板用三态 `status` 替代 `is_enabled`，
     * 停用的模板不该被搜出来（点了也生成不了）。</p>
     *
     * @param keyword 关键词
     * @param limit   最多条数（夹到 1~20）
     * @return 模板列表；关键词为空返回空列表
     */
    @Override
    public List<DocTemplate> searchActiveTemplates(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int size = Math.min(Math.max(limit, 1), 20);
        return docTemplateMapper.selectList(Wrappers.<DocTemplate>lambdaQuery()
                .eq(DocTemplate::getStatus, "active")
                .like(DocTemplate::getName, keyword.trim())
                .orderByAsc(DocTemplate::getSortOrder)
                .orderByAsc(DocTemplate::getId)
                // size 是服务端夹过的 int，不是用户输入，拼接安全（与项目既有做法一致）
                .last("LIMIT " + size));
    }
}
