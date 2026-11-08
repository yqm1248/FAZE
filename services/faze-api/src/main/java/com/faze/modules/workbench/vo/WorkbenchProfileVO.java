/*
 * 职责：律师「资料编辑」页的回显数据（`GET /app/workbench/profile`，段 5 D5）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 68-lawyer-profile 页。
 * 数据来源：lawyer 表。
 * 注意：
 *   1. `realName` / `licenseNumber` **只读**：页面要显示（带锁），但入参里没有它们，
 *      改不了（D5 第 3 条要求「构造请求也改不了」，最彻底的做法是压根不收）。
 *   2. `auditStatus` 供底部「资质审核状态」行使用；具体通过日期在入驻审核状态页看，
 *      本 VO 不复制一份（避免两处日期不一致）。
 *   3. `specialties` 出参是数组：库里存的是 JSON 数组字符串，解析失败时给空数组，不让脏数据打 500。
 */
package com.faze.modules.workbench.vo;

import java.util.List;
import lombok.Data;

/**
 * 律师资料回显。
 */
@Data
public class WorkbenchProfileVO {

    /** 姓名（只读）。 */
    private String realName;

    /** 执业证号（只读）。 */
    private String licenseNumber;

    /** 头像 URL。 */
    private String avatarUrl;

    /** 个人简介。 */
    private String bio;

    /** 专长标签。 */
    private List<String> specialties;

    /** 律所名称。 */
    private String lawFirm;

    /** 服务地区（只读）。 */
    private String region;

    /** 资质审核状态：pending / approved / rejected。 */
    private String auditStatus;
}
