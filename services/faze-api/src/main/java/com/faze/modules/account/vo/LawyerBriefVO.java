/*
 * 职责：律师的跨域展示投影（E3 收藏/关注列表用；不含 L3/L4 敏感字段）。
 * 调用方：modules/trade/service/SocialQueryService（收藏列表 / 关注列表组装）。
 * 数据来源：faze.lawyer（经 LawyerQueryService#mapBriefsByIds 返回）。
 * 注意：
 *   1. **不放执业证号、提现卡号、历史余额缓存**：跨域投影只装展示需要的字段，
 *      多一个字段就多一个泄露面（与 LawyerCardVO 同一原则）。
 *   2. `userId` 是律师关联的 C 端账号，仅用于跨域查「最近登录时间」；
 *      不直接回传给 C 端（关注列表不出 userId）。
 *   3. `specialties` 是解析后的数组：JSON 解析在 account 域内完成，调用方不碰原始 JSON。
 */
package com.faze.modules.account.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 律师跨域展示投影。
 *
 * @param lawyerId      律师主键
 * @param userId        关联的 C 端用户主键（查最近登录时间用）
 * @param realName      执业姓名
 * @param lawFirm       执业律所
 * @param region        执业地区
 * @param practiceYears 执业年限
 * @param specialties   擅长领域（已解析）
 * @param onlineStatus  在线状态：online / busy / offline
 * @param rating        评分
 * @param avatarUrl     头像地址
 */
public record LawyerBriefVO(Long lawyerId,
                            Long userId,
                            String realName,
                            String lawFirm,
                            String region,
                            Integer practiceYears,
                            List<String> specialties,
                            String onlineStatus,
                            BigDecimal rating,
                            String avatarUrl) {
}
