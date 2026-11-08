/**
 * 版本检查接口与判定（段 7 E1「检查更新」+ E5 版本弹窗）。
 *
 * 职责：
 *   1. 调 `GET /pub/app/version` 拿最新版本 / 最低版本 / 下载地址 / 更新说明；
 *   2. 用纯函数判定「已是最新 / 可稍后 / 强制更新」三种结果。
 * 所属路由：弹窗挂在 `/settings`（设置页「检查更新」），不新增独立页（E5 明确「41b 做成弹窗」）。
 * 数据来源：`GET /api/v1/pub/app/version?platform=&currentVersion=`（后端读 `sys_config` 的
 *           `app.version.latest` / `app.version.min` / `app.version.download_url` / `app.version.changelog`）。
 * 注意：
 *   1. **不新增第 69 页**：设计稿 41b 实现为 `shared/components/UpdateModal.tsx`。
 *   2. 版本比较不引第三方 semver：只有 `x.y.z` 三段数字，自己写 20 行更可控，
 *      也避免新增依赖要登记依赖清单。
 *   3. `currentVersion` 取构建号（`Constants.expoConfig.version`），**不写死** `1.0.0`。
 *   4. 强更只做前端判断（工作流：本段不做灰度）；真实热更新通道段 8 再接。
 *   5. `platform` 传真实平台；Web 验收环境下后端只认 `android` / `ios`，
 *      所以 Web 统一按 `android` 上报（Web 不是真实发布平台，不影响线上判定）。
 */

import Constants from 'expo-constants';
import { Platform } from 'react-native';

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 版本接口出参（`/pub/app/version`）。 */
export type AppVersionData = {
  /** 最新版本号，形如 `1.1.0`。 */
  latestVersion: string;
  /** 最低可运行版本号；当前版本低于它必须更新。 */
  minVersion: string;
  /** 更新包下载地址（真实热更新通道未打通，本段只用于「去更新」跳转）。 */
  downloadUrl: string;
  /** 更新说明；允许用 `\n` 分隔多行（设计稿 41b 的「本次更新」列表）。 */
  changelog: string;
  /** 后端计算的是否强制更新（前端仍按 minVersion 再判一次，双保险）。 */
  forceUpdate: boolean;
};

/** 更新判定的三种结果。 */
export type UpdateDecision = 'latest' | 'optional' | 'force';

/**
 * 取客户端当前版本号。
 *
 * @returns 形如 `1.0.0`（配置缺失时退回 `1.0.0`，与 app.config.ts 的默认版本一致）
 */
export function currentAppVersion(): string {
  return Constants.expoConfig?.version ?? '1.0.0';
}

/**
 * 把 `v1.2.3` / `1.2.3` 解析成三段数字。
 *
 * @param version 版本号字符串
 * @returns 三段非负整数；非法段落按 0 处理（不抛异常，版本脏数据不该让页面崩）
 */
function parseVersion(version: string): [number, number, number] {
  const cleaned = version.trim().replace(/^v/i, '');
  const parts = cleaned.split('.');
  const toInt = (raw: string | undefined): number => {
    const value = Number.parseInt(raw ?? '', 10);
    return Number.isFinite(value) && value >= 0 ? value : 0;
  };
  return [toInt(parts[0]), toInt(parts[1]), toInt(parts[2])];
}

/**
 * 比较两个版本号。
 *
 * @param left  左版本
 * @param right 右版本
 * @returns 左 < 右 返回 -1；相等返回 0；左 > 右 返回 1
 */
export function compareVersion(left: string, right: string): number {
  const [leftMajor, leftMinor, leftPatch] = parseVersion(left);
  const [rightMajor, rightMinor, rightPatch] = parseVersion(right);
  if (leftMajor !== rightMajor) {
    return leftMajor < rightMajor ? -1 : 1;
  }
  if (leftMinor !== rightMinor) {
    return leftMinor < rightMinor ? -1 : 1;
  }
  if (leftPatch !== rightPatch) {
    return leftPatch < rightPatch ? -1 : 1;
  }
  return 0;
}

/**
 * 判定当前版本该走哪条分支。
 *
 * 规则（工作流 §三 E5 第 3 条）：
 * - `current < minVersion` → 强更（弹窗不可关闭）；
 * - `current < latestVersion` → 可稍后提示；
 * - 相等（或更高，例如灰度包）→ 已是最新。
 *
 * @param current 当前版本
 * @param data    服务端版本信息
 * @returns 判定结果
 */
export function decideUpdate(current: string, data: AppVersionData): UpdateDecision {
  if (compareVersion(current, data.minVersion) < 0) {
    return 'force';
  }
  if (compareVersion(current, data.latestVersion) < 0) {
    return 'optional';
  }
  return 'latest';
}

/**
 * 把 `changelog` 拆成逐行条目（弹窗「本次更新」列表）。
 *
 * @param changelog 服务端返回的更新说明（可能含 `\n`、`\r\n`、空行）
 * @returns 去掉空行后的条目数组，最多 6 条（多了会把弹窗撑出屏幕）
 */
export function changelogLines(changelog: string): string[] {
  return changelog
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .slice(0, 6);
}

/**
 * 查询服务端版本信息。
 *
 * @param currentVersion 当前客户端版本（缺省取构建号）
 * @returns 成功时 `data` 为版本信息
 */
export function getAppVersion(currentVersion = currentAppVersion()): Promise<ApiResult<AppVersionData>> {
  // Web 只是验收环境，后端 platform 白名单是 android / ios；按 android 上报避免 400。
  const platform = Platform.OS === 'web' ? 'android' : Platform.OS;
  const query = `platform=${encodeURIComponent(platform)}&currentVersion=${encodeURIComponent(currentVersion)}`;
  return get<AppVersionData>(`/api/v1/pub/app/version?${query}`);
}
