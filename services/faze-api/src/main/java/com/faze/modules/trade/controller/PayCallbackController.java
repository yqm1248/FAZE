/*
 * 职责：支付渠道异步回调的 HTTP 入口（第 6 段 C1/C2）——三个路径共用一条处理链。
 * 调用方：微信支付 / 支付宝 / Mock 通道的服务器通知。
 * 数据来源：请求体原文 + 请求头 → RechargeCallbackService。
 * 注意：
 *   1. **免鉴权域名** `/api/v1/pub/**`（SecurityConfig 已 permitAll），
 *      所以本控制器不接收 `@AuthenticationPrincipal`，也不写登录态。**唯一信任来源是验签**。
 *   2. **响应体是渠道要求的格式，不是 `R<T>`**：微信 `{"code":"SUCCESS"}`、
 *      支付宝纯文本 `success`、Mock 简化 JSON。返回统一响应体会让渠道无限重试。
 *   3. 三个路径的**业务逻辑完全一样**，区别只在网关的 parseCallback 与应答格式；
 *      所以只有控制器不同，服务层一份。
 *   4. **Mock 路径只用于开发/验收**：生产环境 `pay.mock.enabled=false`，
 *      `PaymentGatewayFactory.get(MOCK)` 会抛 405013，回调自然拒绝。
 *   5. 支付宝回调是 `application/x-www-form-urlencoded` 表单（不是 JSON），
 *      用 `@RequestBody` 收原文会拿到 `a=1&b=2` 字符串，由解析器自己 split（C2 第 2 条）。
 *   6. 不读 `@RequestBody` 之外的东西；报文不落日志（含 L4 字段）。
 */
package com.faze.modules.trade.controller;

import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.service.RechargeCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付回调接口。
 */
@RestController
@RequestMapping("/api/v1/pub/pay/callback")
@RequiredArgsConstructor
public class PayCallbackController {

    private final RechargeCallbackService rechargeCallbackService;

    /**
     * 微信支付回调（APIv3）。
     *
     * @param request 请求（取签名头 + 原始报文）
     * @return 微信要求的应答体
     */
    @PostMapping("/wechat")
    public ResponseEntity<String> wechat(HttpServletRequest request) {
        return toResponse(rechargeCallbackService.handle(PayChannel.WECHAT, readRawBody(request),
                headersOf(request)));
    }

    /**
     * 支付宝回调（表单格式）。
     *
     * @param request 请求（报文是 `a=1&b=2` 表单原文）
     * @return 纯文本 `success` / `fail`
     */
    @PostMapping("/alipay")
    public ResponseEntity<String> alipay(HttpServletRequest request) {
        return toResponse(rechargeCallbackService.handle(PayChannel.ALIPAY, readRawBody(request),
                headersOf(request)));
    }

    /**
     * Mock 通道回调（仅开发/验收；生产关闭时工厂会拒绝）。
     *
     * @param request 请求（Mock 报文是表单编码，由 MockPaymentGateway 预生成）
     * @return 简化 JSON
     */
    @PostMapping("/mock")
    public ResponseEntity<String> mock(HttpServletRequest request) {
        return toResponse(rechargeCallbackService.handle(PayChannel.MOCK, readRawBody(request),
                headersOf(request)));
    }

    /**
     * 读原始报文。
     *
     * <p>**不用 `@RequestBody`，也不能只读输入流**：支付宝 / Mock 的回调是
     * `application/x-www-form-urlencoded`，Tomcat 在第一次 `getInputStream()` 时就会把
     * 表单解析成参数并交回空流，于是拿到的 body 是空串、验签必然失败（这是 C2 第 2 条
     * 「表单不是 JSON」的具体坑，2026-11-08 实测踩到）。</p>
     *
     * <p>所以按 content-type 分两路：表单走 `getParameterMap()` 重建 `k=v&…`（URL 编码后用
     * 与原始报文等价的字符串参与验签），其它（微信 JSON、Mock 若发 JSON）直读输入流。</p>
     *
     * @param request 请求
     * @return 报文字符串；读取失败返回空串
     */
    private String readRawBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null
                && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                for (String value : entry.getValue()) {
                    if (builder.length() > 0) {
                        builder.append('&');
                    }
                    builder.append(java.net.URLEncoder.encode(entry.getKey(),
                                    java.nio.charset.StandardCharsets.UTF_8))
                            .append('=')
                            .append(java.net.URLEncoder.encode(value == null ? "" : value,
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return builder.toString();
        }
        try (java.io.InputStream stream = request.getInputStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(PayCallbackController.class)
                    .warn("读取回调报文失败", e);
            return "";
        }
    }

    /**
     * 组装应答。
     *
     * @param reply 服务层应答
     * @return HTTP 响应
     */
    private ResponseEntity<String> toResponse(RechargeCallbackService.Reply reply) {
        return ResponseEntity.status(reply.httpStatus())
                .header("Content-Type", reply.contentType() + "; charset=UTF-8")
                .body(reply.body());
    }

    /**
     * 取全部请求头（签名头在微信/AliPay 里名字不同，统一透传给解析器）。
     *
     * @param request 请求
     * @return 头名 → 值
     */
    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, request.getHeader(name)));
        return headers;
    }
}
