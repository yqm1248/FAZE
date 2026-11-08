/*
 * 职责：A-24 通知模板管理（列表 / 新建 / 修改 / 软停用 / 测试渲染）。
 * 调用方：modules/ops 的 AdminNotifyTemplateController。
 * 数据来源：faze.notify_template。
 * 注意：
 *   1. **变量语法只有一种**：`{{camelCase}}`（i18n 规范禁位置参数）。
 *      保存时解析 title 与 content 的变量集合，**两者必须完全一致**——
 *      标题有 `{{a}}` 正文没有，说明模板写错了，前端渲染时会出现「标题里有个变量没值」。
 *   2. **删除 = 软停用**（status='disabled'）：已发出的历史通知引用 template_code，
 *      物理删会让「这条通知是用哪个模板发的」查不到。
 *   3. **测试发送只做「渲染预览」，不写 notification**：
 *      操作者是 RuoYi 管理员（faze_admin.sys_user），**不在 faze.app_user 里**，
 *      notification.user_id 无处可写。这是与工作流 D2「发给自己站内信」的一处偏差，
 *      已记 §十一；等段 8 打通「管理员 → 站内信」的身份映射后再补。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.NotifyTemplateSaveBO;
import com.faze.modules.ops.admin.vo.NotifyTemplateVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.NotifyTemplate;
import com.faze.modules.ops.mapper.NotifyTemplateMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知模板服务。
 */
