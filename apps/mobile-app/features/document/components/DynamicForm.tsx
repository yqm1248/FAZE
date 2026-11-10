/**
 * 动态表单（E2 / E3 共用）与文书表单页壳（DocumentFormScreen）。
 *
 * 职责：
 *   1. 把后端 `field_schema` 渲染成表单——6 种字段类型（text / textarea / number / date /
 *      select / array），不写死任何业务字段；`LAWSUIT-*` 与其它模板走同一份实现；
 *   2. 校验（必填、手机号、数字、日期、array 条数）、array 的增删（受 minItems / maxItems 约束）；
 *   3. `DocumentFormScreen` 是 E2 / E3 共用的页面壳：拉模板 + 回填草稿、5 秒防抖自动存草稿、
 *      离开页面立即存一次、「立即生成」投递后跳预览页。
 * 调用方：`features/document/pages/DocLawsuitFormPage.tsx`（E2）、
 *         `features/document/pages/DocFormPage.tsx`（E3）。
 * 数据来源：`features/document/api/client.ts` 的 `fetchTemplates` / `fetchDocument` /
 *          `saveDraft` / `generateDocument` / `parseFieldSchema` / `parseFormData`。
 * 注意：
 *   1. **schema 形状以后端为准**：字段名是 `key`、array 子项在 `itemSchema.fields`
 *      （`services/faze-api/.../document/bo/FieldSchema.java:137-174`）。归一化以 `key` 为主路径，
 *      兼容旧形状（`name` / `fields`）只是防御，不要反过来。
 *   2. **form_data 形状**：`{ [key]: "文本" | [{ [subKey]: "文本" }] }`——array 的值是对象数组
 *      （后端模板循环块依赖这个形状），提交前必须 `JSON.stringify`。
 *   3. 校验文案不在这里拼：校验返回「文案 key + 变量」，渲染层再 `t()`，四语言口径统一。
 *   4. 自动保存只在用户改动后触发（回填不触发）；离开页面用 ref 取最新值，避免闭包拿到旧值。
 *   5. 表单值受控于本文件的页面壳：`DynamicForm` 只渲染，`onChange` 收整张 `FormValues`。
 *   6. 视觉数值全部来自 `tokens`；RTL 只用 `start/end`，不写物理方向。
 *   7. **Web 的焦点环由本文件接管**（2026-11-09 实测问题）：浏览器默认在输入框上画黑色
 *      focus ring，盖住圆角灰底容器、看起来「边框和灰底不贴合」。做法是用 `outlineStyle: 'solid'`
 *      + `outlineWidth: 0` 去掉输入控件自身的轮廓（UA 的 `auto` 会忽略宽度，必须覆盖样式，
 *      见 `WEB_FOCUS_RESET` 注释），把聚焦态换成外层灰底容器 `inputRow` 的主色细边框。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { createElement, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AppState,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
  type TextStyle,
} from 'react-native';

import {
  fetchDocument,
  fetchTemplates,
  generateDocument,
  parseFieldSchema,
  parseFormData,
  saveDraft,
} from '@/features/document/api/client';
import { toRmbUpper } from '@/features/document/utils/rmbUpper';
import type {
  DocumentDetail,
  DocTemplateBrief,
  FieldSchema,
  FieldType,
} from '@/features/document/api/client';
import type { ApiResult } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 自动保存的防抖时长（毫秒）。工作流 E2 第 3 条：改动后 5 秒。 */
const AUTOSAVE_DELAY_MS = 5000;

/** array 字段缺省上限：schema 没给 `maxItems` 时按 20 条封顶，避免无限添加。 */
const DEFAULT_MAX_ITEMS = 20;

/** 允许的字段类型（与后端 `FieldSchema.ALLOWED_TYPES` 一致）。 */
const ALLOWED_TYPES: readonly FieldType[] = ['text', 'textarea', 'number', 'date', 'select', 'array'];

/** 把「路径 → 校验问题」收成一张表；路径形如 `court_name` 或 `plaintiffs.0.name`。 */
type ValidationErrorMap = Record<string, ValidationIssue>;

/** 一个表单值：标量是字符串，array 是对象数组（与后端 form_data 一致）。 */
export type FieldValue = string | FormItem[];

/** array 的一行（子字段 key → 文本）。 */
export type FormItem = Record<string, string>;

/** 整张表单的值。 */
export type FormValues = Record<string, FieldValue>;

/** 归一后的字段定义（渲染层只认这个，不直接碰后端原始 JSON）。 */
export type DynamicField = {
  /** 字段名（= form_data 的属性名 = 模板占位符 key）。 */
  name: string;
  /** 展示标签。 */
  label: string;
  /** 字段类型。 */
  type: FieldType;
  /** 是否必填。 */
  required: boolean;
  /** 输入提示（可空）。 */
  placeholder: string | null;
  /** `select` 的选项。 */
  options: string[];
  /** `array` 的子项字段（一层）。 */
  itemFields: DynamicField[];
  /** `array` 最少条数，默认 0。 */
  minItems: number;
  /** `array` 最多条数；schema 未给时按 20 封顶（最小 1）。 */
  maxItems: number;
  /** 文本最大长度（可空）。 */
  maxLength: number | null;
  /**
   * 是否在表单里隐藏。
   *
   * 隐藏字段是**派生字段**：模板里要它（`{{key}}` 必须能在 schema 里找到，否则
   * `TemplateRenderer` 抛「模板引用了未定义的字段」），但不该让用户手填——
   * 典型例子是借条的「借款金额（大写）」，由小写金额自动算出来。
   * 隐藏字段不渲染、不参与表单校验，提交前由 `applyDerivedFields` 算好塞进 form_data。
   */
  hidden: boolean;
  /** 派生来源字段名（`hidden` 为 true 时有意义）。 */
  derivedFrom: string | null;
  /** 派生算法名（当前只认 `rmbUpper`，未知值原样复制来源值）。 */
  derive: string | null;
  /** 输入框右侧的固定单位（如 `%`）；`null` 表示不显示。 */
  suffix: string | null;
  /** 数值格式约束：`money` = 金额（最多两位小数）。 */
  format: string | null;
};

/** 一条校验问题：文案 key + 占位符变量（渲染层再 `t()`）。 */
export type ValidationIssue = {
  /** 文案 key。 */
  key: MessageKey;
  /** 占位符变量。 */
  vars: Record<string, string | number>;
};

/** 整张表单的校验结果。 */
export type FormValidation = {
  /** 是否通过。 */
  ok: boolean;
  /** 字段路径 → 问题。 */
  errors: ValidationErrorMap;
  /** 缺失的必填字段展示名（用于按钮上方的汇总提示）。 */
  missingLabels: string[];
};

