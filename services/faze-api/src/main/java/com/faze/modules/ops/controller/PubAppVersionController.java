/*
 * 职责：`GET /api/v1/pub/app/version`（E5）——APP 版本检查与强制更新判定。
 * 调用方：apps/mobile-app 的 E1 设置页「检查更新」与 41b 更新弹窗。
 * 数据来源：faze.sys_config 的 4 个键（V708 建）：
 *           `app.version.latest` / `app.version.min` / `app.version.download_url` / `app.version.changelog`。
 * 注意：
 *   1. **版本号不写死在代码里**：E5 算做完第 1 条就是「改 sys_config 看 APP 是否弹提示」，
 *      写死就验不了。代码只保留兜底默认值（配置被删时不让客户端 500）。
 *   2. **强更判定用语义化版本比较**（common/util/Semver），不是字符串比较：
 *      `"1.10.0" < "1.9.0"` 在字符串比较下成立，会把新版判成旧版。
 *   3. `forceUpdate = currentVersion < minVersion`；`currentVersion` 缺失时**不判强更**
 *      （老客户端可能不带这个参数，不能因此把它锁死）。
 *   4. `platform` 目前只做透传与日志（安卓/iOS 是否分版本由运营在 sys_config 里写一个
 *      统一版本号决定；将来要分平台时按 `app.version.latest.android` 扩展，不改本接口形状）。
 *   5. 免登录：`/api/v1/pub/**` 在 SecurityConfig 里 permitAll。
 *   6. 纯查询，按《审计与日志规范》§3.2 不标 `@OperationLog`。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.util.Semver;
import com.faze.modules.ops.service.SysConfigQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * APP 版本检查接口。
 */
@RestController
@RequestMapping("/api/v1/pub/app")
@RequiredArgsConstructor
@Slf4j
public class PubAppVersionController {

    /** 最新版本号的配置键。 */
    private static final String KEY_LATEST = "app.version.latest";

    /** 最低支持版本号的配置键（低于它强制更新）。 */
    private static final String KEY_MIN = "app.version.min";

    /** 下载地址的配置键。 */
    private static final String KEY_DOWNLOAD_URL = "app.version.download_url";

    /** 更新说明的配置键。 */
    private static final String KEY_CHANGELOG = "app.version.changelog";

    /** 配置缺失时的兜底版本号（与 V708 种子一致）。 */
    private static final String FALLBACK_VERSION = "1.0.0";

    /** 平台参数只读查询。 */
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 检查 APP 版本。
     *
     * <p>无副作用（只读 sys_config）。所有键缺失时仍返回 200 + 兜底值，不让 APP 启动即报错。</p>
     *
     * @param platform       平台（android / ios / web；当前只记日志）
     * @param currentVersion 客户端当前版本（语义化；缺失时不判强更）
     * @return 版本信息与强更标记
     */
    @GetMapping("/version")
    public R<AppVersionVO> version(@RequestParam(required = false) String platform,
                                   @RequestParam(required = false) String currentVersion) {
        String latest = sysConfigQueryService.getString(KEY_LATEST, FALLBACK_VERSION);
        String min = sysConfigQueryService.getString(KEY_MIN, FALLBACK_VERSION);

        AppVersionVO vo = new AppVersionVO();
        vo.setLatestVersion(latest);
        vo.setMinVersion(min);
        vo.setDownloadUrl(sysConfigQueryService.getString(KEY_DOWNLOAD_URL, ""));
        vo.setChangelog(sysConfigQueryService.getString(KEY_CHANGELOG, ""));
        vo.setForceUpdate(StringUtils.hasText(currentVersion) && Semver.isLower(currentVersion, min));
        log.debug("APP 版本检查 platform={} current={} latest={} min={} force={}",
                platform, currentVersion, latest, min, vo.isForceUpdate());
        return R.ok(vo);
    }

    /**
     * 版本信息出参。
     */
    public static class AppVersionVO {

        /** 最新版本号。 */
        private String latestVersion;

        /** 最低支持版本号。 */
        private String minVersion;

        /** 下载地址；空串表示运营未配置（客户端不给「去更新」按钮）。 */
        private String downloadUrl;

        /** 更新说明（可含 \n 换行）。 */
        private String changelog;

        /** 是否强制更新（currentVersion < minVersion）。 */
        private boolean forceUpdate;

        public String getLatestVersion() {
            return latestVersion;
        }

        public void setLatestVersion(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        public String getMinVersion() {
            return minVersion;
        }

        public void setMinVersion(String minVersion) {
            this.minVersion = minVersion;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public String getChangelog() {
            return changelog;
        }

        public void setChangelog(String changelog) {
            this.changelog = changelog;
        }

        public boolean isForceUpdate() {
            return forceUpdate;
        }

        public void setForceUpdate(boolean forceUpdate) {
            this.forceUpdate = forceUpdate;
        }
    }
}
