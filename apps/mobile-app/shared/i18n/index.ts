/**
 * i18n 底座：Provider、`t()`、语言切换。
 *
 * 依据：i18n 规范 §2.3（语言状态单一来源）、§3.1~3.2（key 规则）、§5（缺译降级）。
 * 调用方：`app/_layout.tsx`（挂载 Provider）、各页面（`useI18n()`）。
 * 注意：
 *   1. **自研而不是 i18next**：段 0~8 用不到命名空间/上下文/复数/后端加载；
 *      复数若走 `Intl.PluralRules` 会重新打开「Hermes locale 数据不全静默回退英文」的风险。
 *      退出成本被 `t()` 单一入口隔离在 `index.ts` 一个文件。
 *   2. **缺 key 在 dev 下返回 `⟦key⟧` 并 `console.warn`**，生产返回 `common.fallbackText`。
 *      绝不返回空串——空串会变成「界面上什么都没有」这种最难定位的问题。
 *   3. 占位符 `{{camelCase}}` 用正则替换；缺变量时 dev 下 warn，不抛异常。
 *   4. 页面只 `useI18n()`，**不许自己读 SecureStore**（语言状态单一来源）。
 */

import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

import { readStoredLang, writeStoredLang } from '@/shared/api/tokenStore';

import { DEFAULT_LANG, detectSystemLang, dirOf, isLang } from './lang';
import type { Lang } from './lang';
import { ZH_HANS, ZH_HANS_ERROR } from './resources';
import { applyDirection } from './rtl';

/** 扁平化后的一层 key 表（如 `common.state.loading`）。 */
type FlatTree<T, Prefix extends string = ''> = {
  [K in keyof T & string]: T[K] extends string
    ? `${Prefix}${K}`
    : FlatTree<T[K], `${Prefix}${K}.`>;
}[keyof T & string];

/** 正常 namespace 的 key（带 namespace 前缀）。 */
export type NamespacedKey = FlatTree<typeof ZH_HANS>;

/** error 域的 key（本身即完整 msgKey）。 */
export type ErrorKey = keyof typeof ZH_HANS_ERROR;

/** 所有合法文案 key。**写错一个字母 `tsc` 就会报错。** */
export type MessageKey = NamespacedKey | ErrorKey;

/** 支持的语言码类型（页面用它标注参数）。 */
export type { Lang };

/** 占位符变量表。 */
export type TranslationVars = Record<string, string | number>;

/** `useI18n()` 的返回值。 */
export type I18nContextValue = {
  /** 当前语言。 */
  lang: Lang;
  /** 当前方向。 */
  dir: 'ltr' | 'rtl';
  /** 语言状态是否已从存储读回（未就绪时页面应显示加载态）。 */
  ready: boolean;
  /**
   * 翻译。
   *
   * @param key  文案 key（编译期校验）
   * @param vars 占位符变量
   * @returns 翻译结果；缺译时按降级链给中文，最后兜底 fallbackText
   */
  t: (key: MessageKey, vars?: TranslationVars) => string;
  /**
   * 切换语言。同向直接重渲染；异向走 `applyDirection` 的软重启链路。
   *
   * @param next 目标语言
   * @returns 是否触发了重启（异向时可能为 true）
   */
  changeLang: (next: Lang) => Promise<boolean>;
};

const I18nContext = createContext<I18nContextValue | null>(null);

/** 生产环境缺 key 的兜底文案 key（`common.fallbackText`）。 */
const FALLBACK_KEY = 'common.fallbackText' as NamespacedKey;

/**
 * 按点号路径在对象树里取值。
 *
 * @param tree 资源树
 * @param path 路径段
 * @returns 命中且为字符串时返回值，否则 undefined
 */
function lookup(tree: unknown, path: string[]): string | undefined {
  let current: unknown = tree;
  for (const segment of path) {
    if (typeof current !== 'object' || current === null) {
      return undefined;
    }
    current = (current as Record<string, unknown>)[segment];
  }
  return typeof current === 'string' ? current : undefined;
}

