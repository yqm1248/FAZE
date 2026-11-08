/*
 * 职责：A-23 公告的管理端服务（列表 / 新建 / 详情 / 预估触达 / 触发发送）。
 * 调用方：modules/ops 的 AdminAnnouncementController。
 * 数据来源：faze.announcement + account 域的目标人群查询 + AnnouncementSendService（发送）。
 * 注意：
 *   1. **sent 不可回退**（裁决 26）：`canPush()` 只对 draft/scheduled/failed 为真，
 *      对 sent 再点发送返回 410013，不静默忽略。
 *   2. **预估触达在服务端算**（稿面「预计触达 1,284 人」）：口径只有一份，
 *      不让前端拿目标条件自己估——估错了运营会以为发给了更多人。
 *   3. `region` 目标**当前不可用**：`app_user` 没有 region 列（段 1 未建），
 *      预估返回 0 并在 targetDesc 里说明。不伪造数据。
 *   4. 发送本身在 AnnouncementSendService（D5），本类只做「能不能发」的判断与转发。
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
import com.faze.infra.seq.BizNoGenerator;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.ops.admin.bo.AnnouncementSaveBO;
import com.faze.modules.ops.admin.vo.AnnouncementDetailVO;
import com.faze.modules.ops.admin.vo.AnnouncementVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.Announcement;
import com.faze.modules.ops.enums.AnnouncementStatus;
import com.faze.modules.ops.mapper.AnnouncementMapper;
import com.faze.modules.ops.service.AnnouncementSendService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 公告管理服务。
 */
