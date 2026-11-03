/**
 * 语言码、语言表与初始语言探测。
 *
 * 依据：i18n 规范 §2（语言清单与方向）、§2.3（语言状态单一来源）。
 * 调用方：`shared/i18n/index.ts`（Provider 初始化）、`app/language.tsx`（切换页）。
 * 注意：
 *   1. **对外一律用连字符**（`ug-Arab-CN`）；下划线写法只在 Java `Locale` 内部出现。
 *   2. 只有 `ug-Arab-CN` 与 `kk-Arab-CN` 是 RTL；**藏文 `bo` 是 LTR**——
 *      「非中文即 RTL」是这类系统最常见的低级错误（`0-A3` 专门验这一条）。
 *   3. 不引 `Intl`：技术路线 §13.7.1 已裁决不用（Hermes 的 locale 数据覆盖不确定，
 *      `kk_Arab`/`ug_Arab` 缺失时会静默回退英文）。
 */

import { getLocales } from 'expo-localization';

/** 支持的语言码（与后端 `app_user.lang` 同值）。 */
export type Lang = 'zh-Hans' | 'bo' | 'ug-Arab-CN' | 'kk-Arab-CN';

/** 排版方向。 */
export type LangDir = 'ltr' | 'rtl';

/** 单一语言的定义。 */
export type LangInfo = {
  /** 语言码（对外用连字符）。 */
  code: Lang;
  /** 母语名（语言选择页展示，**不翻译**）。 */
  nativeName: string;
  /** 排版方向。 */
  dir: LangDir;
};

/** 语言表（顺序即语言切换页的展示顺序）。 */
export const LANGS: readonly LangInfo[] = [
  { code: 'zh-Hans', nativeName: '简体中文', dir: 'ltr' },
  { code: 'bo', nativeName: 'བོད་ཡིག', dir: 'ltr' },
  { code: 'ug-Arab-CN', nativeName: 'ئۇيغۇرچە', dir: 'rtl' },
  { code: 'kk-Arab-CN', nativeName: 'قازاق تىلى', dir: 'rtl' },
] as const;

/** 默认语言（探测失败时兜底）。 */
export const DEFAULT_LANG: Lang = 'zh-Hans';

/**
 * 判断任意字符串是否是受支持的语言码。
 *
 * @param value 待判断值
 * @returns 是则收窄为 `Lang`
 */
export function isLang(value: string): value is Lang {
  return LANGS.some((item) => item.code === value);
}

/**
 * 取语言方向。
 *
 * @param lang 语言码
 * @returns `rtl` 或 `ltr`
 */
export function dirOf(lang: Lang): LangDir {
  return LANGS.find((item) => item.code === lang)?.dir ?? 'ltr';
}

/**
 * 按系统语言探测初始语言（只在**首次启动**用）。
 *
 * 匹配规则按语言前缀：`zh*` → 简体中文，`bo*` → 藏文，`ug*` → 维吾尔文，`kk*` → 哈萨克文。
 * 没有匹配时回 `zh-Hans`。**不跟随系统实时切换**（i18n 规范 §2.3）。
 *
 * @returns 初始语言
 */
export function detectSystemLang(): Lang {
  const locales = getLocales();
  const first = locales[0];
  if (!first) {
    return DEFAULT_LANG;
  }
  const tag = (first.languageTag ?? first.languageCode ?? '').toLowerCase();
  if (tag.startsWith('zh')) {
    return 'zh-Hans';
  }
  if (tag.startsWith('bo')) {
    return 'bo';
  }
  if (tag.startsWith('ug')) {
    return 'ug-Arab-CN';
  }
  if (tag.startsWith('kk')) {
    return 'kk-Arab-CN';
  }
  return DEFAULT_LANG;
}
