/*
 * 职责：服务端错误码的**唯一**来源。业务代码禁止出现裸数字错误码。
 * 调用方：common/R（构造失败响应）、common/GlobalExceptionHandler（按分支取码）、
 *         security/RestAuthenticationEntryPoint（401 两码）、各业务 Service 抛 BusinessException。
 * 数据来源：《法泽FAZE_错误码规范_v1.0.md》§三 的完整错误码表（段 0 只登记本段用到的子集，
 *           段 1 起按同一张表继续补，**不许自行发明新码，也不许改已冻结码的含义**）。
 * 注意：
 *   1. httpStatus 必须与《错误码规范》§2.2 一致：业务失败仍 HTTP 200，只有鉴权（401）与
 *      无权限（403）才不是 200。这两个必须与 code 同时正确，客户端是按「HTTP 状态 + body.code」
 *      两个维度分流的（§6.1）。
 *   2. msgKey 是给前端 i18n 的稳定键（《i18n 规范》三段式），msg 只是给开发者看的兜底中文，
 *      **客户端不得直接展示 msg**（接口规范 §5.1 约束 2）。
 *   3. 402002 与 402003 的区分是硬要求：前者「过期，可刷新」，后者「无效，直接登出」；
 *      混为一谈会让客户端进入无限刷新循环（《错误码规范》§4.3 第 3 条）。
 */
package com.faze.common.exception;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 服务端错误码的唯一来源（段 0 子集）。
 */
@Getter
public enum ErrorCode {

    // ── 通用域 01 ──────────────────────────────────────────────────────────
    /** 参数校验失败（Bean Validation）。HTTP 200。 */
    COMMON_PARAM_INVALID(401001, 200, "error.common.param-invalid", "参数校验失败"),
    /** 缺少必填参数。HTTP 200。 */
    COMMON_PARAM_MISSING(401002, 200, "error.common.param-missing", "缺少必填参数"),
    /** 参数格式错误（手机号、日期等）。HTTP 200。 */
    COMMON_PARAM_FORMAT(401003, 200, "error.common.param-format", "参数格式错误"),
    /** 资源不存在。HTTP 200（路径不存在另走 404，见 GlobalExceptionHandler）。 */
    COMMON_NOT_FOUND(401004, 200, "error.common.not-found", "资源不存在"),
    /** 资源已存在。HTTP 200。 */
    COMMON_ALREADY_EXISTS(401005, 200, "error.common.already-exists", "资源已存在"),
    /** 状态非法，当前操作不允许。HTTP 200。 */
    COMMON_ILLEGAL_STATE(401006, 200, "error.common.illegal-state", "状态非法，当前操作不允许"),
    /** 请求过于频繁。HTTP 200。 */
    COMMON_RATE_LIMITED(401007, 200, "error.common.rate-limited", "请求过于频繁"),
    /** 无权限访问该资源。HTTP **403**。 */
    COMMON_FORBIDDEN(401008, 403, "error.common.forbidden", "无权限访问该资源"),
    /** 服务器内部错误（兜底）。HTTP 200。 */
    COMMON_INTERNAL(501001, 200, "error.common.internal", "服务器内部错误"),
    /** 第三方服务不可用。HTTP 200。 */
    COMMON_THIRD_PARTY_DOWN(501002, 200, "error.common.third-party-down", "第三方服务不可用"),
    /** 数据库操作失败。HTTP 200。 */
    COMMON_DB_ERROR(501003, 200, "error.common.db-error", "数据库操作失败"),
    /** 幂等键冲突（重复请求）。HTTP 200。 */
    COMMON_IDEMPOTENT_CONFLICT(501005, 200, "error.common.idempotent-conflict", "重复请求，请勿重复提交"),