@Slf4j
@Service
public class AdminAnnouncementService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 预估/发送的最大人数（裁决 5：user_ids 上限 5000）。 */
    private static final int MAX_TARGET = 5000;

    /** 公告 Mapper。 */
    private final AnnouncementMapper announcementMapper;

    /** 单号生成。 */
    private final BizNoGenerator bizNoGenerator;

    /** 账户查询（目标人群）。 */
    private final AccountQueryService accountQueryService;

    /** 发送服务。 */
    private final AnnouncementSendService sendService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param announcementMapper 公告 Mapper
     * @param bizNoGenerator     单号生成
     * @param accountQueryService 账户查询
     * @param sendService        发送服务
     * @param jsonMapper         JSON
     */
    public AdminAnnouncementService(AnnouncementMapper announcementMapper, BizNoGenerator bizNoGenerator,
                                    AccountQueryService accountQueryService,
                                    AnnouncementSendService sendService, JsonMapper jsonMapper) {
        this.announcementMapper = announcementMapper;
        this.bizNoGenerator = bizNoGenerator;
        this.accountQueryService = accountQueryService;
        this.sendService = sendService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 分页查询公告。
     *
     * @param status   状态
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<AnnouncementVO> page(String status, Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        IPage<Announcement> page = announcementMapper.selectPage(new Page<>(num, size),
                Wrappers.<Announcement>lambdaQuery()
                        .eq(StringUtils.hasText(status), Announcement::getStatus, status)
                        .orderByDesc(Announcement::getId));
        List<AnnouncementVO> rows = new ArrayList<>(page.getRecords().size());
        for (Announcement entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, page.getTotal(), num, size);
    }

    /**
     * 新建公告（草稿或定时）。
     *
     * @param bo         入参
     * @param operatorId 创建管理员 id
     * @return 公告 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.ANNOUNCEMENT_CREATE,
            targetType = OpsAuditAction.TARGET_ANNOUNCEMENT)
    @Transactional(rollbackFor = Exception.class)
    public Long create(AnnouncementSaveBO bo, Long operatorId) {
        if (bo.getChannels() == null || !bo.getChannels().contains("inapp")) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "站内信是必选渠道");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Announcement entity = new Announcement();
        entity.setAnnouncementNo(bizNoGenerator.nextAnnouncementNo());
        entity.setTitle(bo.getTitle());
        entity.setContent(bo.getContent());
        entity.setTargetType(bo.getTargetType());
        entity.setTargetValue(bo.getTargetValue());
        entity.setChannels(jsonMapper.writeValueAsString(bo.getChannels()));
        entity.setStatus(bo.getScheduledAt() == null
                ? AnnouncementStatus.DRAFT.getCode() : AnnouncementStatus.SCHEDULED.getCode());
        entity.setScheduledAt(bo.getScheduledAt());
        entity.setReachCount(0);
        entity.setOpenCount(0);
        entity.setCreatedBy(operatorId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        announcementMapper.insert(entity);
        mark(entity.getId(), "{\"announcementNo\":" + quote(entity.getAnnouncementNo())
                + ",\"status\":" + quote(entity.getStatus()) + "}");
        return entity.getId();
    }

    /**
     * 公告详情（含预估触达）。
     *
     * @param id 公告 id
     * @return 详情
     */
    public AnnouncementDetailVO detail(Long id) {
        Announcement entity = requireExisting(id);
        AnnouncementDetailVO detail = new AnnouncementDetailVO();
        detail.setAnnouncement(toVO(entity));
        detail.setContent(entity.getContent());
        detail.setTargetDesc(targetDesc(entity));
        detail.setEstimatedReach(estimate(entity.getTargetType(), entity.getTargetValue()));
        detail.setPushable(AnnouncementStatus.of(entity.getStatus()).canPush());
        return detail;
    }

    /**
     * 预估触达人数。
     *
     * @param targetType  目标类型
     * @param targetValue 目标参数 JSON
     * @return 预估人数
     */
    public long estimate(String targetType, String targetValue) {
        if ("all".equals(targetType)) {
            return accountQueryService.listActiveUserIds(MAX_TARGET).size();
        }
        if ("role".equals(targetType)) {
            String role = jsonString(targetValue, "role");
            return role == null ? 0L : accountQueryService.listUserIdsByRole(role, MAX_TARGET).size();
        }
        if ("user_ids".equals(targetType)) {
            List<Long> ids = jsonLongArray(targetValue, "userIds");
            return ids.isEmpty() ? 0L : accountQueryService.listUserIdsByIds(ids, MAX_TARGET).size();
        }
        // region：app_user 无 region 列，当前不可用
        return 0L;
    }

    /**
     * 触发发送（立即发送 / 重试补发）。
     *
     * @param id         公告 id
     * @param operatorId 操作管理员 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = OpsAuditAction.ANNOUNCEMENT_PUSH,
            targetType = OpsAuditAction.TARGET_ANNOUNCEMENT)
    public void push(Long id, Long operatorId) {
        Announcement entity = requireExisting(id);
        if (!AnnouncementStatus.of(entity.getStatus()).canPush()) {
            throw new BusinessException(ErrorCode.OPS_ANNOUNCEMENT_SENT);
        }
        sendService.send(entity, operatorId);
        mark(id, "{\"status\":\"sent\"}");
    }

    /**
     * 按 id 取公告，不存在抛 401004。
     *
     * @param id 公告 id
     * @return 公告
     */
    private Announcement requireExisting(Long id) {
        Announcement entity = announcementMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "公告不存在: " + id);
        }
        return entity;
    }

    /**
     * 目标人群描述。
     *
     * @param entity 公告
     * @return 描述
     */
    private String targetDesc(Announcement entity) {
        return switch (entity.getTargetType() == null ? "" : entity.getTargetType()) {
            case "all" -> "全体用户";
            case "role" -> "按角色：" + jsonString(entity.getTargetValue(), "role");
            case "region" -> "按地区（当前不可用：app_user 未建 region 列）";
            case "user_ids" -> "指定用户 " + jsonLongArray(entity.getTargetValue(), "userIds").size() + " 人";
            default -> entity.getTargetType();
        };
    }

    /**
     * 实体转 VO（含打开率）。
     *
     * @param entity 公告
     * @return VO
     */
    private AnnouncementVO toVO(Announcement entity) {
        AnnouncementVO vo = new AnnouncementVO();
        vo.setId(entity.getId());
        vo.setAnnouncementNo(entity.getAnnouncementNo());
        vo.setTitle(entity.getTitle());
        vo.setTargetType(entity.getTargetType());
        vo.setChannels(entity.getChannels());
        vo.setStatus(entity.getStatus());
        AnnouncementStatus status = AnnouncementStatus.ofOrNull(entity.getStatus());
        vo.setStatusLabel(status == null ? entity.getStatus() : status.getLabel());
        vo.setScheduledAt(entity.getScheduledAt());
        vo.setSentAt(entity.getSentAt());
        vo.setReachCount(entity.getReachCount());
        vo.setOpenCount(entity.getOpenCount());
        int reach = entity.getReachCount() == null ? 0 : entity.getReachCount();
        int open = entity.getOpenCount() == null ? 0 : entity.getOpenCount();
        vo.setOpenRate(reach == 0 ? "—"
                : BigDecimal.valueOf(open * 100.0 / reach).setScale(1, RoundingMode.HALF_UP) + "%");
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 从 JSON 里取字符串字段。
     *
     * @param json JSON 文本
     * @param key  字段名
     * @return 值；解析失败或字段不存在返回 null
     */
    private String jsonString(String json, String key) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(json);
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? null : value.asString();
        } catch (Exception e) {
            log.warn("公告目标 JSON 解析失败 json={}", json, e);
            return null;
        }
    }

    /**
     * 从 JSON 里取 long 数组。
     *
     * @param json JSON 文本
     * @param key  字段名
     * @return 数组；解析失败返回空
     */
    private List<Long> jsonLongArray(String json, String key) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = jsonMapper.readTree(json).get(key);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<Long> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                out.add(item.asLong());
            }
            return out;
        } catch (Exception e) {
            log.warn("公告目标 JSON 解析失败 json={}", json, e);
            return List.of();
        }
    }

    /**
     * 写审计明细。
     *
     * @param targetId 公告 id
     * @param detail   明细
     */
    private void mark(Long targetId, String detail) {
        AuditContext.mark(null, null, null, String.valueOf(targetId), detail);
    }

    /**
     * JSON 字符串字面量。
     *
     * @param value 值
     * @return 带引号字符串
     */
    private String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
