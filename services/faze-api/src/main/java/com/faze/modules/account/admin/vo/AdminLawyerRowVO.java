/*
 * 职责：A-06 律师列表的行出参。
 * 调用方：modules/account/admin/service/AdminLawyerService、AdminLawyerController。
 * 数据来源：faze.lawyer。
 * 注意：
 *   1. `licenseNoMasked` 只给掩码（前 4 + **** + 后 4）；完整证号不进列表。
 *   2. `specialties` 是解析后的字符串数组（库里是 JSON）。
 *   3. `rating` 用 BigDecimal 输出为 JSON 数字（0.0~5.0，保留一位小数），
 *      不要转字符串：前端星级组件按数字处理。
 *   4. `onlineStatus` 契约未规定枚举口径，**原样返回库内值** online / busy / offline，
 *      不在本段发明大写映射（避免与前端页面口径打架）。
 */
package com.faze.modules.account.admin.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师列表行。
 */
@Data
public class AdminLawyerRowVO {

    /** 律师主键。 */
    private Long id;

    /** 关联用户主键。 */
    private Long userId;

    /** 执业姓名。 */
    private String realName;

    /** 执业证号掩码。 */
    private String licenseNoMasked;

    /** 执业律所。 */
    private String lawFirm;

    /** 执业地区。 */
    private String region;

    /** 执业年限。 */
    private Integer practiceYears;

    /** 擅长领域。 */
    private List<String> specialties;

    /** 评分 0.0~5.0。 */
    private BigDecimal rating;

    /** 评价数。 */
    private Integer reviewCount;

    /** 累计接单数。 */
    private Integer orderCount;

    /** 审核状态：PENDING / APPROVED / REJECTED。 */
    private String auditStatus;

    /** 在线状态：online / busy / offline。 */
    private String onlineStatus;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
