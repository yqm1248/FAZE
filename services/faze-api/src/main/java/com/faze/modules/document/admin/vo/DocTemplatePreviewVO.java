/*
 * 职责：A-18 预览接口的返回（段 4 B1）。
 * 调用方：AdminDocTemplateController#preview。
 * 数据来源：FileRecordService（现签 60 秒地址）。
 * 注意：`expiresIn` 固定 60，前端要在过期前渲染完；过期后重新点「预览」再签。
 */
package com.faze.modules.document.admin.vo;

import lombok.Data;

/**
 * 模板预览图。
 */
@Data
public class DocTemplatePreviewVO {

    /** 预览图短时地址。 */
    private String url;

    /** 有效期（秒），固定 60。 */
    private long expiresIn;
}