    // ── 账户与鉴权域 02 ───────────────────────────────────────────────────
    /** 手机号或密码错误（**不区分是哪个错**，防账号枚举）。HTTP 200。 */
    AUTH_LOGIN_FAILED(402001, 200, "error.auth.login-failed", "手机号或密码错误"),
    /** 登录已过期，可刷新。HTTP **401**。 */
    AUTH_TOKEN_EXPIRED(402002, 401, "error.auth.token-expired", "登录已过期，请重新登录"),
    /** 凭证无效（签名错、已被拉黑），刷了也没用。HTTP **401**。 */
    AUTH_TOKEN_INVALID(402003, 401, "error.auth.token-invalid", "登录凭证无效，请重新登录"),
    /** 账号已被封禁。HTTP **403**。 */
    AUTH_ACCOUNT_BLOCKED(402004, 403, "error.auth.account-blocked", "账号已被封禁"),
    /** 短信验证码错误或已过期。HTTP 200。 */
    AUTH_CODE_INVALID(402005, 200, "error.auth.code-invalid", "验证码错误或已过期"),
    /** 手机号已注册。HTTP 200（**不泄露除「已注册」外的任何信息**）。 */
    AUTH_PHONE_TAKEN(402006, 200, "error.auth.phone-taken", "手机号已注册"),
    /** 账号已注销。HTTP 200。 */
    AUTH_ACCOUNT_CANCELLED(402008, 200, "error.auth.account-cancelled", "账号已注销"),
    /** 未成年人需监护人同意（未填监护人手机号）。HTTP 200。 */
    AUTH_GUARDIAN_REQUIRED(402009, 200, "error.auth.guardian-required", "未成年人需填写监护人手机号"),
    /** 密码强度不足。HTTP 200。 */
    AUTH_PASSWORD_WEAK(402010, 200, "error.auth.password-weak", "密码强度不足"),
    /** 短信发送失败（第三方通道）。HTTP 200。 */
    AUTH_SMS_SEND_FAILED(502001, 200, "error.auth.sms-send-failed", "短信发送失败"),

    // ── 交易与资金域 05 ──────────────────────────────────────────────────
    /** 余额不足。HTTP 200。 */
    TRADE_BALANCE_INSUFFICIENT(405001, 200, "error.trade.balance-insufficient", "账户余额不足"),
    /** 订单不存在（含「不是你的订单」——归属条件进 SQL，查不到即不存在）。HTTP 200。 */
    TRADE_ORDER_NOT_FOUND(405002, 200, "error.trade.order-not-found", "订单不存在"),
    /** 订单状态非法，当前操作不允许（非法状态迁移统一走它，HTTP 200 + 5xxxx）。HTTP 200。 */
    TRADE_ORDER_STATE_INVALID(405003, 200, "error.trade.order-state-invalid", "订单状态非法，当前操作不允许"),
    /** 重复提交（幂等拦截：同一 Idempotency-Key / 重复支付）。HTTP 200。 */
    TRADE_DUPLICATE_SUBMIT(405004, 200, "error.trade.duplicate-submit", "请勿重复提交"),
    /** 退款金额超出可退上限。HTTP 200。 */
    TRADE_REFUND_EXCEEDS(405005, 200, "error.trade.refund-exceeds", "退款金额超出可退上限"),
    /** 金额非法（负数、超限、非整数分）。HTTP 200。 */
    TRADE_AMOUNT_INVALID(405006, 200, "error.trade.amount-invalid", "金额非法"),
    /** 非本人订单（越权）。HTTP **403**。 */
    TRADE_NOT_ORDER_OWNER(405007, 403, "error.trade.not-order-owner", "非本人订单，无权访问"),
    /** 律师不可接单（不存在 / 未通过审核 / 已清退）。HTTP 200。 */
    TRADE_LAWYER_UNAVAILABLE(405008, 200, "error.trade.lawyer-unavailable", "该律师当前不可接单"),
    /** 该服务未上架或未定价（价格为空的律师不许下单成 0 元单）。HTTP 200。 */
    TRADE_SERVICE_UNAVAILABLE(405009, 200, "error.trade.service-unavailable", "该律师未开通此服务"),
    /** 律师不在线且平台要求在线才可下单（开关 `order_require_lawyer_online`）。HTTP 200。 */
    TRADE_LAWYER_OFFLINE(405010, 200, "error.trade.lawyer-offline", "该律师当前不在线"),

    /** 退款单不存在（G4.2 审批时按 id 查不到）。 */
    TRADE_REFUND_NOT_FOUND(405011, 200, "error.trade.refund-not-found", "退款单不存在"),
    /*
     * 评价两码用 405021 / 405022，**不复用 405012 / 405013**：《错误码规范》§三 的 405 段已把
     * 405008~405020 预留给段 5（提现 / 结算 / 充值 / 支付渠道）。段 2 施工已实际占用 405008~405011
     * （见《错误码规范》§三 的「段 2 施工登记」），本任务不再往那段里塞，避免与段 5 撞车。
     */
    /** 评价不存在（律师回复时按 id 查不到）。HTTP 200。 */
    TRADE_REVIEW_NOT_FOUND(405021, 200, "error.trade.review-not-found", "评价不存在"),
    /** 该订单已评价过（一单一次，uk_review_order）。HTTP 200。 */
    TRADE_REVIEW_DUPLICATE(405022, 200, "error.trade.review-duplicate", "该订单已评价，不能重复评价"),