/**
 * 在 zh-Hans 资源里查 key。
 *
 * error 域的 key 是完整 msgKey，其余 key 的第一段是 namespace。
 *
 * @param key 文案 key
 * @returns 中文文案；未找到时 undefined
 */
function lookupZhHans(key: string): string | undefined {
  if (key.startsWith('error.')) {
    const direct = (ZH_HANS_ERROR as Record<string, string>)[key];
    if (direct !== undefined) {
      return direct;
    }
  }
  const [namespace, ...rest] = key.split('.');
  if (!namespace || rest.length === 0) {
    return undefined;
  }
  const tree = (ZH_HANS as Record<string, unknown>)[namespace];
  if (tree === undefined) {
    return undefined;
  }
  return lookup(tree, rest);
}

/**
 * 替换 `{{var}}` 占位符。
 *
 * @param template 模板字符串
 * @param vars     变量表
 * @param key      原始 key（仅用于告警）
 * @returns 替换后的字符串；缺变量时保留原占位符并告警
 */
function interpolate(template: string, vars: TranslationVars | undefined, key: string): string {
  if (!vars) {
    return template;
  }
  return template.replace(/\{\{(\w+)\}\}/g, (match, name: string) => {
    const value = vars[name];
    if (value === undefined) {
      if (__DEV__) {
        console.warn(`[i18n] t('${key}') 缺少占位符变量 {{${name}}}`);
      }
      return match;
    }
    return String(value);
  });
}

/**
 * i18n Provider。
 *
 * 副作用：首次挂载时从 SecureStore 读语言；读不到则按系统语言探测。
 *
 * @param props.children 子树
 * @returns Provider 元素
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(DEFAULT_LANG);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const stored = await readStoredLang();
        const initial = stored !== null && isLang(stored) ? stored : detectSystemLang();
        if (!cancelled) {
          setLangState(initial);
        }
      } catch {
        // 读存储失败按默认语言继续：语言读不到不该让 App 起不来。
        if (!cancelled) {
          setLangState(DEFAULT_LANG);
        }
      } finally {
        if (!cancelled) {
          setReady(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const t = useCallback(
    (key: MessageKey, vars?: TranslationVars): string => {
      // 段 0 只有 zh-Hans 有内容，其余语言逐级降级到它。
      const zh = lookupZhHans(key);
      if (zh !== undefined) {
        return interpolate(zh, vars, key);
      }
      if (__DEV__) {
        console.warn(`[i18n] 缺 key: ${key}`);
        return `⟦${key}⟧`;
      }
      return lookupZhHans(FALLBACK_KEY) ?? '内容暂时无法显示';
    },
    [],
  );

  const changeLang = useCallback(
    async (next: Lang): Promise<boolean> => {
      if (next === lang) {
        return false;
      }
      // 先落盘：异向分支要重启，重启后靠这个值恢复语言。
      await writeStoredLang(next);
      const reloading = await applyDirection(next, lang);
      if (!reloading) {
        setLangState(next);
      }
      return reloading;
    },
    [lang],
  );

  const value = useMemo<I18nContextValue>(
    () => ({ lang, dir: dirOf(lang), ready, t, changeLang }),
    [lang, ready, t, changeLang],
  );

  // 用 createElement 而不是 JSX：本文件必须保持 .ts 扩展名（文档规定的路径），
  // 而 .ts 不允许 JSX 语法。
  return createElement(I18nContext.Provider, { value }, children);
}

/**
 * 取 i18n 上下文。
 *
 * @returns 当前语言、方向、`t()`、`changeLang()`
 * @throws Error 在 `I18nProvider` 之外调用时抛出（属于装配错误，应立刻暴露）
 */
export function useI18n(): I18nContextValue {
  const context = useContext(I18nContext);
  if (context === null) {
    throw new Error('useI18n 必须在 <I18nProvider> 内使用');
  }
  return context;
}
