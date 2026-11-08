/*
 * 职责：把 form_data 注入模板 HTML，产出合法 XHTML（段 4 C2）。
 * 调用方：DocGenerateHandler（C3，生成 PDF 前）、A-18 预览（B1）。
 * 数据来源：doc_template.template_html + doc_template.field_schema + document.form_data（JSON）。
 * 注意：
 *   1. **文本值必须 HTML 转义**：用户填的 `A&B` 直接拼进 HTML 会让整份文书解析失败；
 *      填 `<script>alert(1)</script>` 则可能破坏结构。这是本任务的核心风险，不是可选项。
 *   2. **只转义一次**：转义后再做任何 `replace` 都可能二次转义，显示成 `&amp;amp;`。
 *      实现上要求「先把所有值转义好，再一次性拼进模板」。
 *   3. 换行：用户输入的多行文本要变成 `<br/>`（XHTML 自闭合），但**先转义再替换**，
 *      否则 `\n` 里的字符会绕过转义。
 *   4. **替换后必须复查 `{{` 残留**：有残留说明模板有校验器没覆盖到的占位符，
 *      当成功渲染出去就是「文书里印着 {{xxx}}」。一律抛 406002。
 *   5. array 循环块只支持一层（与 FieldSchema 的 MAX_DEPTH 一致）：
 *      `{{#items}}...{{item.xxx}}...{{/items}}`。
 *   6. 本类**不查库、不开事务、不依赖 Spring 上下文**（纯函数，可脱离 Spring 单测）。
 */
package com.faze.modules.document.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.bo.FieldSchema;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 模板渲染器：form_data → XHTML。
 */
@Component
@Slf4j
public class TemplateRenderer {

    /** 循环块：`{{#items}}...{{/items}}`（非贪婪、跨行）。 */
    private static final Pattern ARRAY_BLOCK = Pattern.compile(
            "\\{\\{#([a-z][A-Za-z0-9_]*)\\}\\}(.*?)\\{\\{/\\1\\}\\}", Pattern.DOTALL);

    /** 块内条目字段：`{{item.xxx}}`。 */
    private static final Pattern ITEM_PLACEHOLDER = Pattern.compile(
            "\\{\\{item\\.([a-z][A-Za-z0-9_]*)\\}\\}");

    /** 顶层文本占位符：`{{key}}`。 */
    private static final Pattern TEXT_PLACEHOLDER = Pattern.compile(
            "\\{\\{([a-z][A-Za-z0-9_]*)\\}\\}");

    /**
     * 渲染。
     *
     * @param templateHtml 模板 HTML
     * @param schema       字段定义（用于必填与 array 边界校验）
     * @param formData     表单数据（JSON 对象）
     * @return 可交给 PdfRenderer 的 XHTML
     * @throws BusinessException 406002（必填缺失 / 类型不符 / 占位符残留）
     */
    public String render(String templateHtml, FieldSchema schema, JsonNode formData) {
        if (templateHtml == null || templateHtml.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "模板内容为空");
        }
        JsonNode data = formData == null
                ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : formData;

