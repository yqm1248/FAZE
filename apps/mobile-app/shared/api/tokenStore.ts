/**
 * 会话与设备标识的持久化。
 *
 * 职责：SecureStore 读写的**唯一入口**。页面与 `features/<域>/api` 一律经此，不许直接 import
 * `expo-secure-store`（B2 算做完第 3 条用 grep 断言这条）。
 * 调用方：`shared/api/client.ts`、`app/index.tsx`、`app/_layout.tsx`、`features/auth/api/auth.ts`。
 * 注意：
 *   1. 键名集中在本文件，**不许在别处拼字符串**——拼错了表现是「重启后登录态丢失」。
 *   2. `expireStoredSession(rejectedToken)` 必须比对「被拒的 token 是否就是当前存的」：
 *      迟到的 401 若把刚登录的新会话清掉，用户会看到「刚登录就被踢」。
 *   3. Web 上 SecureStore 不可用，降级到 `localStorage`（**仅开发期 web 调试**，
 *      真机/生产必须走钥匙串/Keystore；这条降级要在验收记录里注明）。
 */

import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/** 存储键（集中定义，禁止外部拼写）。 */
export const STORAGE_KEYS = {
  accessToken: 'faze.accessToken',
  refreshToken: 'faze.refreshToken',
  deviceId: 'faze.deviceId',
  lang: 'faze.lang',
  rtlPrevPath: 'faze.rtlPrevPath',
} as const;

/** 一次会话的两枚令牌。 */
export type Session = {
  accessToken: string;
  refreshToken: string;
};

const isWeb = Platform.OS === 'web';

/**
 * 读取一个键。
 *
 * @param key 键名（取自 `STORAGE_KEYS`）
 * @returns 值；不存在时返回 null
 */
async function readItem(key: string): Promise<string | null> {
  if (isWeb) {
    return globalThis.localStorage?.getItem(key) ?? null;
  }
  return SecureStore.getItemAsync(key);
}

/**
 * 写入一个键。
 *
 * @param key   键名
 * @param value 值（非空）
 */
async function writeItem(key: string, value: string): Promise<void> {
  if (isWeb) {
    globalThis.localStorage?.setItem(key, value);
    return;
  }
  await SecureStore.setItemAsync(key, value, {
    // 只在本机解锁后可读，不参与 iCloud/Google 自动备份迁移。
    keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
  });
}

/**
 * 删除一个键（不存在时无副作用）。
 *
 * @param key 键名
 */
async function deleteItem(key: string): Promise<void> {
  if (isWeb) {
    globalThis.localStorage?.removeItem(key);
    return;
  }
  await SecureStore.deleteItemAsync(key);
}

/**
 * 读取当前会话。
 *
 * @returns 两枚令牌都存在时返回会话；否则 null
 */
export async function readSession(): Promise<Session | null> {
  const accessToken = await readItem(STORAGE_KEYS.accessToken);
  const refreshToken = await readItem(STORAGE_KEYS.refreshToken);
  if (!accessToken || !refreshToken) {
    return null;
  }
  return { accessToken, refreshToken };
}

/**
 * 写入会话（登录成功 / 刷新成功后调用）。
 *
 * @param session 新会话
 */
export async function writeSession(session: Session): Promise<void> {
  await writeItem(STORAGE_KEYS.accessToken, session.accessToken);
  await writeItem(STORAGE_KEYS.refreshToken, session.refreshToken);
}

/**
 * 只清「被服务端拒绝的那个 access token」对应的会话。
 *
 * 典型场景：token 过期后并发了 3 个请求，其中 2 个带着旧 token 迟到返回 401。
 * 若直接清库，会把第 1 个请求刚刷新出来的新会话一起清掉，用户表现为「刚登录就被踢」。
 *
 * @param rejectedAccessToken 本次被判 401 的 access token
 * @returns 是否真的清了（false 表示是迟到 401，已丢弃）
 */
export async function expireStoredSession(rejectedAccessToken: string): Promise<boolean> {
  const currentAccessToken = await readItem(STORAGE_KEYS.accessToken);
  if (currentAccessToken !== rejectedAccessToken) {
    return false;
  }
  await deleteItem(STORAGE_KEYS.accessToken);
  await deleteItem(STORAGE_KEYS.refreshToken);
  return true;
}

/**
 * 清空会话（主动登出用，无并发判定）。
 */
export async function clearSession(): Promise<void> {
  await deleteItem(STORAGE_KEYS.accessToken);
  await deleteItem(STORAGE_KEYS.refreshToken);
}

/**
 * 取设备标识；首次调用时生成 UUIDv4 并持久化。
 *
 * 服务端登录请求体里的 `deviceId` 必须与请求头 `X-Device-Id` 相同，
 * 所以两边都必须走这一个函数（B1/B2 的契约）。
 *
 * @returns 设备标识（UUIDv4）
 */
export async function getDeviceId(): Promise<string> {
  const existing = await readItem(STORAGE_KEYS.deviceId);
  if (existing) {
    return existing;
  }
  const generated = createUuidV4();
  await writeItem(STORAGE_KEYS.deviceId, generated);
  return generated;
}

/**
 * 读语言偏好。
 *
 * B3 接入 i18n 后，语言状态的唯一来源是 i18n store；本函数只作为启动早期的引导读取。
 *
 * @returns 语言码；未设置时返回 null（调用方决定默认值）
 */
export async function readStoredLang(): Promise<string | null> {
  return readItem(STORAGE_KEYS.lang);
}

/**
 * 写语言偏好（重启后保持）。
 *
 * @param lang 语言码（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）
 */
export async function writeStoredLang(lang: string): Promise<void> {
  await writeItem(STORAGE_KEYS.lang, lang);
}

/**
 * 保存「切换方向前的路由」，用于软重启后回到原页。
 *
 * @param payload 形如 `{"pathname":"/language","params":{}}`
 */
export async function writeRtlPrevPath(payload: string): Promise<void> {
  await writeItem(STORAGE_KEYS.rtlPrevPath, payload);
}

/**
 * 取出并清空「切换方向前的路由」。
 *
 * @returns 之前保存的 JSON 字符串；不存在时 null
 */
export async function takeRtlPrevPath(): Promise<string | null> {
  const value = await readItem(STORAGE_KEYS.rtlPrevPath);
  if (value) {
    await deleteItem(STORAGE_KEYS.rtlPrevPath);
  }
  return value;
}

/**
 * 生成 UUIDv4（不引 `uuid` 依赖，20 行以内能保证正确）。
 *
 * 优先用 `crypto.randomUUID()`（Hermes 0.81 已提供）；缺失时退回手写实现。
 *
 * @returns UUIDv4 字符串
 */
export function createUuidV4(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i += 1) {
    bytes[i] = Math.floor(Math.random() * 256);
  }
  // RFC 4122：第 7 字节高 4 位置为 4（版本），第 9 字节高 2 位置为 10（变体）
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
