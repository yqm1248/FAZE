/**
 * 本地偏好存储（通知 / 隐私开关这类尚无后端接口的客户端设置）。
 *
 * 职责：给「本机保存的偏好」一个唯一入口，避免各页面直接摸 `localStorage`。
 * 调用方：`features/settings/pages/NotifySettingsPage.tsx`、`PrivacySettingsPage.tsx`。
 * 数据来源：Web 用 `localStorage`；原生没有接入（`localStorage` 不存在时退回进程内 Map）。
 * 注意：
 *   1. **这不是业务数据的存储层**：业务数据（订单、余额、资料）一律以服务端为准。
 *   2. 什么时候删掉本文件：通知 / 隐私偏好接口上线后（`docs` 里有挂账），
 *      页面改调接口、本文件随之删除。
 *   3. 读失败（JSON 损坏 / 存储被禁）一律返回默认值，不让页面崩。
 */

/** 内存兜底表：原生或隐私模式下 localStorage 不可用时使用（重启即丢）。 */
const memory = new Map<string, string>();

/**
 * 取存储实现；不可用时退回内存。
 *
 * @returns 具备 get/set 的对象
 */
function store(): Pick<Storage, 'getItem' | 'setItem'> {
  if (typeof globalThis.localStorage !== 'undefined') {
    return globalThis.localStorage;
  }
  return {
    getItem: (key: string) => memory.get(key) ?? null,
    setItem: (key: string, value: string) => {
      memory.set(key, value);
    },
  };
}

/**
 * 读一个布尔偏好。
 *
 * @param key 偏好键（集中在本文件的调用方里定义）
 * @param fallback 不存在或解析失败时的默认值
 * @returns 布尔值
 */
export function readBoolPref(key: string, fallback: boolean): boolean {
  try {
    const raw = store().getItem(key);
    if (raw === null) {
      return fallback;
    }
    return raw === 'true';
  } catch {
    return fallback;
  }
}

/**
 * 写一个布尔偏好。
 *
 * @param key 偏好键
 * @param value 值
 */
export function writeBoolPref(key: string, value: boolean): void {
  try {
    store().setItem(key, value ? 'true' : 'false');
  } catch {
    // 存储不可用（无痕模式 / 配额满）时静默失败：偏好只影响展示，不该让页面崩。
  }
}