    // ── IM 会话域 04 ──────────────────────────────────────────────────────
    /** 会话不存在。HTTP 200。 */
    CONVERSATION_NOT_FOUND(404001, 200, "error.conversation.not-found", "会话不存在"),
    /** 无权访问该会话（不是会话成员）。HTTP **403**。 */
    CONVERSATION_FORBIDDEN(404002, 403, "error.conversation.forbidden", "无权访问该会话"),

    // ── AI 域 08 ─────────────────────────────────────────────────────────
    /*
     * 段 3 施工登记（2026-11-07）：按《错误码规范》§3.8 补齐 408001~408005 与 508002。
     * 编号与 msgKey 逐字照 §3.8 的表，**不许改含义**——408002 在本段是「每日轮次配额超限」
     * （总纲已裁决 AI 不计费，见 §零之二 第 5 项：「计费」口径统一改为「配额」）。
     */
    /** 未在法条库检索到相关条文（**不编造，直接告知**）。HTTP 200。 */
    AI_NO_LAW_MATCHED(408001, 200, "error.ai.no-law-matched", "未在法条库检索到相关条文"),
    /** AI 每日调用轮次超限（响应带 `retryAfterSeconds`）。HTTP 200。 */
    AI_QUOTA_EXCEEDED(408002, 200, "error.ai.quota-exceeded", "AI 调用次数超限"),
    /** 内容被安全策略拦截（**段 3 不接机审，本段不产出该码**，《错误码规范》§3.8 预留）。HTTP 200。 */
    AI_CONTENT_BLOCKED(408003, 200, "error.ai.content-blocked", "内容被安全策略拦截"),
    /** 超出可咨询范围（诉讼策略 / 胜诉率 / 金额与刑期预测 / 代写可递交文书）。HTTP 200。 */
    AI_OUT_OF_SCOPE(408004, 200, "error.ai.out-of-scope", "超出可咨询范围"),
    /** 检测到 Prompt 注入企图。HTTP 200。 */
    AI_PROMPT_INJECTED(408005, 200, "error.ai.prompt-injected", "检测到 Prompt 注入企图"),
    /** 豆包服务不可用 / 5 秒内未出首字节。HTTP 200。 */
    AI_SERVICE_DOWN(508001, 200, "error.ai.service-down", "AI 服务暂不可用"),
    /** AI 输出校验失败（回答里所有条文号都不存在且无法降级）。HTTP 200。 */
    AI_OUTPUT_INVALID(508002, 200, "error.ai.output-invalid", "AI 输出校验失败，请重试"),

    // ── 文书域 06（段 4，《错误码规范》§3.6） ──────────────────────────────

    /** 模板不存在或已停用。HTTP 200。 */
    DOCUMENT_TEMPLATE_NOT_FOUND(406001, 200, "error.document.template-not-found", "模板不存在或已停用"),
    /** 表单校验失败（必填缺失、类型不符、array 超限、占位符残留）。HTTP 200。 */
    DOCUMENT_FORM_INVALID(406002, 200, "error.document.form-invalid", "表单校验失败"),
    /** 文书不存在（或不属于当前用户——**不区分**，防枚举）。HTTP 200。 */
    DOCUMENT_NOT_FOUND(406003, 200, "error.document.not-found", "文书不存在"),
    /** 无权访问该文书（归属校验失败）。HTTP 403。 */
    DOCUMENT_FORBIDDEN(406004, 403, "error.document.forbidden", "无权访问该文书"),
    /** 签名库已满（每个用户最多 5 个签名图，防图床）。HTTP 200。 */
    SIGNATURE_LIMIT_EXCEEDED(406005, 200, "error.document.signature-limit", "签名数量已达上限（最多 5 个）"),
    /** 签名已被文书引用，不允许删除。HTTP 200。 */
    SIGNATURE_IN_USE(406006, 200, "error.document.signature-in-use", "该签名已用于文书，不能删除"),
    /** 签名不存在（或不属于当前用户——**不区分**，防枚举）。HTTP 200。 */
    SIGNATURE_NOT_FOUND(406007, 200, "error.document.signature-not-found", "签名不存在"),
    /** 文书生成失败（占位符不匹配 / 非法 XHTML / 存储不可用等）。HTTP 200。 */
    DOCUMENT_GENERATE_FAILED(506001, 200, "error.document.generate-failed", "文书生成失败"),
    /** PDF 转换失败（渲染异常）。HTTP 200。 */
    DOCUMENT_PDF_FAILED(506002, 200, "error.document.pdf-failed", "PDF 转换失败"),
    /** 渲染所需字体缺失（中文场景指仿宋 `simfang.ttf` 不存在或路径配错）。HTTP 200。 */
    DOCUMENT_FONT_MISSING(506003, 200, "error.document.font-missing", "渲染所需字体缺失"),

