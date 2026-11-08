/*
 * 职责：模板 HTML 的三道校验——well-formed XHTML、标签/属性白名单、占位符与 field_schema 一一对应（段 4 B2）。
 * 调用方：A-18 保存模板（B1）、生成前复查（C2）、5 份模板的产出验收（B3）。
 * 数据来源：doc_template.template_html + doc_template.field_schema。
 * 注意：
 *   1. **必须在保存时校验，生成前再查一次**：保存时挡的是运营手误，生成前挡的是「模板被直接改库绕过保存接口」。
 *      只在保存时校验，等于把校验交给一个可以被绕过的入口（工作流 §三 B2 常见失败第 2 条）。
 *   2. openhtmltopdf 用 XML 解析器（不是 HTML 容错解析器）：`<br>` 必须写 `<br/>`、
 *      裸 `&` 必须写 `&amp;`。这里用 javax.xml 的 DOM 解析同一套规则，报错带行列号，
 *      这样运营能看到「第 12 行第 5 列」，而不是笼统的「XML 解析错误」。
 *   3. **白名单必须是白名单**：写成黑名单（只禁 flex）一定漏 `display: grid`、`display: inline-flex`。
 *      这里对 CSS 只做「禁用集合命中即拒」，标签做「不在允许集合即拒」，两个方向都是白名单语义。
 *   4. 占位符校验是**集合相等**（多一个少一个都拒）：少一个 = 有字段填了但渲染不出来；
 *      多一个 = 模板引用了不存在的字段，渲染时会被替换成空（静默丢内容，比报错更糟）。
 *   5. 解析 DOM 时**禁用外部实体**（防 XXE）：模板内容来自运营，不等于可信。
 */
package com.faze.modules.document.validator;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.bo.FieldSchema;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * 模板 HTML 校验器。
 */
@Component
@Slf4j
public class TemplateHtmlValidator {

