/**
 * 人民币金额小写转大写。
 *
 * 职责：把用户输入的阿拉伯数字金额（如 `1000`、`1000.50`）转成借条/合同里要的
 *      规范大写（如 `壹仟元整`、`壹仟元伍角`）。
 * 调用方：`features/document/components/DynamicForm.tsx` 的派生字段计算
 *        （模板 `field_schema` 里标了 `derive: 'rmbUpper'` 的隐藏字段）。
 * 数据来源：用户在「借款金额（小写）」里输入的数字。
 * 注意：
 *   1. **转换在客户端做，服务端不参与**：后端 `TemplateRenderer` 只做 `{{key}}` 文本替换，
 *      schema 里的 `hidden` / `derivedFrom` / `derive` 是前端约定，后端解析时忽略。
 *      这样不必改后端渲染链路，也没有第二个转换实现需要对表。
 *   2. **输出含「元」**：模板里写的是 `（大写）{{amount_text}}，小写{{amount_num}}元`，
 *      所以本函数要把「元整 / 元X角」一起带上，不能只返回数字部分。
 *   3. **非法输入返回空串而不是抛异常**：空串会走到必填校验，用户能看到「请填写 XX」；
 *      抛异常会变成页面级错误，把「金额没填」渲染成「系统故障」。
 *   4. 只支持到**万亿**量级（民间借贷够用）；超出范围按 0 处理。
 */

/** 数字到中文大写字。 */
const DIGITS = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖'] as const;

/** 四位组内的位权（个 / 拾 / 佰 / 仟）。 */
const SMALL_UNITS = ['', '拾', '佰', '仟'] as const;

/** 四位组之间的位权（万 / 亿 / 万亿）。 */
const BIG_UNITS = ['', '万', '亿', '万亿'] as const;

/**
 * 把一个 0–9999 的整数转成大写，不含位权以外的零压缩。
 *
 * @param value 该组的值
 * @returns 大写串（全零时为空串）
 */
function groupToUpper(value: number): string {
  let digits = '';
  let zero = false;
  for (let pos = 3; pos >= 0; pos -= 1) {
    const unit = 10 ** pos;
    const digit = Math.floor(value / unit) % 10;
    if (digit === 0) {
      // 组内出现 0 时先记下，等后面还有非零位再补「零」，避免「壹仟零」这种尾巴
      zero = digits !== '';
      continue;
    }
    if (zero) {
      digits += '零';
      zero = false;
    }
    digits += (DIGITS[digit] ?? '') + (SMALL_UNITS[pos] ?? '');
  }
  return digits;
}

/**
 * 整数部分转大写。
 *
 * @param integer 非负整数
 * @returns 大写串（0 时返回「零」）
 */
function integerToUpper(integer: number): string {
  if (integer === 0) {
    return '零';
  }
  const groups: number[] = [];
  let rest = integer;
  while (rest > 0) {
    groups.push(rest % 10000);
    rest = Math.floor(rest / 10000);
  }
  let text = '';
  for (let index = groups.length - 1; index >= 0; index -= 1) {
    const group = groups[index] ?? 0;
    if (group === 0) {
      // 整组为零：只有在后面还有非零组时才补一个「零」（如 1_0000_0001 → 壹亿零壹）
      if (text !== '' && groups.slice(0, index).some((item) => item > 0) && !text.endsWith('零')) {
        text += '零';
      }
      continue;
    }
    const groupText = groupToUpper(group);
    // 组首位不足 4 位（如 12 万）时，若前面已有内容，需要补「零」占位：10_0001 → 拾万零壹
    if (text !== '' && group < 1000 && !text.endsWith('零')) {
      text += '零';
    }
    text += groupText + BIG_UNITS[index];
  }
  return text;
}

/**
 * 金额小写转大写。
 *
 * @param input 用户输入（可能是 `1,000.50`、`1000.5`、`1000` 等）
 * @returns 规范大写（含「元 / 角 / 分」）；无法解析时返回空串
 */
export function toRmbUpper(input: string): string {
  // 容错：去掉千分位逗号与空格，全角句点也当小数点
  const normalized = input.replace(/[,，\s]/g, '').replace(/。|．/g, '.');
  if (!/^\d+(?:\.\d{0,2})?$/.test(normalized)) {
    return '';
  }
  const [integerPart = '0', decimalPart = ''] = normalized.split('.');
  const integer = Number(integerPart);
  if (!Number.isFinite(integer) || integer > 9_999_999_999_999) {
    return '';
  }
  const jiao = Number(decimalPart[0] ?? '0');
  const fen = Number(decimalPart[1] ?? '0');

  if (integer === 0 && jiao === 0 && fen === 0) {
    return '零元整';
  }

  let text = '';
  if (integer > 0) {
    text += `${integerToUpper(integer)}元`;
  }
  if (jiao === 0 && fen === 0) {
    return `${text}整`;
  }
  if (jiao > 0) {
    text += `${DIGITS[jiao]}角`;
  } else if (integer > 0 && fen > 0) {
    // 有元有分、角为零：规范写法要带「零」（壹仟元零伍分）
    text += '零';
  }
  if (fen > 0) {
    text += `${DIGITS[fen]}分`;
  }
  return text;
}
