/*
 * 职责：PC 案件进度纠错入参（`POST /admin/cases/{id}/progress`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：PC A-19 的「推进节点」按钮（纠错用途）。
 * 注意：
 *   1. 管理端可指定任意**合法**节点，但**必须填纠错原因**（≤500 字），否则 401001。
 *   2. 必须写审计，动作码 `CASE_PROGRESS_CORRECT`，detail 存 before/after。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PC 进度纠错入参。
 */
@Data
public class AdminProgressBO {

    /** 节点编码（8 个合法值之一）。 */
    @NotBlank(message = "节点编码不能为空")
    @Size(max = 30, message = "节点编码过长")
    private String nodeCode;

    /** 节点标题。 */
    @Size(max = 100, message = "节点标题不能超过 100 字")
    private String title;

    /** 节点说明。 */
    @Size(max = 2000, message = "节点说明不能超过 2000 字")
    private String description;

    /** 纠错原因（必填，写审计 detail）。 */
    @NotBlank(message = "纠错原因不能为空")
    @Size(max = 500, message = "纠错原因不能超过 500 字")
    private String reason;
}
