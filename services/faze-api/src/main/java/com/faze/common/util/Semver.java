/*
 * 职责：语义化版本号（semantic version）的比较工具，供 E5「版本检查与强更」判定使用。
 * 调用方：modules/ops/controller/PubAppVersionController（对比 currentVersion 与 minVersion/latestVersion）。
 * 数据来源：入参是 sys_config 里的版本号字符串与查询参数 currentVersion。
 * 注意：
 *   1. **禁止用字符串比较**：`"1.10.0".compareTo("1.9.0")` 返回负数（字符 '1' < '9'），
 *      会把 1.10.0 判成比 1.9.0 旧，导致「明明升了版本却一直提示更新」。
 *   2. 只做「主.次.修订」三段数字比较；**忽略 `-beta` / `+build` 后缀**（本项目 app 版本
 *      当前不走预发布语义，后缀存在时按「相同版本号」处理，宁可少提示不可误强更）。
 *   3. 容错：允许 `v` 前缀（`v1.0.0`）与缺段（`1.0` 视作 `1.0.0`）；无法解析的段按 0 处理，
 *      但空串/纯空白返回 false（无法判定不降级成「比任何版本都小」——那会让空配置强制所有人更新）。
 *   4. 纯函数、无副作用，可安全并发调用。
 */
package com.faze.common.util;

/**
 * 语义化版本比较（只比较 x.y.z 三段数字）。
 */
public final class Semver {

    /** 工具类禁止实例化。 */
    private Semver() {
    }

    /**
     * 判断 {@code left} 是否**严格低于** {@code right}。
     *
     * <p>任一侧为空或无法解析时返回 false（「不确定」按「不需要更新」处理，
     * 避免配置缺失把全体客户端推向强更弹窗）。</p>
     *
     * @param left  待比较版本（如 currentVersion）
     * @param right 基准版本（如 minVersion）
     * @return left &lt; right 时 true
     */
    public static boolean isLower(String left, String right) {
        Integer[] a = parse(left);
        Integer[] b = parse(right);
        if (a == null || b == null) {
            return false;
        }
        return compareParts(a, b) < 0;
    }

    /**
     * 比较两个版本号。
     *
     * @param left  版本 A
     * @param right 版本 B
     * @return 负数表示 A &lt; B，0 表示相等，正数表示 A &gt; B；任一侧无法解析返回 0
     */
    public static int compare(String left, String right) {
        Integer[] a = parse(left);
        Integer[] b = parse(right);
        if (a == null || b == null) {
            return 0;
        }
        return compareParts(a, b);
    }

    /**
     * 解析成三段数字（缺段补 0，非数字段按 0）。
     *
     * @param version 版本字符串
     * @return 三段数字；null 表示无法解析（null / 空白 / 无任何数字段）
     */
    private static Integer[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        // 去掉 -pre / +build 后缀：只在第一个 - 或 + 之前取数字
        int cut = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        String core = cut == value.length() ? value : value.substring(0, cut);
        String[] parts = core.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        Integer[] result = {0, 0, 0};
        boolean anyDigit = false;
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            String part = parts[i].trim();
            int number = 0;
            if (!part.isEmpty()) {
                int digitEnd = 0;
                while (digitEnd < part.length() && Character.isDigit(part.charAt(digitEnd))) {
                    digitEnd++;
                }
                if (digitEnd > 0) {
                    anyDigit = true;
                    try {
                        number = Integer.parseInt(part.substring(0, digitEnd));
                    } catch (NumberFormatException ignored) {
                        number = 0;
                    }
                }
            }
            result[i] = number;
        }
        return anyDigit ? result : null;
    }

    /**
     * 逐段比较三段数字。
     *
     * @param a 版本 A
     * @param b 版本 B
     * @return 比较结果
     */
    private static int compareParts(Integer[] a, Integer[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
