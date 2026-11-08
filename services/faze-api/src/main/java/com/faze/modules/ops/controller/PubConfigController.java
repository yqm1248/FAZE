/*
 * 职责：公开配置读取接口——只把**允许匿名阅读**的少量配置项暴露给未登录客户端。
 * 调用方：App「用户服务协议」页 / 「隐私政策」页
 *         （apps/mobile-app/features/auth/api/legalDoc.ts）。
 * 数据来源：faze.sys_config，经 SysConfigQueryService 只读查询。
 * 注意：
 *   1. **必须走白名单**（PUBLIC_CONFIG_KEYS）。sys_config 里同一张表躺着
 *      sms_fixed_code / sms_fixed_code_enabled / pay_pwd_max_fail 这类东西，
 *      若按 `/{configKey}` 直通查询，匿名用户就能读到开发期固定验证码，
 *      等于把短信验证整条链路废掉。白名单是这张表对匿名世界唯一的出口。
 *   2. 非白名单键与「键存在但无值」**都回 401004 资源不存在**，不区分——
 *      区分了就等于告诉攻击者「这个键存在，只是不给你看」，可用来枚举配置项。
 *   3. 免登录：`/api/v1/pub/**` 在 SecurityConfig 里是 permitAll，
 *      所以本接口不接收 `@AuthenticationPrincipal`，也不写登录态。
 *   4. 纯查询，按审计规范 §3.2 不标 `@OperationLog`。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.entity.SysConfig;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开配置读取接口。
 */
@RestController
@RequestMapping("/api/v1/pub/config")
@RequiredArgsConstructor
public class PubConfigController {

    /**
     * 允许匿名读取的配置键白名单。
     *
     * <p>只有「本来就是给用户看的文本」才允许进这里。新增键时必须同时想清楚：
     * 这个值泄露给匿名用户会不会削弱任何一道校验。想不清楚就不加。</p>
     */
    private static final Set<String> PUBLIC_CONFIG_KEYS = Set.of(
            "user_agreement_content",
            "privacy_policy_content");

    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 读取一条公开配置。
     *
     * @param configKey 配置键，必须在白名单内
     * @return 配置键、正文与更新时间
     * @throws BusinessException 401004 键不在白名单、键不存在或值为空
     */
    @GetMapping("/{configKey}")
    public R<PubConfigVO> get(@PathVariable String configKey) {
        if (!PUBLIC_CONFIG_KEYS.contains(configKey)) {
            // detail 只进日志：日志里能看出有人在探测，响应体里看不出键是否存在。
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "公开配置白名单外的键：" + configKey);
        }
        SysConfig row = sysConfigQueryService.findRow(configKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND,
                        "公开配置键在 sys_config 里不存在：" + configKey));
        String content = row.getConfigValue();
        if (content == null || content.isBlank()) {
            // 键存在但没有正文（运营还没填）——对页面来说等价于「没有这份文本」，
            // 让页面走空态，而不是渲染一张空白协议。
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND,
                    "公开配置键存在但正文为空：" + configKey);
        }
        PubConfigVO vo = new PubConfigVO();
        vo.setConfigKey(row.getConfigKey());
        vo.setConfigValue(content);
        vo.setUpdatedAt(row.getUpdatedAt());
        return R.ok(vo);
    }

    /**
     * 公开配置出参。
     *
     * <p>字段名与前端 `features/auth/api/legalDoc.ts` 的 `SysConfigItem` 一一对应，
     * 改字段名要同步改前端。</p>
     */
    public static class PubConfigVO {

        /** 配置键。 */
        private String configKey;

        /** 配置值（法务文本正文，纯文本，换行即分段）。 */
        private String configValue;

        /** 最后更新时间（UTC，无时区后缀，与 RealnameVO 等 C 端出参一致）。 */
        private LocalDateTime updatedAt;

        public String getConfigKey() {
            return configKey;
        }

        public void setConfigKey(String configKey) {
            this.configKey = configKey;
        }

        public String getConfigValue() {
            return configValue;
        }

        public void setConfigValue(String configValue) {
            this.configValue = configValue;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
