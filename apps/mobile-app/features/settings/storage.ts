/**
 * 设置页的本机存储与缓存管理（段 7 E1）。
 *
 * 职责：
 *   1. 提供设置项的持久化读写：`settings.fontScale`（字体四档）、`settings.theme`（深色三选）；
 *   2. 提供「缓存大小测量」与「清除缓存」两个能力，供清除缓存入口使用。
 * 所属路由：`/settings`（设置页）。字体档位也被页面读取用于真实字号渲染。
 * 数据来源：本机存储（Web 为 `localStorage`；原生为进程内 Map——见注意事项第 1 条）。
 * 注意：
 *   1. **项目未安装 `@react-native-async-storage/async-storage`**（package.json 里没有，
 *      新增依赖要登记 `docs/architecture/依赖清单.md`，不在本任务写作用域内）。
 *      因此这里按 AsyncStorage 的三方法（getItem / setItem / removeItem）做了一层等价封装：
 *      Web 用 `localStorage`（重启浏览器后保持，验收环境就是 Web）；原生端用进程内 Map，
 *      **真机上重启会丢**——这是本段允许的降级，已记 `docs/acceptance/seg7/T2-APP-E组4页与设置.md`。
 *      段 8 装上 AsyncStorage 后，只需把 `readRaw` / `writeRaw` 换成它的实现，页面不用改。
 *   2. **原生端不许 import `expo-secure-store`**：`shared/api/tokenStore.ts` 是全仓唯一入口
 *      （段 1 B2 的硬约束），所以这里也不碰 SecureStore，token 与本文件的键互不影响。
 *   3. 「清除缓存」只能删**本文件登记的缓存命名空间**（`faze.cache.` 前缀）与浏览器 Cache Storage，
 *      **绝不能**删 `faze.accessToken` / `faze.refreshToken` / `faze.lang` / 文书草稿键——
 *      验收点明文要求「清完 token 还在、草稿还在」。
 *   4. 缓存大小口径：Web 上取「本次会话已加载的可再生资源字节（`performance` 资源计时）
 *      + Cache Storage 字节 + `faze.cache.` 键字节」，再减去上次清除时的基线；
 *      所以清除后立刻重测必定为 0。原生端无 `expo-file-system`（未安装）时返回 0 并说明，
 *      装好后把 `nativeCacheBytes()` 换成遍历 `cacheDirectory` 求和即可。
 */

import type { FontScaleLevel } from '@/shared/theme/fontScale';
import { DEFAULT_FONT_SCALE, isFontScaleLevel } from '@/shared/theme/fontScale';

/** 存储键（集中定义，禁止外部拼写；与工作流 §三 E1 表格里的 `settings.*` 对应）。 */
export const SETTINGS_KEYS = {
  /** 字体大小四档（`sm` / `md` / `lg` / `xl`）。 */
  fontScale: 'settings.fontScale',
  /** 深色模式三选（`system` / `on` / `off`）。 */
  theme: 'settings.theme',
  /** 上次清除缓存时测得的基线字节数（用于「清完变 0」的口径）。 */
  cacheBaseline: 'settings.cacheBaseline',
} as const;

/** 深色模式三选。**本段只做开关与持久化**，主题令牌替换挂到段 8（工作流 §三 E1 卡住怎么办）。 */
export type ThemeMode = 'system' | 'on' | 'off';

/** 深色模式三选的默认值：跟随系统。 */
export const DEFAULT_THEME_MODE: ThemeMode = 'system';

/** 缓存键前缀：清除缓存时只删带这个前缀的本地键。 */
export const CACHE_KEY_PREFIX = 'faze.cache.';

/** 本机存储键的内存兜底表（原生端与隐私模式下使用）。 */
const memoryStore = new Map<string, string>();

/**
 * 当前是否可用 `localStorage`（Web / 隐私模式判断）。
 *
 * @returns 可用时返回 localStorage，否则 null
 */
function localStore(): Storage | null {
  try {
    if (typeof globalThis.localStorage === 'undefined') {
      return null;
    }
    return globalThis.localStorage;
  } catch {
    // Safari 隐私模式下访问 localStorage 会抛 SecurityError。
    return null;
  }
}

/**
 * 读一个键的原始字符串。
 *
 * @param key 键名（取自 `SETTINGS_KEYS` 或 `faze.cache.` 前缀）
 * @returns 值；不存在或存储不可用时返回 null
 */
async function readRaw(key: string): Promise<string | null> {
  const store = localStore();
  if (store !== null) {
    try {
      return store.getItem(key);
    } catch {
      return null;
    }
  }
  return memoryStore.get(key) ?? null;
}

