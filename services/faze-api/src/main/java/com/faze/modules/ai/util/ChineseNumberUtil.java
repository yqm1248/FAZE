/*
 * 职责：中文数字 ↔ 阿拉伯数字的纯函数转换（段 3 B3）。`law_item.article_no` 存中文条号
 *       （第六百八十条），`article_seq` 存整数（680），两者比对必须先做这层转换。
 * 调用方：modules/ai/service/ArticleNoValidator（条号回查）、modules/ai/service/LawSearchService
 *         （条号精确检索时把用户写的「第680条」转成 article_seq）。
 * 数据来源：无（纯函数，不查库、不注入、无状态，可直接 new 出来单测）。
 * 注意：
 *   1. **只支持 0~9999**：本项目法条序号最大是民法典的 1260，万级单位（万/亿）用不上；
 *      超出范围返回 null，不猜。
 *   2. **解析失败一律返回 null，不抛异常**：校验链路的语义是「判为不存在」而不是「报错」
 *      （工作流 B3 第 5 条），异常会让 D3 整条回答流被打断。
 *   3. 只接受「零一二三四五六七八九 十百千」与「两」（两=二，兼容口语「两千」），
 *      不接受「壹贰叁」大写金额写法——法条条号不会用大写。
 *   4. 相邻单位的合法性用「单位必须严格递减」校验：`一百十`（=110，合法，缺「一」）通过，
 *      `十百`、`一百百` 返回 null，避免把错写解析成看似合理的数字。
 */
package com.faze.modules.ai.util;

/**
 * 中文数字与阿拉伯数字互转工具（纯函数）。
 */
public final class ChineseNumberUtil {

    /** 支持的最大数字（千位）。 */
    public static final int MAX_SUPPORTED = 9999;

    /** 零字符。 */
    private static final char ZERO = '零';
    /** 十。 */
    private static final char TEN = '十';
    /** 百。 */
    private static final char HUNDRED = '百';
    /** 千。 */
    private static final char THOUSAND = '千';

    private ChineseNumberUtil() {
    }

    /**
     * 中文数字转阿拉伯数字。
     *
     * <p>支持 `第六百八十条` 里的 `六百八十`、`第680条` 里的 `680`；不支持的写法返回 null。</p>
     *
     * @param text 中文数字（可含前导/尾随空白）；也可直接传阿拉伯数字串
     * @return 阿拉伯数字；解析失败或超出 0~{@value #MAX_SUPPORTED} 时返回 null
     */
    public static Integer toArabic(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        // 任务 1：纯阿拉伯数字（用户直接问「第680条」），比中文解析优先。
        if (isAsciiDigits(value)) {
            try {
                long parsed = Long.parseLong(value);
                return parsed >= 0 && parsed <= MAX_SUPPORTED ? (int) parsed : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // 任务 2：逐字解析。section 累计「千/百/十」段，number 是当前单位前的数字。
        int section = 0;
        int number = 0;
        int lastUnit = 0;
        boolean hasUnit = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int digit = digitOf(ch);
            if (digit >= 0) {
                // 连续两个非零数字（如「一二」）不是合法条号写法；「零」后可再接数字（一百零一）。
                if (digit != 0 && number != 0 && hasDigit) {
                    return null;
                }
                number = digit;
                hasDigit = true;
                continue;
            }
            int unit = unitOf(ch);
            if (unit < 0) {
                return null;
            }
            if (number == 0) {
                // 「十」可单用（十九 = 19）；「百」「千」不能单用；「零十」非法。
                if (unit == 10 && section == 0 && lastUnit == 0) {
                    number = 1;
                } else {
                    return null;
                }
            }
            if (lastUnit != 0 && unit >= lastUnit) {
                return null;
            }
            section += number * unit;
            number = 0;
            lastUnit = unit;
            hasUnit = true;
        }
        section += number;
        // 任务 3：没有单位的多字写法（「一二三」）不猜；单字（「一」「零」）允许。
        if (!hasUnit && value.length() > 1) {
            return null;
        }
        return section >= 0 && section <= MAX_SUPPORTED ? section : null;
    }

    /**
     * 阿拉伯数字转中文数字（与 {@link #toArabic(String)} 互逆）。
     *
     * @param number 0~{@value #MAX_SUPPORTED}
     * @return 中文数字，如 680 → 六百八十
     * @throws IllegalArgumentException number 超出支持范围（纯函数被误用，属于编程错误）
     */
    public static String toChinese(int number) {
        if (number < 0 || number > MAX_SUPPORTED) {
            throw new IllegalArgumentException("只支持 0~" + MAX_SUPPORTED + "，收到 " + number);
        }
        if (number == 0) {
            return String.valueOf(ZERO);
        }
        StringBuilder out = new StringBuilder(8);
        int thousands = number / 1000;
        int hundreds = number % 1000 / 100;
        int tens = number % 100 / 10;
        int ones = number % 10;

        if (thousands > 0) {
            out.append(chineseDigit(thousands)).append(THOUSAND);
        }
        if (hundreds > 0) {
            out.append(chineseDigit(hundreds)).append(HUNDRED);
        } else if (thousands > 0 && (tens > 0 || ones > 0)) {
            // 一千零六十 / 一千零一：高位与低位之间缺一个零
            out.append(ZERO);
        }
        if (tens > 0) {
            // 十/十九 不写「一十」「一十九」，但 一百一十 / 一千二百六十 要写「一十」
            if (!(tens == 1 && thousands == 0 && hundreds == 0)) {
                out.append(chineseDigit(tens));
            }
            out.append(TEN);
        } else if ((thousands > 0 || hundreds > 0) && ones > 0) {
            // 一百零一 / 一千零五：十位是 0 且有个位时要补零
            out.append(ZERO);
        }
        if (ones > 0) {
            out.append(chineseDigit(ones));
        }
        return out.toString();
    }

    /**
     * 判断是否由 ASCII 数字组成。
     *
     * @param text 文本
     * @return 全为 0~9 时 true
     */
    private static boolean isAsciiDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 取中文字符对应的数字。
     *
     * @param ch 字符
     * @return 0~9；不是数字字符时 -1
     */
    private static int digitOf(char ch) {
        switch (ch) {
            case '零':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    /**
     * 取中文字符对应的单位。
     *
     * @param ch 字符
     * @return 10/100/1000；不是单位字符时 -1
     */
    private static int unitOf(char ch) {
        switch (ch) {
            case TEN:
                return 10;
            case HUNDRED:
                return 100;
            case THOUSAND:
                return 1000;
            default:
                return -1;
        }
    }

    /**
     * 数字 1~9 转单字中文。
     *
     * @param digit 1~9
     * @return 中文单字
     */
    private static char chineseDigit(int digit) {
        switch (digit) {
            case 1:
                return '一';
            case 2:
                return '二';
            case 3:
                return '三';
            case 4:
                return '四';
            case 5:
                return '五';
            case 6:
                return '六';
            case 7:
                return '七';
            case 8:
                return '八';
            case 9:
                return '九';
            default:
                throw new IllegalArgumentException("不是 1~9：" + digit);
        }
    }
}