    // ── 案件域 07（段 5，《错误码规范》§3.7） ──────────────────────────────
    /*
     * 段 5 施工登记（2026-11-08）：按《错误码规范》§3.7 补齐 407001~407008。
     * 编号与 msgKey 逐字照 §3.7 的表，**不许改含义**。
     * 407003 是 403（越权），其余 200——只有「无权访问」破例走 HTTP 状态码（§2.2）。
     */
    /** 案件不存在（含「不是你的案件」——归属条件进 SQL，查不到即不存在）。HTTP 200。 */
    CASE_NOT_FOUND(407001, 200, "error.case.not-found", "案件不存在"),
    /** 案件状态非法，当前操作不允许（非法状态迁移统一走它）。HTTP 200。 */
    CASE_STATE_INVALID(407002, 200, "error.case.state-invalid", "案件状态非法，当前操作不允许"),
    /** 无权访问该案件（越权）。HTTP **403**。 */
    CASE_FORBIDDEN(407003, 403, "error.case.forbidden", "无权访问该案件"),
    /** 已立案，不能重复提交（同案重复立案）。HTTP 200。 */
    CASE_ALREADY_FILED(407004, 200, "error.case.already-filed", "该案件已立案，不能重复提交"),
    /** 本案没有可送达的文书（或该文书未生成 PDF）。HTTP 200。 */
    CASE_NO_DOCUMENT(407005, 200, "error.case.no-document", "本案没有可送达的文书"),
    /** 送达记录不存在。HTTP 200。 */
    CASE_DELIVERY_NOT_FOUND(407006, 200, "error.case.delivery-not-found", "送达记录不存在"),
    /** 该文书已签收，不可重复签收（首次结果不变，不覆盖签收时间）。HTTP 200。 */
    CASE_DELIVERY_ALREADY_SIGNED(407007, 200, "error.case.delivery-already-signed", "该文书已签收，不能重复签收"),
    /** 送达方式非法（只允许 platform / sms_pickup / mail）。HTTP 200。 */
    CASE_DELIVERY_METHOD_INVALID(407008, 200, "error.case.delivery-method-invalid", "送达方式非法"),

    // ── 交易域 05 段 5 补登（提现 / 结算，《错误码规范》§3.5） ────────────────
    /*
     * 段 5 施工登记（2026-11-08）：405023~405027。
     * **原定 405008~405012 已被段 2 占用**（见本文件段 2 的注释与《错误码规范》§3.5 的顺延说明），
     * 故按规范顺延后的号落地，不回头改段 2 的码。
     */
    /** 提现金额低于最低限额。HTTP 200。 */
    TRADE_WITHDRAW_BELOW_MIN(405023, 200, "error.trade.withdraw-below-min", "提现金额低于最低限额"),
    /** 可提现余额不足。HTTP 200。 */
    TRADE_WITHDRAW_INSUFFICIENT(405024, 200, "error.trade.withdraw-insufficient", "可提现余额不足"),
    /** 提现单状态非法，当前操作不允许（非法迁移一律 200 + 本码）。HTTP 200。 */
    TRADE_WITHDRAW_STATE_INVALID(405025, 200, "error.trade.withdraw-state-invalid", "提现单状态非法，当前操作不允许"),
    /** 结算数据不一致（流水净额 ≠ 订单收入 − 服务费，**发现即停**，不许写结算单）。HTTP 200。 */
    TRADE_SETTLEMENT_MISMATCH(405026, 200, "error.trade.settlement-mismatch", "结算数据不一致，已停止生成"),
    /** 提现过于频繁（单日笔数超限）。HTTP 200。 */
    TRADE_WITHDRAW_TOO_FREQUENT(405027, 200, "error.trade.withdraw-too-frequent", "提现过于频繁"),