/**
 * 写一个键。
 *
 * @param key   键名
 * @param value 值（字符串，结构化数据自行 JSON 序列化）
 */
async function writeRaw(key: string, value: string): Promise<void> {
  const store = localStore();
  if (store !== null) {
    try {
      store.setItem(key, value);
      return;
    } catch {
      // 配额满 / 隐私模式：退回内存，至少本次会话内不丢。
    }
  }
  memoryStore.set(key, value);
}

/**
 * 删除一个键（不存在时无副作用）。
 *
 * @param key 键名
 */
async function removeRaw(key: string): Promise<void> {
  const store = localStore();
  if (store !== null) {
    try {
      store.removeItem(key);
    } catch {
      // 忽略：删不掉也不该让页面崩。
    }
  }
  memoryStore.delete(key);
}

/**
 * 设置项的存储封装（AsyncStorage 三方法等价实现）。
 *
 * 供设置页之外的页面复用同一套键空间；**不要**用它存 token（token 走 `shared/api/tokenStore`）。
 */
export const settingsStorage = {
  getItem: readRaw,
  setItem: writeRaw,
  removeItem: removeRaw,
} as const;

/**
 * 读字体档位。
 *
 * @returns 合法档位；未设置或值非法时返回默认（标准）
 */
export async function readFontScale(): Promise<FontScaleLevel> {
  const raw = await readRaw(SETTINGS_KEYS.fontScale);
  return isFontScaleLevel(raw) ? raw : DEFAULT_FONT_SCALE;
}

/**
 * 写字体档位（持久化，切换后立即生效）。
 *
 * @param level 档位
 */
export async function writeFontScale(level: FontScaleLevel): Promise<void> {
  await writeRaw(SETTINGS_KEYS.fontScale, level);
}

/**
 * 读深色模式设置。
 *
 * @returns `system` / `on` / `off`；未设置或值非法时返回「跟随系统」
 */
export async function readThemeMode(): Promise<ThemeMode> {
  const raw = await readRaw(SETTINGS_KEYS.theme);
  if (raw === 'on' || raw === 'off' || raw === 'system') {
    return raw;
  }
  return DEFAULT_THEME_MODE;
}

/**
 * 写深色模式设置。
 *
 * **本段不同步服务端**（工作流裁决），也不替换主题令牌；只做持久化，
 * 供段 8 的统一主题层读取。
 *
 * @param mode 三选值
 */
export async function writeThemeMode(mode: ThemeMode): Promise<void> {
  await writeRaw(SETTINGS_KEYS.theme, mode);
}

/**
 * 统计本次会话已加载的可再生资源字节（仅 Web 有 `performance` 资源计时）。
 *
 * 口径说明：这些是浏览器为打开 App 而下载并缓存的脚本、图片、字体，
 * 属于「图片与临时文件缓存」里用户能感知的部分（断网后重新打开会变慢）。
 *
 * @returns 字节数；非 Web 或拿不到时为 0
 */
function webResourceBytes(): number {
  if (typeof performance === 'undefined') {
    return 0;
  }
  const entries = performance.getEntriesByType('resource') as PerformanceResourceTiming[];
  let total = 0;
  for (const entry of entries) {
    // encodedBodySize 是压缩后的真实传输字节；部分资源为 0 时退回 transferSize。
    const size = entry.encodedBodySize > 0 ? entry.encodedBodySize : entry.transferSize;
    total += Number.isFinite(size) ? size : 0;
  }
  return total;
}

/**
 * 统计浏览器 Cache Storage 的总字节（仅 Web，且需要站点有 Cache Storage 数据）。
 *
 * @returns 字节数；不支持时为 0
 */
async function webCacheStorageBytes(): Promise<number> {
  if (typeof caches === 'undefined') {
    return 0;
  }
  let total = 0;
  try {
    const names = await caches.keys();
    for (const name of names) {
      const cache = await caches.open(name);
      const requests = await cache.keys();
      for (const request of requests) {
        const response = await cache.match(request);
        if (response === undefined) {
          continue;
        }
        total += (await response.clone().blob()).size;
      }
    }
  } catch {
    // Cache Storage 在某些隐私模式下会抛错：按 0 处理，不影响页面。
    return 0;
  }
  return total;
}

/**
 * 统计本项目缓存命名空间（`faze.cache.` 前缀）下本地键的字节数。
 *
 * @returns UTF-16 估算字节数（键 + 值，每字符 2 字节）
 */
