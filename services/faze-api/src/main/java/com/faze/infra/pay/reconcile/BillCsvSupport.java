/*
 * 职责：账单 CSV 的公共工具——金额「元↔分」转换、东八区时间↔UTC、行切分（第 6 段 D3）。
 * 调用方：MockBillParser / MockBillGenerator / （将来的）微信与支付宝解析器。
 * 数据来源：无（纯函数）。
 * 注意：
 *   1. **金额只走 `BigDecimal`，禁 `double`**：`0.1` 元用 double 乘 100 会得 10.000000000000002，
 *      `(long)` 截断是 10 但 `1.15` 就会丢分。D3 第 6 条与 C2 第 4 条是同一个坑。
 *      `movePointRight(2).longValueExact()` 遇非两位小数会抛异常，这是**故意**的——
 *      宁可解析失败也不要静默丢分。
 *   2. **账单文件里是渠道当地时区（东八区），`BillRow.paidAt` 统一存 UTC**：
 *      解析时换算，生成时换算回去，两侧同一口径，D4 比对时间才不会差 8 小时。
 *   3. 行切分用最朴素的 `split(",")`：**Mock 账单的字段里不含逗号**（渠道名、单号、金额、
 *      时间都不会有）。真实渠道的账单有引号包裹与转义，由各自解析器自己处理——不要把
 *      这个简化实现往微信账单上套。
 *   4. 兼容 `\r\n` 与 `\n` 两种行尾（渠道文件两种都有）。
 */
package com.faze.infra.pay.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 账单 CSV 工具。
 */
public final class BillCsvSupport {

    /** 账单文件里的时间格式（渠道当地时区，东八区）。 */
    public static final DateTimeFormatter BILL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 业务时区（与全项目一致）。 */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private BillCsvSupport() {
    }

    /**
     * 「元」字符串转「分」。
     *
     * @param yuan 账单里的金额文本，如 `100.00` / `0.1`
     * @return 分
     * @throws IllegalArgumentException 文本非法或超过两位小数
     */
    public static long yuanToCents(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            throw new IllegalArgumentException("账单金额为空");
        }
        try {
            return new BigDecimal(yuan.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("账单金额无法精确转分：" + yuan, e);
        }
    }

    /**
     * 「分」转「元」文本（两位小数，不带千分位）。
     *
     * @param cents 分
     * @return 元文本，如 `100.00`
     */
    public static String centsToYuan(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }

    /**
     * 账单时间（东八区）转 UTC。
     *
     * @param text 如 `2026-11-08 10:00:00`
     * @return UTC 时间
     * @throws IllegalArgumentException 时间格式非法
     */
    public static LocalDateTime billTimeToUtc(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("账单时间为空");
        }
        try {
            return LocalDateTime.parse(text.trim(), BILL_TIME_FORMAT)
                    .atZone(BIZ_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("账单时间格式非法：" + text, e);
        }
    }

    /**
     * UTC 时间转账单时间文本（东八区）。
     *
     * @param utc UTC 时间
     * @return 如 `2026-11-08 10:00:00`
     */
    public static String utcToBillTime(LocalDateTime utc) {
        LocalDateTime local = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(BIZ_ZONE).toLocalDateTime();
        return local.format(BILL_TIME_FORMAT);
    }

    /**
     * 东八区某日 00:00 的 UTC wall clock（查当日充值单用）。
     *
     * @param day 东八区日期
     * @return UTC 时间
     */
    public static LocalDateTime startOfDayUtc(LocalDate day) {
        return day.atStartOfDay(BIZ_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 东八区次日 00:00 的 UTC wall clock（左闭右开区间的右端）。
     *
     * @param day 东八区日期
     * @return UTC 时间
     */
    public static LocalDateTime startOfNextDayUtc(LocalDate day) {
        return startOfDayUtc(day.plusDays(1));
    }

    /**
     * 按行切分文件文本（兼容 `\r\n` 与 `\n`，丢弃空行）。
     *
     * @param text 文件全文
     * @return 非空行
     */
    public static String[] lines(String text) {
        return text.split("\\r?\\n");
    }

    /**
     * 判断一行是否是「数据行」（非空、非表头、非汇总）。
     *
     * <p>表头与汇总行的判定交给各解析器：它们知道自己的格式。本方法只判空。</p>
     *
     * @param line 一行
     * @return true 表示不是空行
     */
    public static boolean notBlank(String line) {
        return line != null && !line.isBlank();
    }
}