/** 原始 JSON 对象的宽松视图（只在本文件的归一化里用）。 */
type RawRecord = Record<string, unknown>;

/**
 * 把 unknown 收窄成普通对象。
 *
 * @param input 任意值
 * @returns 对象视图；数组 / null / 标量返回 null
 */
function asRecord(input: unknown): RawRecord | null {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return null;
  }
  return input as RawRecord;
}

/**
 * 读字符串属性。
 *
 * @param record 对象
 * @param key    属性名
 * @returns 字符串值；非字符串返回 null
 */
function readString(record: RawRecord, key: string): string | null {
  const value = record[key];
  return typeof value === 'string' ? value : null;
}

/**
 * 读有限数字属性。
 *
 * @param record 对象
 * @param key    属性名
 * @returns 数字；非有限数字返回 null
 */
function readNumber(record: RawRecord, key: string): number | null {
  const value = record[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * 读布尔属性。
 *
 * @param record 对象
 * @param key    属性名
 * @returns 布尔值；缺失时 false
 */
function readBoolean(record: RawRecord, key: string): boolean {
  return record[key] === true;
}

/**
 * 类型守卫：值是否为合法字段类型。
 *
 * @param value 原始字符串
 * @returns 是则收窄为 `FieldType`
 */
function isFieldType(value: string | null): value is FieldType {
  return value !== null && ALLOWED_TYPES.includes(value as FieldType);
}

/**
 * 类型守卫：过滤掉 null。
 *
 * @param value 可空值
 * @returns 是否非 null
 */
function isPresent<T>(value: T | null): value is T {
  return value !== null;
}

/**
 * 归一单个字段。
 *
 * 主路径读 `key` + `itemSchema.fields`（后端形状）；`name` / `fields` 仅作兼容回退。
 *
 * @param input 原始字段定义
 * @returns 归一字段；无法识别时 null
 */
function normalizeField(input: unknown): DynamicField | null {
  const record = asRecord(input);
  if (record === null) {
    return null;
  }
  const name = readString(record, 'key') ?? readString(record, 'name');
  if (name === null || name.trim() === '') {
    return null;
  }
  const rawType = readString(record, 'type');
  const type: FieldType = isFieldType(rawType) ? rawType : 'text';

  let itemFields: DynamicField[] = [];
  if (type === 'array') {
    const itemSchema = asRecord(record.itemSchema);
    const fromItemSchema = itemSchema === null ? undefined : itemSchema.fields;
    const legacy = record.fields;
    const rawChildren = Array.isArray(fromItemSchema)
      ? fromItemSchema
      : Array.isArray(legacy)
        ? legacy
        : [];
    itemFields = rawChildren.map(normalizeField).filter(isPresent);
  }

  const rawOptions = record.options;
  const options = Array.isArray(rawOptions)
    ? rawOptions.filter((item): item is string => typeof item === 'string')
    : [];
  const placeholder = readString(record, 'placeholder');
  const maxLength = readNumber(record, 'maxLength');
  const minItemsRaw = readNumber(record, 'minItems');
  const maxItemsRaw = readNumber(record, 'maxItems');

  return {
    name,
    label: readString(record, 'label') ?? name,
    type,
    required: readBoolean(record, 'required'),
    placeholder: placeholder === null || placeholder.trim() === '' ? null : placeholder,
    options,
    itemFields,
    minItems: type === 'array' ? Math.max(minItemsRaw ?? 0, 0) : 0,
    maxItems: type === 'array' ? Math.max(maxItemsRaw ?? DEFAULT_MAX_ITEMS, 1) : 0,
    maxLength: maxLength === null ? null : Math.max(maxLength, 0),
    hidden: readBoolean(record, 'hidden'),
    derivedFrom: readString(record, 'derivedFrom'),
    derive: readString(record, 'derive'),
    suffix: readString(record, 'suffix'),
    format: readString(record, 'format'),
  };
}

/**
 * 归一整份 `field_schema`。
 *
 * @param schema `parseFieldSchema` 的结果（可空）
 * @returns 字段列表（顺序与 schema 一致）
 */
export function normalizeFieldSchema(schema: FieldSchema | null): DynamicField[] {
  if (schema === null) {
    return [];
  }
  const root = asRecord(schema);
  if (root === null) {
    return [];
  }
  const fields = root.fields;
  if (!Array.isArray(fields)) {
    return [];
  }
  return fields.map(normalizeField).filter(isPresent);
}

/**
 * 造一条空的 array 行。
 *
 * @param itemFields 子字段定义
 * @returns 子字段全部为空串的一行
 */
export function createEmptyItem(itemFields: DynamicField[]): FormItem {
  const item: FormItem = {};
  for (const field of itemFields) {
    item[field.name] = '';
  }
  return item;
}

/**
 * 按 schema 造空表单。
 *
 * array 字段按 `minItems` 预置空行（最少 0 行）；其余字段为 `''`。
 *
 * @param fields 字段定义
 * @returns 空表单值
 */
export function createEmptyFormValues(fields: DynamicField[]): FormValues {
  const values: FormValues = {};
  for (const field of fields) {
    if (field.type === 'array') {
      values[field.name] = Array.from({ length: field.minItems }, () =>
        createEmptyItem(field.itemFields),
      );
    } else {
      values[field.name] = '';
    }
  }
  return values;
}

/**
 * 把服务端 `form_data` 回填到表单值。
 *
 * 只认 schema 里存在的字段；array 的行数若少于 `minItems` 会补空行（否则用户看到的是空卡）。
 *
 * @param fields 字段定义
 * @param stored `parseFormData` 的结果
 * @returns 回填后的表单值
 */
export function mergeFormValues(fields: DynamicField[], stored: Record<string, unknown>): FormValues {
  const values = createEmptyFormValues(fields);
  for (const field of fields) {
    const raw = stored[field.name];
    if (field.type === 'array') {
      if (!Array.isArray(raw)) {
        continue;
      }
      const rows: FormItem[] = [];
      for (const row of raw) {
        const record = asRecord(row);
        if (record === null) {
          continue;
        }
        const item = createEmptyItem(field.itemFields);
        for (const sub of field.itemFields) {
          const cell = record[sub.name];
          if (typeof cell === 'string') {
            item[sub.name] = cell;
          } else if (typeof cell === 'number') {
            item[sub.name] = String(cell);
          }
        }
        rows.push(item);
      }
      while (rows.length < field.minItems) {
        rows.push(createEmptyItem(field.itemFields));
      }
      values[field.name] = rows;
      continue;
    }
    if (typeof raw === 'string') {
      values[field.name] = raw;
    } else if (typeof raw === 'number') {
      values[field.name] = String(raw);
    }
  }
  return values;
}

/**
 * 判断字段是否按手机号校验。
 *
 * 不是写死模板字段：按 key 的命名约定识别（`phone` / `mobile` / `tel` 结尾），
 * 这样任何模板里的联系电话列都会即时校验，新增模板不用改这里。
 *
 * @param name 字段 key
 * @returns 是手机号类字段
 */
function isPhoneField(name: string): boolean {
  const lower = name.toLowerCase();
  return lower.includes('phone') || lower.includes('mobile') || lower.endsWith('tel');
}

/**
 * 校验一个标量值。
 *
 * @param field 字段定义
 * @param raw   用户输入的原始文本
 * @returns 问题；通过时 null
 */
function validateScalar(field: DynamicField, raw: string): ValidationIssue | null {
  const value = raw.trim();
  if (value === '') {
    return field.required
      ? { key: 'document.dynamic.required', vars: { label: field.label } }
      : null;
  }
  if (isPhoneField(field.name)) {
    return /^1[3-9]\d{9}$/.test(value) ? null : { key: 'document.dynamic.invalidPhone', vars: {} };
  }
  if (field.type === 'number' && !/^-?\d+(?:\.\d+)?$/.test(value)) {
    return { key: 'document.dynamic.invalidNumber', vars: {} };
  }
  // 金额：最多两位小数。拦住「12.345」这种输入——大写金额转换只到分，
  // 放过去会在生成时被服务端判「必填项未填写：借款金额（大写）」，报错点离输入点太远。
  if (field.format === 'money' && !/^\d+(?:\.\d{1,2})?$/.test(value)) {
    return { key: 'document.dynamic.invalidMoney', vars: {} };
  }
  if (field.type === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return { key: 'document.dynamic.invalidDate', vars: {} };
  }
  return null;
}

/**
 * 校验整张表单。
 *
 * @param fields 字段定义
 * @param values 表单值
 * @returns 是否通过、字段路径 → 问题、缺失的必填字段展示名
 */
export function validateFormValues(fields: DynamicField[], values: FormValues): FormValidation {
  const errors: ValidationErrorMap = {};
  const missingLabels: string[] = [];

  for (const field of fields) {
    // 派生字段由 applyDerivedFields 在提交前算好，不该按「用户没填」报必填
    if (field.hidden) {
      continue;
    }
    const value = values[field.name];
    if (field.type === 'array') {
      const rows: FormItem[] = Array.isArray(value) ? value : [];
      const filledRows = rows.filter((row) =>
        Object.values(row).some((cell) => cell.trim() !== ''),
      );
      if (field.required && filledRows.length === 0) {
        errors[field.name] = {
          key: 'document.dynamic.requiredArray',
          vars: { label: field.label, min: field.minItems },
        };
        missingLabels.push(field.label);
        continue;
      }
      if (rows.length < field.minItems) {
        errors[field.name] = {
          key: 'document.dynamic.requiredArray',
          vars: { label: field.label, min: field.minItems },
        };
        continue;
      }
      rows.forEach((row, index) => {
        for (const sub of field.itemFields) {
          const issue = validateScalar(sub, row[sub.name] ?? '');
          if (issue !== null) {
            errors[`${field.name}.${index}.${sub.name}`] = issue;
            if (sub.required && missingLabels.length < 3) {
              missingLabels.push(sub.label);
            }
          }
        }
      });
      continue;
    }
    const issue = validateScalar(field, typeof value === 'string' ? value : '');
    if (issue !== null) {
      errors[field.name] = issue;
      if (field.required) {
        missingLabels.push(field.label);
      }
    }
  }

  return { ok: Object.keys(errors).length === 0, errors, missingLabels };
}

/**
 * 计算派生字段的值。
 *
 * 派生字段（`hidden` + `derivedFrom`）不出现在表单里，但**模板占位符和服务端的必填校验
 * 都依赖它**：占位符在 schema 里找不到定义会抛「模板引用了未定义的字段」，值空着又会
 * 被判「必填项未填写」。所以保存草稿与生成前都必须按来源字段算出来。
 *
 * 当前只支持 `derive: 'rmbUpper'`（金额小写转大写，见 `utils/rmbUpper.ts`）；
 * 未知算法**原样复制来源值**——宁可给一个不完美的值，也不能让整单生成失败。
 *
 * @param fields 字段定义
 * @param values 用户填的表单值
 * @returns 带上派生字段的新值（不修改入参）
 */
export function applyDerivedFields(fields: DynamicField[], values: FormValues): FormValues {
  const derived = fields.filter((field) => field.hidden && field.derivedFrom !== null);
  if (derived.length === 0) {
    return values;
  }
  const next: FormValues = { ...values };
  for (const field of derived) {
    const source = values[field.derivedFrom ?? ''];
    const sourceText = typeof source === 'string' ? source : '';
    next[field.name] = field.derive === 'rmbUpper' ? toRmbUpper(sourceText) : sourceText;
  }
  return next;
}

/**
 * 把字段按「标量卡 / array 卡」分组（与设计稿的分组卡片一致）。
 *
 * 连续的非 array 字段合成一张「基本信息」卡；每个 array 字段单独一张卡。
 *
 * @param fields 字段定义
 * @returns 分组
 */
function groupFields(
  fields: DynamicField[],
): Array<{ kind: 'scalars'; fields: DynamicField[] } | { kind: 'array'; field: DynamicField }> {
  const groups: Array<
    { kind: 'scalars'; fields: DynamicField[] } | { kind: 'array'; field: DynamicField }
  > = [];
  let pending: DynamicField[] = [];
  for (const field of fields) {
    // 派生字段不在表单里出现（见 DynamicField.hidden 的说明）
    if (field.hidden) {
      continue;
    }
    if (field.type === 'array') {
      if (pending.length > 0) {
        groups.push({ kind: 'scalars', fields: pending });
        pending = [];
      }
      groups.push({ kind: 'array', field });
      continue;
    }
    pending.push(field);
  }
  if (pending.length > 0) {
    groups.push({ kind: 'scalars', fields: pending });
  }
  return groups;
}

/**
 * 去掉浏览器默认焦点轮廓。
 *
 * 为什么要同时写 `outlineStyle` 和 `outlineWidth`：Chrome 的 UA 焦点环是
 * `outline-style: auto`，而 **`auto` 会忽略 `outline-width`**（宽度由浏览器决定）——
 * 实测只写 `outlineWidth: 0` 时，真实鼠标点击后仍会画出黑色方框。所以把 `outlineStyle`
 * 显式改成 `'solid'`（覆盖 UA 的 `auto`）再配 `outlineWidth: 0`，浏览器就不画轮廓了。
 * 为什么不用 `outlineStyle: 'none'`：RN 0.81 的 `TextStyle` 只允许
 * `'solid' | 'dotted' | 'dashed'`（`StyleSheetTypes.d.ts:417`），没有 `'none'`，
 * 写 `'none'` 会被 TS 拒绝；`solid + 0` 等价于不画，且类型原生支持。
 * 原生端这两个属性不产生可见效果（宽度为 0）。
 *
 * 聚焦态改由外层灰底容器 `styles.inputRowFocused`（主色细边框）表达，见 `ScalarField`。
 */
const WEB_FOCUS_RESET: TextStyle = { outlineStyle: 'solid', outlineWidth: 0 };

/** Web 日期输入的属性类型（DOM 类型只在这个文件出现一次）。 */
type DateInputProps = React.InputHTMLAttributes<HTMLInputElement> & {
  style?: React.CSSProperties;
  'data-testid'?: string;
};

/**
 * Web 端日期选择器。
 *
 * 为什么不用 RN 组件：依赖里没有 datetimepicker，新增依赖需要登记（AGENTS §12）；
 * Web 验收下 `<input type="date">` 就是原生日期选择器，避免手输。
 * 原生端由 `ScalarField` 退回 TextInput。
 *
 * @param props.value    `YYYY-MM-DD`
 * @param props.onChange 变化回调
 * @param props.onFocus  获得焦点（用于把聚焦态交给外层灰底容器）
 * @param props.onBlur   失焦
 * @param props.disabled 是否禁用
 * @param props.label    无障碍标签
 * @param props.testID   自动化标识
 * @returns 受控 DOM input
 */
function WebDateInput({
  value,
  onChange,
  onFocus,
  onBlur,
  disabled,
  label,
  testID,
}: {
  value: string;
  onChange: (next: string) => void;
  onFocus: () => void;
  onBlur: () => void;
  disabled: boolean;
  label: string;
  testID: string;
}) {
  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    onChange(event.target.value);
  };
  const props: DateInputProps = {
    type: 'date',
    value,
    disabled,
    'aria-label': label,
    'data-testid': testID,
    onChange: handleChange,
    onFocus,
    onBlur,
    style: {
      minHeight: size.inputHeight,
      width: '100%',
      boxSizing: 'border-box',
      borderRadius: radius.sm,
      backgroundColor: color.background.input,
      color: color.text.body,
      border: 'none',
      // 原生日期控件自带浏览器焦点轮廓，与圆角灰底不贴合，这里统一去掉（聚焦态看外层容器）。
      outlineStyle: 'none',
      paddingInlineStart: spacing.md,
      paddingInlineEnd: spacing.md,
      fontSize: typography.body.fontSize,
      fontFamily: 'inherit',
    },
  };
  return createElement('input', props);
}

/**
 * 单个标量字段（标签 + 控件 + 错误）。
 *
 * @param props.field    归一字段
 * @param props.value    当前文本值
 * @param props.error    已翻译的错误文案（null 表示不显示）
 * @param props.disabled 是否禁用
 * @param props.testIDPrefix 自动化前缀
 * @param props.onChange 值变化
 * @param props.onBlur   失焦（用于把字段标成 touched）
 * @returns 一行表单控件
 */
function ScalarField({
  field,
  value,
  error,
  disabled,
  testIDPrefix,
  onChange,
  onBlur,
}: {
  field: DynamicField;
  value: string;
  error: string | null;
  disabled: boolean;
  testIDPrefix: string;
  onChange: (next: string) => void;
  onBlur: () => void;
}) {
  const { t } = useI18n();
  const [focused, setFocused] = useState(false);
  const fieldTestID = `${testIDPrefix}-field-${field.name}`;
  const inputTestID = `${testIDPrefix}-input-${field.name}`;
  // 文本类没有 schema 提示时用「请输入<标签>」，不用「请选择」——「请选择」是 select 的措辞。
  const placeholder = field.placeholder ?? t('document.dynamic.enterPlaceholder', {
    label: field.label,
  });
  const labelText = field.required
    ? `${field.label} ${t('document.dynamic.requiredMark')}`
    : field.label;

  const renderControl = () => {
    if (field.type === 'select') {
      return (
        <View style={styles.optionRow}>
          {field.options.map((option, index) => {
            const active = value === option;
            return (
              <Pressable
                key={option}
                testID={`${fieldTestID}-option-${index}`}
                onPress={() => {
                  onChange(option);
                  onBlur();
                }}
                disabled={disabled}
                accessibilityRole="button"
                accessibilityState={{ selected: active, disabled }}
                accessibilityLabel={option}
                style={[styles.optionChip, active ? styles.optionChipActive : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.optionText, active ? styles.optionTextActive : null]}
                >
                  {option}
                </LocalizedText>
              </Pressable>
            );
          })}
        </View>
      );
    }
    if (field.type === 'date') {
      if (Platform.OS === 'web') {
        return (
          <WebDateInput
            value={value}
            onChange={(next) => {
              onChange(next);
              onBlur();
            }}
            onFocus={() => setFocused(true)}
            onBlur={() => {
              setFocused(false);
              onBlur();
            }}
            disabled={disabled}
            label={labelText}
            testID={inputTestID}
          />
        );
      }
      return (
        <TextInput
          testID={inputTestID}
          value={value}
          onChangeText={onChange}
          onFocus={() => setFocused(true)}
          onBlur={() => {
            setFocused(false);
            onBlur();
          }}
          placeholder={t('document.dynamic.datePlaceholder')}
          accessibilityLabel={labelText}
          editable={!disabled}
          style={styles.input}
          placeholderTextColor={color.text.muted}
        />
      );
    }
    const multiline = field.type === 'textarea';
    return (
      <TextInput
        testID={inputTestID}
        value={value}
        onChangeText={onChange}
        onFocus={() => setFocused(true)}
        onBlur={() => {
          setFocused(false);
          onBlur();
        }}
        placeholder={field.placeholder ?? placeholder}
        accessibilityLabel={labelText}
        keyboardType={field.type === 'number' ? 'numeric' : 'default'}
        maxLength={field.maxLength ?? undefined}
        multiline={multiline}
        editable={!disabled}
        style={[
          styles.input,
          multiline ? styles.inputMultiline : null,
          Platform.OS === 'web' ? WEB_FOCUS_RESET : null,
        ]}
        placeholderTextColor={color.text.muted}
      />
    );
  };

  return (
    <View style={styles.field} testID={fieldTestID}>
      <LocalizedText numberOfLines={2} style={styles.fieldLabel}>
        {labelText}
      </LocalizedText>
      <View
        style={[
          styles.inputRow,
          field.type === 'textarea' ? styles.inputRowMultiline : null,
          focused ? styles.inputRowFocused : null,
        ]}
      >
        {renderControl()}
        {field.suffix === null ? null : (
          <LocalizedText numberOfLines={1} style={styles.suffixText} testID={`${fieldTestID}-suffix`}>
            {field.suffix}
          </LocalizedText>
        )}
      </View>
      {error === null ? null : (
        <LocalizedText numberOfLines={2} style={styles.fieldError} testID={`${fieldTestID}-error`}>
          {error}
        </LocalizedText>
      )}
    </View>
  );
}

/**
 * 动态表单渲染器（受控）。
 *
 * @param props.fields       归一字段
 * @param props.values       当前值
 * @param props.errors       需要显示的错误（路径 → 问题）
 * @param props.disabled     是否禁用（提交中）
 * @param props.testIDPrefix 自动化前缀（E2 = `lawsuit`，E3 = `form`）
 * @param props.onChange     整张表单值变化
 * @param props.onBlur       字段失焦（路径）
 * @returns 分组后的表单卡片
 */
export function DynamicForm({
  fields,
  values,
  errors,
  disabled = false,
  testIDPrefix,
  onChange,
  onBlur,
}: {
  fields: DynamicField[];
  values: FormValues;
  errors: ValidationErrorMap;
  disabled?: boolean;
  testIDPrefix: string;
  onChange: (next: FormValues) => void;
  onBlur: (path: string) => void;
}) {
  const { t } = useI18n();
  const groups = useMemo(() => groupFields(fields), [fields]);
  /**
   * 第一个标量组的组下标。
   *
   * 只有它显示「基本信息」小标题：schema 里数组字段之间的标量（例如起诉状的
   * 「事实与理由 / 具状日期」）会形成第二个标量组，若也标「基本信息」会出现两张同名卡。
   */
  const firstScalarsIndex = groups.findIndex((group) => group.kind === 'scalars');

  /**
   * 把校验问题翻成文案。
   *
   * @param path 字段路径
   * @returns 文案；无问题或不需要显示时 null
   */
  const errorTextOf = (path: string): string | null => {
    const issue = errors[path];
    return issue === undefined ? null : t(issue.key, issue.vars);
  };

  /**
   * 更新一个标量字段。
   *
   * @param name  字段名
   * @param value 新值
   */
  const setScalar = (name: string, value: string) => {
    onChange({ ...values, [name]: value });
  };

  /**
   * 更新 array 某一行的一个子字段。
   *
   * @param fieldName array 字段名
   * @param index     行号
   * @param subName   子字段名
   * @param value     新值
   */
  const setItemCell = (fieldName: string, index: number, subName: string, value: string) => {
    const current = values[fieldName];
    const rows: FormItem[] = Array.isArray(current) ? [...current] : [];
    const row = rows[index];
    if (row === undefined) {
      return;
    }
    rows[index] = { ...row, [subName]: value };
    onChange({ ...values, [fieldName]: rows });
  };

  return (
    <View testID={`${testIDPrefix}-dynamic-form`}>
      {groups.map((group, groupIndex) => {
        if (group.kind === 'array') {
          const field = group.field;
          const current = values[field.name];
          const rows: FormItem[] = Array.isArray(current) ? current : [];
          const canAdd = rows.length < field.maxItems;
          const canRemove = rows.length > field.minItems;
          return (
            <View key={field.name} style={styles.card} testID={`${testIDPrefix}-array-${field.name}`}>
              <View style={styles.cardHead}>
                <View style={styles.cardBar} />
                <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                  {field.label}
                </LocalizedText>
                <LtrText numberOfLines={1} style={styles.cardCounter}>
                  {t('document.dynamic.counter', {
                    current: rows.length,
                    max: field.maxItems,
                  })}
                </LtrText>
              </View>

              {rows.length === 0 ? (
                <LocalizedText numberOfLines={1} style={styles.arrayEmpty}>
                  {t('document.dynamic.arrayEmpty')}
                </LocalizedText>
              ) : null}

              {rows.map((row, index) => {
                const itemKey = `${field.name}-${index}`;
                return (
                  <View key={itemKey} style={styles.itemBlock} testID={`${testIDPrefix}-item-${itemKey}`}>
                    <View style={styles.itemHead}>
                      <LocalizedText numberOfLines={1} style={styles.itemTitle}>
                        {t('document.dynamic.itemTitle', {
                          label: field.label,
                          index: index + 1,
                        })}
                      </LocalizedText>
                      {canRemove ? (
                        <Pressable
                          testID={`${testIDPrefix}-remove-${field.name}-${index}`}
                          onPress={() => {
                            onChange({
                              ...values,
                              [field.name]: rows.filter((_, rowIndex) => rowIndex !== index),
                            });
                          }}
                          disabled={disabled}
                          accessibilityRole="button"
                          accessibilityLabel={t('document.dynamic.remove')}
                          style={styles.removeButton}
                        >
                          <Ionicons name="close-circle-outline" size={20} color={color.text.muted} />
                        </Pressable>
                      ) : null}
                    </View>
                    {field.itemFields.map((sub) => {
                      const path = `${field.name}.${index}.${sub.name}`;
                      return (
                        <ScalarField
                          key={sub.name}
                          field={sub}
                          value={row[sub.name] ?? ''}
                          error={errorTextOf(path)}
                          disabled={disabled}
                          testIDPrefix={`${testIDPrefix}-item-${itemKey}`}
                          onChange={(next) => setItemCell(field.name, index, sub.name, next)}
                          onBlur={() => onBlur(path)}
                        />
                      );
                    })}
                  </View>
                );
              })}

              {canAdd ? (
                <Pressable
                  testID={`${testIDPrefix}-add-${field.name}`}
                  onPress={() => {
                    onChange({
                      ...values,
                      [field.name]: [...rows, createEmptyItem(field.itemFields)],
                    });
                  }}
                  disabled={disabled}
                  accessibilityRole="button"
                  accessibilityLabel={t('document.dynamic.add', { label: field.label })}
                  style={styles.addButton}
                >
                  <Ionicons name="add" size={18} color={color.brand.primary} />
                  <LocalizedText numberOfLines={1} style={styles.addText}>
                    {t('document.dynamic.add', { label: field.label })}
                  </LocalizedText>
                </Pressable>
              ) : (
                <LocalizedText numberOfLines={1} style={styles.arrayLimit}>
                  {t('document.dynamic.maxReached', { max: field.maxItems })}
                </LocalizedText>
              )}
            </View>
          );
        }

        return (
          <View key={`scalars-${groupIndex}`} style={styles.card} testID={`${testIDPrefix}-scalars-${groupIndex}`}>
            {groupIndex === firstScalarsIndex ? (
              <View style={styles.cardHead}>
                <View style={styles.cardBar} />
                <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                  {t('document.dynamic.basicInfo')}
                </LocalizedText>
              </View>
            ) : null}
            {group.fields.map((field) => {
              const raw = values[field.name];
              return (
                <ScalarField
                  key={field.name}
                  field={field}
                  value={typeof raw === 'string' ? raw : ''}
                  error={errorTextOf(field.name)}
                  disabled={disabled}
                  testIDPrefix={testIDPrefix}
                  onChange={(next) => setScalar(field.name, next)}
                  onBlur={() => onBlur(field.name)}
                />
              );
            })}
          </View>
        );
      })}
    </View>
  );
}

/** 表单页壳的文案配置（由 E2 / E3 页面传入）。 */
export type DocumentFormConfig = {
  /** 导航栏标题。 */
  navbarTitle: string;
  /** 页面副标题。 */
  pageSub: string;
  /** 合规 / 说明提示条。 */
  notice: string;
  /** 生成按钮文案。 */
  generateLabel: string;
  /** 自动化前缀（`lawsuit` / `form`）。 */
  testIDPrefix: string;
};

/** 表单页加载结果：模板 + 已有文书（从预览页「返回表单修改」进来时才有）。 */
type FormPageData = {
  /** 命中的模板；`null` = 参数缺失或模板已下线。 */
  template: DocTemplateBrief | null;
  /** 已有文书详情；新建为 null。 */
  detail: DocumentDetail | null;
};

/**
 * 拼预览页路由。
 *
 * `generating=1` 是给预览页（E4，fe-doc-b）的信号：后端生成期间 `status` 仍是 `draft`
 * （没有独立的 generating 字段），预览页据此直接进「生成中」轮询，而不是先白转 3 秒。
 *
 * @param documentId 文书主键
 * @returns 可传给 router 的地址
 */
function previewHref(documentId: string): Href {
  return `${String(PAGE_ROUTES.docPreview)}?id=${encodeURIComponent(documentId)}&generating=1` as Href;
}

/**
 * 加载表单页数据（模板 + 可选的已有文书）。
 *
 * @param params.templateId 模板主键（可空）
 * @param params.documentId 文书主键（可空，从预览页返回修改时传）
 * @returns 成功时给出模板与详情；失败时给 `AppError`
 */
async function loadFormPage(params: {
  templateId: string | undefined;
  documentId: string | undefined;
}): Promise<ApiResult<FormPageData>> {
  const templatesResult = await fetchTemplates();
  if (!templatesResult.ok) {
    return templatesResult;
  }
  let detail: DocumentDetail | null = null;
  if (params.documentId !== undefined) {
    const detailResult = await fetchDocument(params.documentId);
    if (!detailResult.ok) {
      return detailResult;
    }
    detail = detailResult.data;
  }
  const wantedId = params.templateId ?? detail?.templateId ?? null;
  const template =
    wantedId === null ? null : (templatesResult.data.find((item) => item.id === wantedId) ?? null);
  return { ok: true, data: { template, detail } };
}

/**
 * 渲染「参数缺失 / 模板不可用」的空态（自带返回入口，不用 AsyncBoundary 的空态）。
 *
 * @param props.testIDPrefix 自动化前缀
 * @param props.paramMissing 是否因路由参数缺失（否则是模板已下线）
 * @returns 居中说明 + 返回模板列表按钮
 */
function FormEmptyState({
  testIDPrefix,
  paramMissing,
}: {
  testIDPrefix: string;
  paramMissing: boolean;
}) {
  const { t } = useI18n();
  const router = useRouter();
  return (
    <View style={styles.emptyWrap} testID={`${testIDPrefix}-empty`}>
      <Ionicons name="document-outline" size={40} color={color.text.muted} />
      <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
        {paramMissing ? t('document.form.paramMissing') : t('document.form.templateMissing')}
      </LocalizedText>
      <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
        {t('document.form.templateMissingDesc')}
      </LocalizedText>
      <PrimaryButton
        label={t('document.form.backToTemplates')}
        variant="outline"
        onPress={() => router.replace(PAGE_ROUTES.docTemplates)}
        style={styles.emptyAction}
        testID={`${testIDPrefix}-back-templates`}
      />
    </View>
  );
}

/**
 * 表单主体（模板就绪后渲染；自动保存与生成都在这里）。
 *
 * @param props.data   模板与已有文书
 * @param props.config 页面文案与自动化前缀
 * @returns 滚动表单 + 底部操作栏
 */
function FormBody({ data, config }: { data: FormPageData; config: DocumentFormConfig }) {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const actionSpace = useActionBarSpace();
  const { template, detail } = data;

  const fields = useMemo(
    () => (template === null ? [] : normalizeFieldSchema(parseFieldSchema(template.fieldSchema))),
    [template],
  );

  const [values, setValues] = useState<FormValues>(() =>
    mergeFormValues(fields, parseFormData(detail?.formData ?? null)),
  );
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'failed'>('idle');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const documentIdRef = useRef<string | null>(detail?.id ?? null);
  const valuesRef = useRef<FormValues>(values);
  const dirtyRef = useRef(false);
  const savingRef = useRef<Promise<string | null> | null>(null);
  const mountedRef = useRef(true);
  const persistRef = useRef<() => Promise<string | null>>(async () => null);

  useEffect(() => {
    valuesRef.current = values;
  }, [values]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  /**
   * 把当前值存成草稿（新建或更新同一条）。
   *
   * 副作用：写服务端；成功后把返回 id 记进 ref（后续更新走同一条）。并发调用复用同一个 Promise。
   *
   * @returns 文书主键；失败时为已存在的 id（可能 null）
   */
  const persist = useCallback(async (): Promise<string | null> => {
    if (template === null) {
      return null;
    }
    if (savingRef.current !== null) {
      return savingRef.current;
    }
    const task = (async () => {
      if (mountedRef.current) {
        setSaveState('saving');
      }
      const result = await saveDraft({
        id: documentIdRef.current ?? undefined,
        templateId: template.id,
        title: template.name,
        // 派生字段（金额大写等）不渲染、不校验，落库前按来源字段算出来再提交
        formData: JSON.stringify(applyDerivedFields(fields, valuesRef.current)),
      });
      if (!result.ok) {
        if (mountedRef.current) {
          setSaveState('failed');
        }
        return documentIdRef.current;
      }
      documentIdRef.current = result.data;
      dirtyRef.current = false;
      if (mountedRef.current) {
        setSaveState('saved');
      }
      return result.data;
    })();
    savingRef.current = task;
    try {
      return await task;
    } finally {
      savingRef.current = null;
    }
  }, [fields, template]);

  useEffect(() => {
    persistRef.current = persist;
  }, [persist]);

  // 5 秒防抖自动保存：只在用户改动后启动；回填不触发。
  useEffect(() => {
    if (!dirtyRef.current) {
      return;
    }
    const timer = setTimeout(() => {
      void persist();
    }, AUTOSAVE_DELAY_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [values, persist]);

  // 离开页面 / 切后台立即存一次（工作流 E2 第 3 条）。
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      if (next !== 'active' && dirtyRef.current) {
        void persistRef.current();
      }
    });
    return () => {
      subscription.remove();
    };
  }, []);

  useEffect(
    () => () => {
      if (dirtyRef.current) {
        void persistRef.current();
      }
    },
    [],
  );

  const validation = useMemo(() => validateFormValues(fields, values), [fields, values]);

  const visibleErrors = useMemo(() => {
    const shown: ValidationErrorMap = {};
    for (const [path, issue] of Object.entries(validation.errors)) {
      const rootPath = path.split('.')[0] ?? path;
      if (submitted || touched[path] === true || touched[rootPath] === true) {
        shown[path] = issue;
      }
    }
    return shown;
  }, [validation, submitted, touched]);

  /**
   * 「还有 N 项必填未填」提示。
   *
   * 只要校验没通过就显示（不只 touched 后）：因为「立即生成」在校验没通过时是置灰的，
   * 用户必须能看到置灰的原因（工作流 E2 自查：必填未填时按钮置灰并说明原因）。
   */
  const showRequiredHint = !validation.ok;

  /**
   * 表单值变化：标脏并更新。
   *
   * @param next 新值
   */
  const handleChange = (next: FormValues) => {
    dirtyRef.current = true;
    setValues(next);
  };

  /**
   * 字段失焦：加入 touched，让该字段的错误开始显示。
   *
   * @param path 字段路径
   */
  const handleBlur = (path: string) => {
    setTouched((previous) => (previous[path] === true ? previous : { ...previous, [path]: true }));
  };

  /**
   * 手动保存草稿（底部「保存草稿」）。
   *
   * 副作用：写服务端并弹提示。
   */
  const handleSaveDraft = async () => {
    setSubmitError(null);
    const id = await persist();
    if (id === null) {
      toast.show(t('document.form.draftSaveFailed'));
      return;
    }
    toast.show(t('document.form.draftSaved'));
  };

  /**
   * 立即生成：先存草稿拿 id，再投递生成任务，然后跳预览页。
   *
   * 副作用：写服务端两次 + 路由跳转。
   */
  const handleGenerate = async () => {
    setSubmitted(true);
    setSubmitError(null);
    if (!validation.ok) {
      return;
    }
    setSubmitting(true);
    try {
      const id = await persist();
      if (id === null) {
        setSubmitError(t('document.form.saveBeforeGenerateFailed'));
        return;
      }
      const result = await generateDocument(id);
      if (!result.ok) {
        setSubmitError(errorText(result.error));
        return;
      }
      router.replace(previewHref(id));
    } finally {
      if (mountedRef.current) {
        setSubmitting(false);
      }
    }
  };

  const regenerateHint = detail !== null;

  return (
    <>
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.scroll, { paddingBottom: actionSpace }]}
        testID={`${config.testIDPrefix}-scroll`}
      >
        <View testID={`${config.testIDPrefix}-form`}>
          <LocalizedText numberOfLines={2} style={styles.pageTitle} testID={`${config.testIDPrefix}-title`}>
            {template === null ? '' : template.name}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.pageSub}>
            {config.pageSub}
          </LocalizedText>

          {regenerateHint ? (
            <View style={styles.regenerateHint} testID={`${config.testIDPrefix}-regenerate-hint`}>
              <Ionicons name="refresh-circle-outline" size={18} color={color.brand.primary} />
              <LocalizedText numberOfLines={2} style={styles.regenerateText}>
                {t('document.lawsuit.regenerateHint')}
              </LocalizedText>
            </View>
          ) : null}

          <View style={styles.notice} testID={`${config.testIDPrefix}-notice`}>
            <Ionicons name="information-circle-outline" size={18} color={color.text.secondary} />
            <LocalizedText numberOfLines={4} style={styles.noticeText}>
              {config.notice}
            </LocalizedText>
          </View>

          {saveState === 'idle' ? null : (
            <LocalizedText
              numberOfLines={1}
              style={[styles.saveState, saveState === 'failed' ? styles.saveStateFailed : null]}
              testID={`${config.testIDPrefix}-draft-state`}
            >
              {saveState === 'saving'
                ? t('document.form.saving')
                : saveState === 'saved'
                  ? t('document.form.draftSaved')
                  : t('document.form.draftSaveFailed')}
            </LocalizedText>
          )}

          <DynamicForm
            fields={fields}
            values={values}
            errors={visibleErrors}
            disabled={submitting}
            testIDPrefix={config.testIDPrefix}
            onChange={handleChange}
            onBlur={handleBlur}
          />

          {fields.length === 0 ? (
            <View style={styles.emptyWrap} testID={`${config.testIDPrefix}-no-fields`}>
              <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
                {t('document.form.empty')}
              </LocalizedText>
              <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
                {t('document.form.emptyDesc')}
              </LocalizedText>
            </View>
          ) : null}

          {showRequiredHint ? (
            <LocalizedText
              numberOfLines={2}
              style={styles.requiredHint}
              testID={`${config.testIDPrefix}-required-hint`}
            >
              {t('document.form.requiredHint', { count: validation.missingLabels.length })}
            </LocalizedText>
          ) : null}

          {submitError === null ? null : (
            <LocalizedText
              numberOfLines={3}
              style={styles.submitError}
              testID={`${config.testIDPrefix}-submit-error`}
            >
              {submitError}
            </LocalizedText>
          )}
        </View>
      </ScrollView>

      <ActionBar>
        <View style={styles.actionRow}>
          <PrimaryButton
            label={t('document.form.saveDraft')}
            variant="outline"
            fullWidth={false}
            style={styles.actionHalf}
            onPress={() => {
              void handleSaveDraft();
            }}
            disabled={submitting}
            testID={`${config.testIDPrefix}-save-draft`}
          />
          <PrimaryButton
            label={submitting ? t('document.form.generating') : config.generateLabel}
            fullWidth={false}
            style={styles.actionHalf}
            onPress={() => {
              void handleGenerate();
            }}
            disabled={submitting || fields.length === 0 || !validation.ok}
            testID={`${config.testIDPrefix}-generate`}
          />
        </View>
      </ActionBar>
    </>
  );
}