function localCacheKeyBytes(): number {
  const store = localStore();
  if (store === null) {
    return 0;
  }
  let total = 0;
  try {
    for (let index = 0; index < store.length; index += 1) {
      const key = store.key(index);
      if (key === null || !key.startsWith(CACHE_KEY_PREFIX)) {
        continue;
      }
      total += (key.length + (store.getItem(key)?.length ?? 0)) * 2;
    }
  } catch {
    return 0;
  }
  return total;
}

/**
 * 原生端的缓存目录字节数。
 *
 * 项目未安装 `expo-file-system`，无法遍历 `cacheDirectory`；这里如实返回 0，
 * 不编造数字（编造会让「清除缓存」变成一个骗人的按钮）。
 *
 * @returns 恒为 0（段 8 装上 expo-file-system 后替换实现）
 */
function nativeCacheBytes(): number {
  return 0;
}

/**
 * 读缓存基线（上次清除后重新开始累计的字节数）。
 *
 * @returns 基线字节数；未设置时为 0
 */
async function readCacheBaseline(): Promise<number> {
  const raw = await readRaw(SETTINGS_KEYS.cacheBaseline);
  if (raw === null) {
    return 0;
  }
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? value : 0;
}

/**
 * 测量当前缓存大小（字节）。
 *
 * 公式：`max(0, 可测总量 - 上次清除时的基线)`。
 * 用基线而不是「删干净再测」的原因：Web 上资源缓存由浏览器管理、JS 删不掉，
 * 没有基线就会出现「点了清除、数字一点没变」——那比不显示数字更糟。
 *
 * @returns 缓存字节数（非负整数）
 */
export async function measureCacheBytes(): Promise<number> {
  const measured = webResourceBytes() + (await webCacheStorageBytes()) + localCacheKeyBytes() + nativeCacheBytes();
  return Math.max(0, measured - (await readCacheBaseline()));
}

/**
 * 清除缓存。
 *
 * 只清「可再生成的临时数据」：Cache Storage、`faze.cache.` 前缀的本地键。
 * **不动** token、语言、字体/深色设置、文书草稿（工作流验收点）。
 *
 * 副作用：删除浏览器 Cache Storage 与本地缓存键，并把基线对齐到当前可测总量，
 * 因此紧接着的 `measureCacheBytes()` 返回 0。
 *
 * @returns 清除后的缓存字节数（应为 0）
 */
export async function clearAppCache(): Promise<number> {
  // 1) 清 Cache Storage（Web）
  if (typeof caches !== 'undefined') {
    try {
      const names = await caches.keys();
      for (const name of names) {
        await caches.delete(name);
      }
    } catch {
      // 隐私模式下可能拒绝：忽略，继续清本地键。
    }
  }

  // 2) 清 `faze.cache.` 前缀的本地键（原生端清内存里的同名键）
  const store = localStore();
  if (store !== null) {
    try {
      const cacheKeys: string[] = [];
      for (let index = 0; index < store.length; index += 1) {
        const key = store.key(index);
        if (key !== null && key.startsWith(CACHE_KEY_PREFIX)) {
          cacheKeys.push(key);
        }
      }
      for (const key of cacheKeys) {
        store.removeItem(key);
      }
    } catch {
      // 同上：清不掉不阻塞后续步骤。
    }
  }
  const memoryKeys = Array.from(memoryStore.keys()).filter((key) => key.startsWith(CACHE_KEY_PREFIX));
  for (const key of memoryKeys) {
    memoryStore.delete(key);
  }

  // 3) 基线对齐：之后新加载的资源才重新计入，因此「清除后」读数为 0。
  const totalAfterClean = webResourceBytes() + (await webCacheStorageBytes()) + localCacheKeyBytes() + nativeCacheBytes();
  await writeRaw(SETTINGS_KEYS.cacheBaseline, String(totalAfterClean));
  return measureCacheBytes();
}

/**
 * 把字节数格式化成用户可读的大小（设置行右侧的「约 12 MB」）。
 *
 * 规则：≥1 MB 保留 1 位小数；≥1 KB 取整 KB；否则显示 0 B。
 *
 * @param bytes 字节数
 * @returns 形如 `12.4 MB` / `860 KB` / `0 B`
 */
export function formatCacheSize(bytes: number): string {
  const safe = Number.isFinite(bytes) && bytes > 0 ? bytes : 0;
  if (safe >= 1024 * 1024) {
    return `${(safe / (1024 * 1024)).toFixed(1)} MB`;
  }
  if (safe >= 1024) {
    return `${Math.round(safe / 1024)} KB`;
  }
  return '0 B';
}
