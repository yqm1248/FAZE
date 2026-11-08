/*
 * 职责：内容审核的统一入参——描述「要审哪条内容」。
 * 调用方：modules/ops 的 ContentReviewService.review(...)，以及投诉/反馈提交链路。
 * 数据来源：调用方（投诉/反馈 Service）从请求体与登录态组装。
 * 注意：
 *   1. **不继承任何 BO 基类、不带校验注解**：这是服务间传参，不是 HTTP 入参；
 *      格式校验在 Controller 的 BO 上做（避免同一规则写两遍）。
 *   2. `bizId` 在「先审后落库」的场景里可能为 null（内容还没入库，拿不到主键）：
 *      此时传 null，ContentReviewService 会把它当 0 处理并在落库后由调用方回填——
 *      本段接入的投诉/反馈是「先落库再审核」还是「先审再落库」见 C2 的实现；
 *      当前选择「先审再落库」，所以 block 时 content 根本不进业务表（A3 算做完第 1 条）。
 *   3. `lang` 允许为空：为空时引擎按「所有语种树都跑」处理（宁可多判）。
 */
package com.faze.modules.ops.dto;

import com.faze.modules.ops.enums.ContentBizType;

/**
 * 内容审核请求。
 *
 * @param bizType   业务类型
 * @param bizId     业务主键（内容尚未落库时可为 null）
 * @param bizUserId 内容作者 app_user.id（用于 A-20 按用户筛选与申诉归属校验）
 * @param content   待判定原文（**引擎不会修改它**）
 * @param lang      语种（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN，可为 null）
 */
public record ReviewRequest(ContentBizType bizType, Long bizId, Long bizUserId, String content, String lang) {
}