/**
 * 文书表单页壳（E2 / E3 共用）。
 *
 * 副作用：挂载时拉模板与（可选）已有文书；离开页面或切后台时会保存草稿（见 `FormBody`）。
 *
 * @param props.config 页面文案与自动化前缀
 * @returns 加载 / 错误 / 空 / 正常四态页面
 */
export function DocumentFormScreen({ config }: { config: DocumentFormConfig }) {
  const params = useLocalSearchParams<{ templateId?: string; documentId?: string }>();
  const templateId = typeof params.templateId === 'string' ? params.templateId : undefined;
  const documentId = typeof params.documentId === 'string' ? params.documentId : undefined;
  const resource = useAsyncResource(() => loadFormPage({ templateId, documentId }));

  const paramMissing = templateId === undefined && documentId === undefined;
  const state = resource.state;

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.screenRoot} testID={`${config.testIDPrefix}-screen`}>
        <AppHeader title={config.navbarTitle} />
        {state.phase === 'loading' ? (
          <View style={styles.stateWrap} testID={`${config.testIDPrefix}-loading`}>
            <LoadingState rows={4} />
          </View>
        ) : null}
        {state.phase === 'error' ? (
          <View style={styles.stateWrap} testID={`${config.testIDPrefix}-error`}>
            <ErrorState
              error={state.error}
              onRetry={resource.reload}
              retryTestID={`${config.testIDPrefix}-retry`}
            />
          </View>
        ) : null}
        {state.phase === 'success' ? (
          state.data.template === null ? (
            <FormEmptyState
              testIDPrefix={config.testIDPrefix}
              paramMissing={paramMissing}
            />
          ) : (
            <FormBody data={state.data} config={config} />
          )
        ) : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screenRoot: {
    flex: 1,
  },
  stateWrap: {
    paddingHorizontal: spacing.lg,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
  },
  pageTitle: {
    ...typography.h2,
    color: color.text.primary,
  },
  pageSub: {
    ...typography.bodySmall,
    color: color.text.muted,
    marginTop: spacing.xxs,
    marginBottom: spacing.md,
  },
  notice: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
    marginBottom: spacing.md,
  },
  noticeText: {
    ...typography.caption,
    flex: 1,
    color: color.text.secondary,
  },
  regenerateHint: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    marginBottom: spacing.md,
  },
  regenerateText: {
    ...typography.caption,
    flex: 1,
    color: color.text.secondary,
  },
  saveState: {
    ...typography.micro,
    color: color.text.muted,
    marginBottom: spacing.sm,
    textAlign: 'auto',
  },
  saveStateFailed: {
    color: color.state.danger,
  },
  card: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    padding: spacing.lg,
    marginBottom: spacing.md,
    gap: spacing.xs,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.xs,
  },
  cardBar: {
    width: 4,
    height: 16,
    borderRadius: radius.xs,
    backgroundColor: color.brand.primary,
  },
  cardTitle: {
    ...typography.h3,
    flex: 1,
    color: color.text.body,
  },
  cardCounter: {
    ...typography.micro,
    color: color.text.muted,
  },
  field: {
    marginTop: spacing.md,
  },
  fieldLabel: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginBottom: spacing.xs,
    textAlign: 'auto',
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: size.inputHeight,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    paddingHorizontal: spacing.md,
  },
  inputRowMultiline: {
    alignItems: 'flex-start',
    paddingVertical: spacing.sm,
  },
  /** 聚焦态：主色细边框取代浏览器默认焦点环（见 `WEB_FOCUS_RESET`）。 */
  inputRowFocused: {
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  /** 输入框右侧的固定单位（如利率的 `%`）——只展示，不进 form_data。 */
  suffixText: {
    ...typography.body,
    color: color.text.secondary,
    marginStart: spacing.xs,
  },
  input: {
    ...typography.body,
    flex: 1,
    height: size.inputHeight,
    color: color.text.body,
    paddingVertical: 0,
    textAlign: 'auto',
  },
  inputMultiline: {
    height: size.inputHeight * 3,
    textAlignVertical: 'top',
  },
  fieldError: {
    ...typography.caption,
    color: color.state.danger,
    marginTop: spacing.xxs,
    textAlign: 'auto',
  },
  optionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    paddingVertical: spacing.xs,
  },
  optionChip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.canvas,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  optionChipActive: {
    backgroundColor: color.brand.primary,
    borderColor: color.brand.primary,
  },
  optionText: {
    ...typography.body,
    color: color.text.secondary,
  },
  optionTextActive: {
    color: color.text.onBrand,
  },
  itemBlock: {
    marginTop: spacing.md,
    paddingTop: spacing.xs,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  itemHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  itemTitle: {
    ...typography.body,
    flex: 1,
    color: color.text.primary,
  },
  removeButton: {
    padding: spacing.xxs,
  },
  addButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
    marginTop: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: color.border.outline,
  },
  addText: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
  arrayEmpty: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'auto',
  },
  arrayLimit: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.md,
    textAlign: 'auto',
  },
  requiredHint: {
    ...typography.caption,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
    marginTop: spacing.sm,
    textAlign: 'auto',
  },
  submitError: {
    ...typography.bodySmall,
    color: color.state.danger,
    paddingHorizontal: spacing.xs,
    marginTop: spacing.sm,
    textAlign: 'auto',
  },
  emptyWrap: {
    alignItems: 'center',
    paddingVertical: spacing.xxxl,
    paddingHorizontal: spacing.xl,
    gap: spacing.sm,
  },
  emptyTitle: {
    ...typography.bodyLarge,
    color: color.text.body,
    textAlign: 'center',
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyAction: {
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xl,
    minWidth: 160,
  },
  actionRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  actionHalf: {
    flex: 1,
  },
});
