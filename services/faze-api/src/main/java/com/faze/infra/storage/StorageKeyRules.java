/*
 * 职责：文件 key 的生成与校验规则（第 1 段 C1）。
 * 调用方：modules/account/service/UploadService、infra/storage/LocalFileStorage。
 * 数据来源：无（纯函数）。
 * 注意：
 *   1. 路径规则来自 C1：`private/{scene}/{yyyyMM}/{userId}/{uuid}.{ext}`。
 *      **bucket 必须私有**——实名证件影像不许有公开可读地址。
 *   2. `scene` 用白名单，**白名单外一律拒绝**（防任意路径写入）。
 *   3. 校验用「完整正则匹配」而不是「包含 ../ 就拒绝」：黑名单永远漏，
 *      白名单正则能把范围钉死。
 *   4. 扩展名白名单只放图片与 PDF；`.html`/`.svg`/`.js` 一律拒绝——
 *      这些能被浏览器当页面执行，是存储型 XSS 的经典入口。
 */
package com.faze.infra.storage;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对象 key 规则。
 */
public final class StorageKeyRules {

    /**
     * 允许的场景白名单。
     *
     * <p>段 1 的 5 个场景 + 段 4 新增 3 个：`signature`（手写签名 PNG，客户端直传）、
     * `document`（文书 PDF 与预览图，服务端生成后落盘）、`doc_template`（模板预览图，服务端生成）。</p>
     *
     * <p>段 5 新增 1 个：`withdraw`（提现出款凭证，管理员在 PC 上传，服务端落盘并登记
     * `file_record(biz_type='withdraw')`）。</p>
     *
     * <p>**必须与 {@link #KEY_PATTERN} 同步**：只改这里不改正则，`build()` 能生成但 `isValid()` 会拒，
     * 表现为「上传凭证签发成功、PUT 时 403」——排查时很容易误判成签名算错。</p>
     */
    public static final Set<String> ALLOWED_SCENES = Set.of(
            "realname", "identity", "license", "avatar", "chat",
            "signature", "document", "doc_template", "withdraw");

    /** 允许的扩展名白名单。 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "pdf");

    /**
     * 完整 key 正则：与生成规则严格一致。
     *
     * <p>场景列表必须与 {@link #ALLOWED_SCENES} 逐项相同（含顺序无关的集合语义）。
     * `doc_template` 虽含下划线，正则里按字面量写，不要写成 `\w+`——那会把任意场景放进来，
     * 白名单就退化成黑名单了。</p>
     */
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^private/(realname|identity|license|avatar|chat|signature|document|doc_template|withdraw)"
                    + "/\\d{6}/\\d+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    + "\\.(jpg|jpeg|png|webp|pdf)$");

    private StorageKeyRules() {
    }

    /**
     * 生成对象 key。
     *
     * @param scene      场景（必须在白名单内）
     * @param userId     用户主键
     * @param yyyyMM     年月，如 202611
     * @param ext        扩展名（不含点，必须在白名单内）
     * @return 完整 key
     * @throws IllegalArgumentException 场景或扩展名不在白名单内
     */
    public static String build(String scene, Long userId, String yyyyMM, String ext) {
        if (!ALLOWED_SCENES.contains(scene)) {
            throw new IllegalArgumentException("非法场景：" + scene);
        }
        String normalizedExt = ext == null ? "" : ext.toLowerCase(Locale.ROOT).replace(".", "");
        if (!ALLOWED_EXT.contains(normalizedExt)) {
            throw new IllegalArgumentException("不允许的文件类型：" + ext);
        }
        return "private/" + scene + "/" + yyyyMM + "/" + userId + "/" + UUID.randomUUID() + "." + normalizedExt;
    }

    /**
     * 校验 key 是否合法（格式 + 场景 + 扩展名）。
     *
     * @param key 待校验 key
     * @return 合法返回 true
     */
    public static boolean isValid(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 从 key 中取所属用户主键（用于归属校验：只能取自己的文件）。
     *
     * @param key 合法 key
     * @return 用户主键；key 非法时返回 null
     */
    public static Long ownerOf(String key) {
        if (!isValid(key)) {
            return null;
        }
        String[] parts = key.split("/");
        try {
            return Long.valueOf(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取文件扩展名（不含点）。
     *
     * @param fileName 原始文件名
     * @return 小写扩展名；无扩展名返回空串
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