        String xhtml = replaceArrayBlocks(templateHtml, schema, data);
        xhtml = replaceTextPlaceholders(xhtml, schema, data);
        assertNoResidue(xhtml);
        return xhtml;
    }

    // ── array 循环块 ────────────────────────────────────────────────────────

    /**
     * 替换所有 array 循环块。
     *
     * @param html   模板
     * @param schema 字段定义
     * @param data   表单数据
     * @return 替换后的 HTML
     */
    private String replaceArrayBlocks(String html, FieldSchema schema, JsonNode data) {
        Matcher m = ARRAY_BLOCK.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String block = m.group(2);
            FieldSchema.FieldDef def = schema.find(key).orElseThrow(() -> new BusinessException(
                    ErrorCode.DOCUMENT_FORM_INVALID, "模板引用了未定义的循环字段：" + key));
            JsonNode array = data.get(key);
            if (array == null || !array.isArray()) {
                if (def.required()) {
                    throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                            "必填列表为空：" + (def.label() == null ? key : def.label()));
                }
                array = null;
            }
            int size = array == null ? 0 : array.size();
            if (def.minItems() != null && size < def.minItems()) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "列表「" + def.label() + "」至少 " + def.minItems() + " 项，实际 " + size + " 项");
            }
            if (def.maxItems() != null && size > def.maxItems()) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "列表「" + def.label() + "」最多 " + def.maxItems() + " 项，实际 " + size + " 项");
            }
            StringBuilder repeated = new StringBuilder();
            for (int i = 0; i < size; i++) {
                JsonNode item = array.get(i);
                repeated.append(replaceItem(block, item, def, i + 1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repeated.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 替换一个数组条目里的 `{{item.xxx}}`。
     *
     * @param block 块内容
     * @param item  条目 JSON
     * @param def   字段定义
     * @param index 条目序号（从 1 起，错误信息用）
     * @return 替换后的块
     */
    private String replaceItem(String block, JsonNode item, FieldSchema.FieldDef def, int index) {
        // 内置序号：模板里写 `{{item.index}}`，渲染成 1、2、3。
        // 为什么要内置而不是用 `<ol>`：openhtmltopdf 对 `<ol>` 的 list marker 定位有问题
        // （2026-11-07 实测：编号被渲染到页面顶部，`list-style-position: inside` 也不生效），
        // 版式不可控。序号做成变量后，模板只要一个 `<p>`，样式完全由 CSS 决定。
        String withIndex = block.replace("{{item.index}}", String.valueOf(index));
        Matcher m = ITEM_PLACEHOLDER.matcher(withIndex);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String subKey = m.group(1);
            String raw = textOf(item, subKey);
            if (raw == null && requiresItemField(def, subKey)) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "列表「" + def.label() + "」第 " + index + " 项缺少必填字段：" + subKey);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(escape(raw)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ── 文本占位符 ──────────────────────────────────────────────────────────

    /**
     * 替换顶层文本占位符。
     *
     * @param html   模板
     * @param schema 字段定义
     * @param data   表单数据
     * @return 替换后的 HTML
     */
    private String replaceTextPlaceholders(String html, FieldSchema schema, JsonNode data) {
        Matcher m = TEXT_PLACEHOLDER.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            FieldSchema.FieldDef def = schema.find(key).orElseThrow(() -> new BusinessException(
                    ErrorCode.DOCUMENT_FORM_INVALID, "模板引用了未定义的字段：" + key));
            String raw = textOf(data, key);
            if ((raw == null || raw.isBlank()) && def.required()) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "必填项未填写：" + (def.label() == null ? key : def.label()));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(escapeWithBreaks(raw)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 替换后不得残留 `{{`。
     *
     * @param html 渲染结果
     */
    private void assertNoResidue(String html) {
        int idx = html.indexOf("{{");
        if (idx >= 0) {
            String snippet = html.substring(idx, Math.min(idx + 40, html.length()));
            log.error("渲染后仍残留占位符：{}", snippet);
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "渲染后仍残留占位符：" + snippet);
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /**
     * 取 JSON 字段的文本值。
     *
     * @param node  JSON 节点
     * @param key   字段名
     * @return 文本；不存在或为 null 时返回 null
     */
    private String textOf(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    /**
     * 条目字段是否必填（按 itemSchema 查）。
     *
     * @param def    数组字段定义
     * @param subKey 条目字段名
     * @return 必填返回 true
     */
    private boolean requiresItemField(FieldSchema.FieldDef def, String subKey) {
        return def.itemFields().stream()
                .anyMatch(f -> f.key().equals(subKey) && f.required());
    }

    /**
     * HTML 转义（五项）。
     *
     * @param raw 原始文本
     * @return 转义后的文本；null → 空串
     */
    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 转义 + 把换行换成自闭合 `<br/>`。
     *
     * <p>顺序很重要：**先转义、再替换换行**。反过来会让 `&` 在换行处理后再被转义成 `&amp;`，
     * 出现 `&amp;amp;`。</p>
     *
     * @param raw 原始文本
     * @return 可插入 HTML 的文本
     */
    private String escapeWithBreaks(String raw) {
        String escaped = escape(raw);
        return escaped.replace("\r\n", "<br/>").replace("\n", "<br/>");
    }
}
