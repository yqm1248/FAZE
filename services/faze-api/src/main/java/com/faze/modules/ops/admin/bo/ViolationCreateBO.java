/*
 * 职责：管理端登记违规/处罚的入参。
 * 调用方：modules/ops 的 AdminViolationController（`POST /api/v1/admin/lawyers/{id}/violations`）。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. `level` 四级：warning 警告 / restrict 限流 / suspend 暂停接单 / ban 清退。
 *      本段**只写记录**，限流与暂停接单的「执行」由段 2/5 的业务逻辑读 user_violation 判定
 *      （工作流 B4 第 6 条：本段登记「段 2/5 需读违规状态」）。
 *   2. `endAt` 可空：null = 永久处罚（表定义如此）。工作流 §三 B3 写「end_at 必填」
 *      与表定义冲突，按表定义执行，记 §十一。
 *   3. `reason` 必填：处罚要能通知用户、要能在申诉时自证。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 违规登记入参。
 */
@Data
public class ViolationCreateBO {

    /** 违规类型（业务码，如 content_violation / fake_license）。 */
    @NotBlank(message = "违规类型不能为空")
    @Size(max = 30, message = "违规类型不能超过 30 字")
    private String violationType;

    /** 处罚级别。 */
    @NotBlank(message = "处罚级别不能为空")
    @Pattern(regexp = "warning|restrict|suspend|ban", message = "处罚级别取值非法")
    private String level;

    /** 处罚原因。 */
    @NotBlank(message = "处罚原因不能为空")
    @Size(max = 500, message = "处罚原因不能超过 500 字")
    private String reason;

    /** 失效时间（UTC）；空 = 永久。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endAt;
}
