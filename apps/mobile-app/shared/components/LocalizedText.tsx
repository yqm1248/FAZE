/**
 * 按 Unicode 区段切 run、逐 run 指定字体的文本组件。
 *
 * 依据：技术路线 §13.3.1（区段判定）、i18n 规范 §7.1~7.2（行高与折行）。
 * 调用方：全仓所有用户可见文本（`<LtrText>` 内部也经它渲染）。
 * 注意：
 *   1. **本文件与 `LtrText.tsx` 是全仓唯一允许直接 import RN `Text` 的地方**
 *      （`no-restricted-imports` 的豁免块里显式列出这两个文件）。
 *   2. `numberOfLines` **必须显式传**：阿拉伯系的连写文字在自动折行时会把字母断开，
 *      必须由调用方给出行数上限（i18n 规范 §7.1）。
 *   3. 字体族名与 D2 加载的字体文件保持一致；段 0 字体文件未落地（D2 才装），
 *      未加载时 RN 会自动回退系统字体——**这是预期行为，不是 bug**。
 *   4. 不切方向、不管 `t()`：方向归 `LtrText` 与 `I18nManager`，文案归 `useI18n().t()`。
 */

import { Text } from 'react-native';
import type { StyleProp, TextProps, TextStyle } from 'react-native';

/** 藏文字体族名（D2 落地字体文件后与此一致）。 */
const FONT_TIBETAN = 'NotoSansTibetan';
/** 阿拉伯系字体族名（维吾尔文 / 哈萨克文共用）。 */
const FONT_ARABIC = 'NotoNaskhArabic';

/** 文本片段所属书写系统。 */
type ScriptKind = 'tibetan' | 'arabic' | 'default';

/** 一个文本 run。 */
type TextRun = {
  /** 片段内容。 */
  text: string;
  /** 所属书写系统。 */
  script: ScriptKind;
};

/** 藏文区段：U+0F00–U+0FFF。 */
const TIBETAN_RE = /[\u0F00-\u0FFF]/;
/** 阿拉伯系区段：U+0600–U+06FF 与 U+0750–U+077F。 */
const ARABIC_RE = /[\u0600-\u06FF\u0750-\u077F]/;

/**
 * 判断单个字符的书写系统。
 *
 * @param char 单字符
 * @returns 书写系统
 */
function scriptOf(char: string): ScriptKind {
  if (TIBETAN_RE.test(char)) {
    return 'tibetan';
  }
  if (ARABIC_RE.test(char)) {
    return 'arabic';
  }
  return 'default';
}

/**
 * 把字符串切成同书写系统的连续片段。
 *
 * @param input 原文
 * @returns 片段数组（至少一个元素；空串返回空数组）
 */
function splitRuns(input: string): TextRun[] {
  const runs: TextRun[] = [];
  let buffer = '';
  let current: ScriptKind | null = null;

  for (const char of Array.from(input)) {
    const script = scriptOf(char);
    if (current === null) {
      current = script;
      buffer = char;
      continue;
    }
    if (script === current) {
      buffer += char;
      continue;
    }
    runs.push({ text: buffer, script: current });
    current = script;
    buffer = char;
  }
  if (current !== null && buffer !== '') {
    runs.push({ text: buffer, script: current });
  }
  return runs;
}

/**
 * 取某个书写系统的字体样式。
 *
 * @param script 书写系统
 * @returns 样式对象；系统字体返回 undefined
 */
function fontStyleOf(script: ScriptKind): TextStyle | undefined {
  if (script === 'tibetan') {
    return { fontFamily: FONT_TIBETAN };
  }
  if (script === 'arabic') {
    return { fontFamily: FONT_ARABIC };
  }
  return undefined;
}

/** `LocalizedText` 的属性。 */
export type LocalizedTextProps = Omit<TextProps, 'numberOfLines'> & {
  /** 行数上限（**必填**，理由见文件头注释第 2 条）。 */
  numberOfLines: number;
  /** 文本内容（字符串或数字）。 */
  children: string | number;
};

/**
 * 按书写系统渲染文本。
 *
 * 副作用：无（纯渲染）。
 *
 * @param props 文本属性
 * @returns 文本节点；混排时按片段切分并分别指定字体
 */
export function LocalizedText({ children, style, numberOfLines, ...rest }: LocalizedTextProps) {
  const text = String(children);
  const runs = splitRuns(text);

  // 单字体场景直接渲染，避免无意义的多层嵌套。
  if (runs.length <= 1) {
    const single = runs[0];
    const fontStyle = single === undefined ? undefined : fontStyleOf(single.script);
    return (
      <Text style={[style, fontStyle]} numberOfLines={numberOfLines} {...rest}>
        {text}
      </Text>
    );
  }

  return (
    <Text style={style} numberOfLines={numberOfLines} {...rest}>
      {runs.map((run, index) => (
        // 片段没有稳定 id，用下标；文本内容变化时整段重渲染，不会造成状态错乱。
        <Text key={`${run.script}-${index}`} style={fontStyleOf(run.script)}>
          {run.text}
        </Text>
      ))}
    </Text>
  );
}

/** 供调用方复用的样式类型别名（避免各处从 react-native 直接引 TextStyle）。 */
export type LocalizedTextStyle = StyleProp<TextStyle>;
