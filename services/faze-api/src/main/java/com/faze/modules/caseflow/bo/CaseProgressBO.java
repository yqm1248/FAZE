/*
 * 职责：律师推进案件进度的请求体（`POST /app/cases/{id}/progress`）。
 * 调用方：modules/caseflow/controller/AppCaseController → CaseService。
 * 数据来源：App 54-case-progress 页。
 * 注意：
 *   1. **客户端只发意图**（节点编码），不发目标状态；状态迁移由服务端 CaseStateMachine 判定。
 *   2. `nodeCode` 必须是 case_progress 的 8 个节点之一；「结案」节点会顺带触发
 *      in_progress → closed（由 Service 决定，不由客户端传状态）。
 *   3. 登记法院案号时（nodeCode=accepted）`courtCaseNo` 必填，由 Service 校验。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 案件进度推进入参。
 */
@Data
public class CaseProgressBO {

    /** 节点编码：submitted / review / accepted / supplement / hearing / judgment / effective / closed。 */
    @NotBlank(message = "节点编码不能为空")
    @Size(max = 30, message = "节点编码过长")
    private String nodeCode;

    /** 节点标题（展示用）；不传由服务端按 nodeCode 取默认文案。 */
    @Size(max = 100, message = "节点标题不能超过 100 字")
    private String title;

    /** 节点说明。 */
    @Size(max = 2000, message = "节点说明不能超过 2000 字")
    private String description;

    /** 法院案号（nodeCode=accepted 时必填）。 */
    @Size(max = 64, message = "法院案号不能超过 64 字")
    private String courtCaseNo;
}
