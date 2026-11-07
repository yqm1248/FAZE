/*
 * 职责：把「文书引用的模板行」解析成「当前可用的模板行」（段 4 D 组顺带修的真缺陷）。
 * 调用方：DocGenerateHandler（生成/重新生成）、AppDocumentService（保存草稿、新建）。
 * 数据来源：doc_template。
 * 注意：
 *   1. **为什么需要它**：改模板按 4-A4 的设计是「插新版本行 + 旧行置 inactive」。
 *      于是任何引用了旧版本行的文书（`document.template_id` 指向已 inactive 的那行）
 *      在「重新生成」或「改表单再保存」时会直接 406001「模板不存在或已停用」——
 *      用户看到的是「这个模板没了」，而实际上模板只是升了一版。2026-11-07 跑 4-A4 时实测到这个失败。
 *   2. 解析规则：模板行 active → 直接用；inactive/缺失 → 按 **code + lang** 找同编码的最新 active 行。
 *      都找不到才抛 406001（那才是「模板真的停用了」）。
 *   3. **只给「用户重新生成 / 继续编辑」用**。历史文书的「原样复现」不走这里——
 *      那是按 `(template_id, template_version)` 精确取行，见 DocTemplateAdminService 的预览与 A-18。
 *   4. 本类不写库、不开事务，纯查询。
 */
package com.faze.modules.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.mapper.DocTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模板行解析器（旧版本行 → 当前可用行）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocTemplateResolver {

    private static final String STATUS_ACTIVE = "active";

    private final DocTemplateMapper docTemplateMapper;

    /**
     * 解析出当前可用的模板行。
     *
     * @param templateId 文书引用的模板主键（可能是旧版本行，也可能已被停用）
     * @return 可用的模板行（active）
     * @throws BusinessException 406001（该编码下没有任何 active 版本）
     */
    public DocTemplate activeOrDefault(Long templateId) {
        if (templateId == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND);
        }
        DocTemplate row = docTemplateMapper.selectById(templateId);
        if (row == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND);
        }
        if (STATUS_ACTIVE.equals(row.getStatus())) {
            return row;
        }
        DocTemplate latest = docTemplateMapper.selectOne(Wrappers.<DocTemplate>lambdaQuery()
                .eq(DocTemplate::getCode, row.getCode())
                .eq(DocTemplate::getLang, row.getLang())
                .eq(DocTemplate::getStatus, STATUS_ACTIVE)
                .orderByDesc(DocTemplate::getVersion)
                .last("LIMIT 1"));
        if (latest == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND);
        }
        log.info("模板行已升版：templateId={} version={} → activeId={} version={}",
                row.getId(), row.getVersion(), latest.getId(), latest.getVersion());
        return latest;
    }
}