    /**
     * 允许的标签（openhtmltopdf 支持的那一部分，见工作流 §三 B2 第 4 条）。
     *
     * <p>`meta` 是**工作流白名单的漏项**：§三 A2 第 4 条明确要求
     * `<meta charset="utf-8">` 必须写成自闭合 `<meta charset="utf-8" />`——
     * 既然要求写，就不能在校验时拒。它不会被渲染成内容，只影响 XML 解析。</p>
     */
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "html", "head", "meta", "style", "body", "div", "p", "span",
            "h1", "h2", "h3", "h4", "h5", "h6", "br", "hr",
            "table", "thead", "tbody", "tr", "td", "th",
            "ul", "ol", "li", "strong", "em", "u", "img");

    /** 禁用标签（即使将来白名单放宽也必须禁）。 */
    private static final Set<String> FORBIDDEN_TAGS = Set.of(
            "script", "iframe", "form", "input", "link", "video", "audio");

    /** 禁用的 CSS 片段：openhtmltopdf 不支持 flex/grid，写了会被**静默忽略**，版式错乱且不报错。 */
    private static final List<Pattern> FORBIDDEN_CSS = List.of(
            Pattern.compile("display\\s*:\\s*(inline-)?flex", Pattern.CASE_INSENSITIVE),
            Pattern.compile("display\\s*:\\s*(inline-)?grid", Pattern.CASE_INSENSITIVE),
            Pattern.compile("@media", Pattern.CASE_INSENSITIVE),
            Pattern.compile("position\\s*:\\s*fixed", Pattern.CASE_INSENSITIVE),
            Pattern.compile("transform\\s*:", Pattern.CASE_INSENSITIVE));

    /** 占位符：`{{key}}`、`{{#key}}`、`{{/key}}`、`{{item.xxx}}`。 */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([#/]?)\\s*([A-Za-z][A-Za-z0-9_.]*)\\s*\\}\\}");

    /** 单份模板体积上限 200KB（工作流 §三 B1 第 4 条）。 */
    private static final int MAX_HTML_BYTES = 200 * 1024;

    /**
     * 全量校验。
     *
     * @param html   模板 HTML
     * @param schema 字段定义
     * @throws BusinessException 406002，`detail` 里带具体原因（行号 / 缺失的 key / 违规标签）
     */
    public void validate(String html, FieldSchema schema) {
        assertSize(html);
        assertWellFormed(html);
        assertTags(html);
        assertCss(html);
        assertPlaceholders(html, schema);
    }

    /**
     * 只校验 well-formed（生成前复查用；标签与 CSS 在保存时已验过）。
     *
     * @param html 模板 HTML
     * @throws BusinessException 406002
     */
    public void assertWellFormed(String html) {
        try {
            newBuilder().parse(new InputSource(new StringReader(html)));
        } catch (SAXParseException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "模板不是合法 XHTML：第 " + e.getLineNumber() + " 行第 "
                            + e.getColumnNumber() + " 列 —— " + e.getMessage());
        } catch (SAXException | java.io.IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "模板不是合法 XHTML：" + e.getMessage());
        }
    }

    /**
     * 提取模板里用到的所有占位符 key（规范化后）。
     *
     * <p>规范化规则：循环块内的 `{{item.xxx}}` 记为 `{blockKey}.item.{xxx}`，
     * 与 {@link FieldSchema#placeholderKeys()} 的命名保持一致。</p>
     *
     * @param html 模板 HTML
     * @return key 集合
     */
    public Set<String> extractPlaceholderKeys(String html) {
        Set<String> keys = new LinkedHashSet<>();
        Deque<String> blockStack = new ArrayDeque<>();
        Matcher m = PLACEHOLDER.matcher(html);
        while (m.find()) {
            String marker = m.group(1);
            String name = m.group(2);
            if ("#".equals(marker)) {
                blockStack.push(name);
                keys.add(name);
            } else if ("/".equals(marker)) {
                if (!blockStack.isEmpty()) {
                    blockStack.pop();
                }
            } else if (name.startsWith("item.")) {
                String itemKey = name.substring("item.".length());
                // `item.index` 是渲染器内置序号，不是 schema 字段，不参与一一对应校验
                if ("index".equals(itemKey)) {
                    continue;
                }
                if (blockStack.isEmpty()) {
                    throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                            "占位符 {{" + name + "}} 出现在循环块之外");
                }
                keys.add(blockStack.peek() + ".item." + itemKey);
            } else {
                keys.add(name);
            }
        }
        return keys;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 体积上限。
     *
     * @param html 模板 HTML
     */
    private void assertSize(String html) {
        if (html == null || html.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "模板内容为空");
        }
        int bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_HTML_BYTES) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "模板体积超上限：" + bytes + " > " + MAX_HTML_BYTES + " 字节");
        }
    }

    /**
     * 标签白名单 + 禁用标签。
     *
     * @param html 模板 HTML
     */
    private void assertTags(String html) {
        Document doc;
        try {
            doc = newBuilder().parse(new InputSource(new StringReader(html)));
        } catch (SAXException | java.io.IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "模板不是合法 XHTML");
        }
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            String tag = el.getTagName().toLowerCase(java.util.Locale.ROOT);
            if (FORBIDDEN_TAGS.contains(tag)) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "模板含禁用标签 <" + tag + ">");
            }
            if (!ALLOWED_TAGS.contains(tag)) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "模板含不支持的标签 <" + tag + ">（允许的标签见 HTML 子集规范）");
            }
        }
    }

    /**
     * 禁用 CSS 检查（对 `<style>` 文本做正则命中）。
     *
     * @param html 模板 HTML
     */
    private void assertCss(String html) {
        NodeList styles;
        try {
            styles = newBuilder().parse(new InputSource(new StringReader(html)))
                    .getElementsByTagName("style");
        } catch (SAXException | java.io.IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "模板不是合法 XHTML");
        }
        List<String> cssBlocks = new ArrayList<>();
        for (int i = 0; i < styles.getLength(); i++) {
            cssBlocks.add(styles.item(i).getTextContent());
        }
        for (String css : cssBlocks) {
            for (Pattern p : FORBIDDEN_CSS) {
                if (p.matcher(css).find()) {
                    throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                            "模板 CSS 使用了不支持的写法：" + p.pattern()
                                    + "（openhtmltopdf 会静默忽略，版式会错乱）");
                }
            }
        }
    }

    /**
     * 占位符与 schema 集合相等校验。
     *
     * @param html   模板 HTML
     * @param schema 字段定义
     */
    private void assertPlaceholders(String html, FieldSchema schema) {
        Set<String> templateKeys = extractPlaceholderKeys(html);
        Set<String> schemaKeys = schema.placeholderKeys().keySet();
        if (templateKeys.equals(schemaKeys)) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>(schemaKeys);
        missing.removeAll(templateKeys);
        Set<String> extra = new LinkedHashSet<>(templateKeys);
        extra.removeAll(schemaKeys);
        StringBuilder detail = new StringBuilder("占位符与字段定义不一致");
        if (!missing.isEmpty()) {
            detail.append("；模板缺少：").append(missing);
        }
        if (!extra.isEmpty()) {
            detail.append("；模板多出：").append(extra);
        }
        throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, detail.toString());
    }

    /**
     * 建一个禁用外部实体的 DOM 解析器（防 XXE）。
     *
     * @return DocumentBuilder
     */
    private DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ThrowingErrorHandler());
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器初始化失败", e);
        }
    }

    /**
     * 把 SAX 错误直接抛出去（默认实现只打印，不中断解析）。
     */
    private static final class ThrowingErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException e) {
            // 警告不算错误
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}