@Slf4j
@Service
public class AdminNotifyTemplateService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 变量正则：{{camelCase}}。 */
    private static final Pattern VAR = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)\\}\\}");

    /** 预览截断长度。 */
    private static final int PREVIEW_LEN = 60;

    /** 默认语种。 */
    private static final String DEFAULT_LANG = "zh-Hans";

    /** 默认渠道。 */
    private static final String DEFAULT_CHANNEL = "inapp";

    /** 启用状态。 */
    private static final String STATUS_ACTIVE = "active";

    /** 停用状态。 */
    private static final String STATUS_DISABLED = "disabled";

    /** 模板 Mapper。 */
    private final NotifyTemplateMapper templateMapper;

    /**
     * 构造服务。
     *
     * @param templateMapper 模板 Mapper
     */
    public AdminNotifyTemplateService(NotifyTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    /**
     * 分页查询模板。
     *
     * @param channel  渠道
     * @param status   状态
     * @param kw       关键词（编码或标题）
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<NotifyTemplateVO> page(String channel, String status, String kw,
                                             Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        IPage<NotifyTemplate> page = templateMapper.selectPage(new Page<>(num, size),
                Wrappers.<NotifyTemplate>lambdaQuery()
                        .eq(StringUtils.hasText(channel), NotifyTemplate::getChannel, channel)
                        .eq(StringUtils.hasText(status), NotifyTemplate::getStatus, status)
                        .and(StringUtils.hasText(kw), w -> w
                                .like(NotifyTemplate::getTemplateCode, kw)
                                .or().like(NotifyTemplate::getTitle, kw))
                        .orderByDesc(NotifyTemplate::getId));
        List<NotifyTemplateVO> rows = new ArrayList<>(page.getRecords().size());
        for (NotifyTemplate entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, page.getTotal(), num, size);
    }

    /**
     * 新建模板。
     *
     * @param bo 入参
     * @return 新模板 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "NOTIFY_TEMPLATE_CREATE",
            targetType = OpsAuditAction.TARGET_NOTIFY_TEMPLATE)
    @Transactional(rollbackFor = Exception.class)
    public Long create(NotifyTemplateSaveBO bo) {
        validateVariables(bo.getTitle(), bo.getContent());
        String lang = StringUtils.hasText(bo.getLang()) ? bo.getLang() : DEFAULT_LANG;
        Long exists = templateMapper.selectCount(Wrappers.<NotifyTemplate>lambdaQuery()
                .eq(NotifyTemplate::getTemplateCode, bo.getTemplateCode())
                .eq(NotifyTemplate::getLang, lang));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NotifyTemplate entity = new NotifyTemplate();
        entity.setTemplateCode(bo.getTemplateCode());
        entity.setLang(lang);
        entity.setTitle(bo.getTitle());
        entity.setContent(bo.getContent());
        entity.setChannel(StringUtils.hasText(bo.getChannel()) ? bo.getChannel() : DEFAULT_CHANNEL);
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            templateMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
        }
        mark(entity.getId(), "{\"after\":" + quote(bo.getTemplateCode()) + "}");
        return entity.getId();
    }

    /**
     * 修改模板。
     *
     * @param id 模板 id
     * @param bo 入参
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "NOTIFY_TEMPLATE_UPDATE",
            targetType = OpsAuditAction.TARGET_NOTIFY_TEMPLATE)
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, NotifyTemplateSaveBO bo) {
        NotifyTemplate old = requireExisting(id);
        validateVariables(bo.getTitle(), bo.getContent());
        String lang = StringUtils.hasText(bo.getLang()) ? bo.getLang() : DEFAULT_LANG;
        if (!old.getTemplateCode().equals(bo.getTemplateCode()) || !old.getLang().equals(lang)) {
            Long exists = templateMapper.selectCount(Wrappers.<NotifyTemplate>lambdaQuery()
                    .eq(NotifyTemplate::getTemplateCode, bo.getTemplateCode())
                    .eq(NotifyTemplate::getLang, lang)
                    .ne(NotifyTemplate::getId, id));
            if (exists != null && exists > 0) {
                throw new BusinessException(ErrorCode.OPS_SENSITIVE_WORD_EXISTS);
            }
        }

        NotifyTemplate entity = new NotifyTemplate();
        entity.setId(id);
        entity.setTemplateCode(bo.getTemplateCode());
        entity.setLang(lang);
        entity.setTitle(bo.getTitle());
        entity.setContent(bo.getContent());
        entity.setChannel(StringUtils.hasText(bo.getChannel()) ? bo.getChannel() : DEFAULT_CHANNEL);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        templateMapper.updateById(entity);
        mark(id, "{\"before\":" + quote(old.getTemplateCode()) + ",\"after\":" + quote(bo.getTemplateCode()) + "}");
    }

    /**
     * 软停用模板。
     *
     * @param id 模板 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "NOTIFY_TEMPLATE_DELETE",
            targetType = OpsAuditAction.TARGET_NOTIFY_TEMPLATE)
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        NotifyTemplate old = requireExisting(id);
        NotifyTemplate entity = new NotifyTemplate();
        entity.setId(id);
        entity.setStatus(STATUS_DISABLED);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        templateMapper.updateById(entity);
        mark(id, "{\"before\":" + quote(old.getStatus()) + ",\"after\":" + quote(STATUS_DISABLED) + "}");
    }

    /**
     * 测试渲染（不写 notification，见类注释第 3 条）。
     *
     * @param id 模板 id
     * @return 渲染后的标题与正文
     */
    public String renderPreview(Long id) {
        NotifyTemplate template = requireExisting(id);
        String text = template.getTitle() + "\n" + template.getContent();
        return text.replaceAll("\\{\\{[a-zA-Z][a-zA-Z0-9]*\\}\\}", "［示例值］");
    }

    /**
     * 校验标题与正文的变量集合一致。
     *
     * @param title   标题
     * @param content 正文
     */
    private void validateVariables(String title, String content) {
        Set<String> titleVars = extract(title);
        Set<String> contentVars = extract(content);
        if (!titleVars.equals(contentVars)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                    "标题与正文的变量不一致：标题 " + titleVars + "，正文 " + contentVars);
        }
    }

    /**
     * 提取变量名。
     *
     * @param text 文本
     * @return 变量集合（去重、保序）
     */
    private Set<String> extract(String text) {
        Set<String> vars = new LinkedHashSet<>();
        if (text == null) {
            return vars;
        }
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * 按 id 取模板，不存在抛 410004。
     *
     * @param id 模板 id
     * @return 模板
     */
    private NotifyTemplate requireExisting(Long id) {
        NotifyTemplate entity = templateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "模板不存在: " + id);
        }
        return entity;
    }

    /**
     * 实体转 VO。
     *
     * @param entity 模板
     * @return VO
     */
    private NotifyTemplateVO toVO(NotifyTemplate entity) {
        NotifyTemplateVO vo = new NotifyTemplateVO();
        vo.setId(entity.getId());
        vo.setTemplateCode(entity.getTemplateCode());
        vo.setLang(entity.getLang());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        String content = entity.getContent() == null ? "" : entity.getContent();
        vo.setContentPreview(content.length() <= PREVIEW_LEN ? content : content.substring(0, PREVIEW_LEN));
        vo.setChannel(entity.getChannel());
        vo.setStatus(entity.getStatus());
        vo.setVariables(new ArrayList<>(extract(entity.getContent())));
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    /**
     * 写审计明细。
     *
     * @param targetId 模板 id
     * @param detail   明细
     */
    private void mark(Long targetId, String detail) {
        AuditContext.mark(null, null, null, String.valueOf(targetId), detail);
    }

    /**
     * JSON 字符串字面量（简单转义）。
     *
     * @param value 值
     * @return 带引号的 JSON 字符串
     */
    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