    // ── 交易域 05 段 6 补登（充值 / 支付渠道，《第 6 段工作流》§五 5.5） ─────────
    /*
     * 段 6 施工登记（2026-11-08）：405013~405020。
     * 405012 保持空号：段 2 占 405008~405011、段 5 占 405023~405027，中间的 405012 无用例，
     * 不复用（复用会让「码 → 文档章节」的追溯断掉）。
     * ⚠️ 405020 的 msgKey 按《错误码规范》§3.5 原文是 `error.trade.recharge-realbame-required`，
     *    `realbame` 是拼写错误，这里按 `realname` 落地并与 i18n 文件对齐；已记入 §十一 回写项。
     */
    /** 渠道未启用（sys_config 里该渠道 enabled=false，不许静默回退 Mock）。HTTP 200。 */
    TRADE_CHANNEL_DISABLED(405013, 200, "error.trade.channel-disabled", "该支付渠道未启用"),
    /** 充值金额超出单笔区间（recharge.min_cents ~ recharge.max_amount_cents）。HTTP 200。 */
    TRADE_RECHARGE_AMOUNT_INVALID(405014, 200, "error.trade.recharge-amount-invalid", "充值金额超出允许区间"),
    /** 充值单状态非法，当前操作不允许（非法迁移一律 200 + 本码）。HTTP 200。 */
    TRADE_RECHARGE_STATE_INVALID(405015, 200, "error.trade.recharge-state-invalid", "充值单状态非法，当前操作不允许"),
    /** 超出单日充值限额或笔数（不区分维度，避免被试探）。HTTP 200。 */
    TRADE_RECHARGE_DAILY_LIMIT(405016, 200, "error.trade.recharge-daily-limit", "超出单日充值限额"),
    /** 支付回调验签失败（安全事件，必须写审计）。HTTP 200。 */
    TRADE_CALLBACK_SIGN_INVALID(405017, 200, "error.trade.callback-sign-invalid", "支付回调验签失败"),
    /** 对账存在未处理差异，相关操作需先处理差异。HTTP 200。 */
    TRADE_RECONCILE_DIFF_PENDING(405018, 200, "error.trade.reconcile-diff-pending", "对账存在未处理差异"),
    /** 渠道下单失败（prepay 调用失败）。HTTP 200。 */
    TRADE_CHANNEL_PREPAY_FAILED(405019, 200, "error.trade.channel-prepay-failed", "渠道下单失败"),
    /** 未实名用户不可充值（是拦截不是限额，App 引导实名）。HTTP 200。 */
    TRADE_RECHARGE_REALNAME_REQUIRED(405020, 200, "error.trade.recharge-realname-required", "未实名认证，不能充值"),
    /*
     * 段 6 D3/D4 追加登记（2026-11-08）：405013~405020 已被本段前序任务占满
     * （405012 空号、405021/405022 段 2 评价、405023~405027 段 5 提现/结算），
     * 按顺序取 **405028**，不复用任何已有码。
     */
    /** 渠道对账单非法（空文件 / 缺表头 / 列数不符 / 金额或时间无法精确解析）。HTTP 200。 */
    TRADE_RECONCILE_BILL_INVALID(405028, 200, "error.trade.reconcile-bill-invalid", "渠道对账单格式非法"),

    // ── 内容域 09 段 5 补登（案例库，《错误码规范》§3.9） ────────────────────
    /*
     * 段 5 施工登记（2026-11-08）：409001~409004 已被段 6/段 4 占用，
     * 按《第 5 段工作流》§九 的说明取 409005~409007。
     */
    /** 案例未填法条引用（上线前必填；条号须经 ArticleNoValidator 校验）。HTTP 200。 */
    CONTENT_CASE_NO_LAW_REF(409005, 200, "error.content.case-no-law-ref", "案例未填写法条引用，不能上线"),
    /** 案例语言版本不齐（门禁口径见《第 5 段工作流》§三 G4）。HTTP 200。 */
    CONTENT_CASE_LANG_INCOMPLETE(409006, 200, "error.content.case-lang-incomplete", "案例语言版本不齐"),
    /** 案例已上线，不能直接删除（须先下架）。HTTP 200。 */
    CONTENT_CASE_PUBLISHED(409007, 200, "error.content.case-published", "案例已上线，须先下架再删除");

    /** 6 位业务码（前 3 位是 HTTP 语义家族，第 4~5 位是业务域）。 */
    private final int code;
    /** 该错误码应回的 HTTP 状态：业务失败恒为 200，鉴权 401，无权限 403。 */
    private final int httpStatus;
    /** 前端 i18n 的稳定键，客户端按它走 t()，**不得展示 msg**。 */
    private final String msgKey;
    /** 开发者可见的兜底中文文案。 */
    private final String msg;

    ErrorCode(int code, int httpStatus, String msgKey, String msg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.msgKey = msgKey;
        this.msg = msg;
    }

    /** 业务码 → 枚举 的索引。接口层拿到的是数字，必须能按 code 反查（§12.1）。 */
    private static final Map<Integer, ErrorCode> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ErrorCode::getCode, Function.identity()));

    /**
     * 按 6 位业务码反查枚举。
     *
     * @param code 业务码
     * @return 对应枚举；未登记时返回 null（调用方需自行兜底，不要抛异常）
     */
    public static ErrorCode of(int code) {
        return BY_CODE.get(code);
    }
}
