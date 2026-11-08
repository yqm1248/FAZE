/*
 * 职责：TemplateRenderer 的单测（段 4 C2 自查清单）。
 * 调用方：`mvn test`。
 * 数据来源：内联的简化模板 + schema + 表单 JSON（不读库、不起 Spring）。
 * 注意：
 *   1. 断言的是**渲染出的 XHTML 源串**（如 `A&amp;B`），不是 PDF 里的显示文本。
 *      「`A&B` 显示正确」由 `4-A1` 打开 PDF 核对。
 *   2. 转义只许一次：断言里同时检查 `&amp;amp;` **不出现**——二次转义是最容易犯且最难看在眼里的错。
 */
package com.faze.modules.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.bo.FieldSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 占位符替换单测。
 */
class TemplateRendererTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SCHEMA = """
            {"version":1,"fields":[
              {"key":"title","label":"标题","type":"text","required":true},
              {"key":"facts","label":"事实","type":"textarea","required":true},
              {"key":"plaintiffs","label":"原告","type":"array","required":true,"minItems":1,"maxItems":5,
               "itemSchema":{"fields":[
                 {"key":"name","label":"姓名","type":"text","required":true},
                 {"key":"phone","label":"电话","type":"text","required":true}]}}
            ]}
            """;

    private static final String TEMPLATE = """
            <html><head><meta charset="utf-8" /><style>body { font-family: 'FangSong'; }</style></head>
            <body>
            <h1>{{title}}</h1>
            <p>{{#plaintiffs}}{{item.name}}（{{item.phone}}）{{/plaintiffs}}</p>
            <p>{{facts}}</p>
            </body></html>
            """;

    private final TemplateRenderer renderer = new TemplateRenderer();

    private FieldSchema schema() {
        return FieldSchema.parse(JSON.readTree(SCHEMA));
    }

    private String render(String formJson) {
        return renderer.render(TEMPLATE, schema(), JSON.readTree(formJson));
    }

    /**
     * 正常路径：三个原告循环出三段、文本字段被替换、无残留。
     */
    @Test
    void rendersArrayAndText() {
        String html = render("""
                {"title":"民事起诉状","facts":"被告欠款未还。",
                 "plaintiffs":[{"name":"张三","phone":"13800000001"},
                               {"name":"李四","phone":"13800000002"},
                               {"name":"王五","phone":"13800000003"}]}
                """);
        assertTrue(html.contains("民事起诉状"));
        assertEquals(3, countOccurrences(html, "张三") + countOccurrences(html, "李四")
                + countOccurrences(html, "王五"));
        assertFalse(html.contains("{{"), "不应残留占位符");
    }

    /**
     * 特殊字符转义：`A&B` 变成 `A&amp;B`，且**不能二次转义**成 `A&amp;amp;B`。
     */
    @Test
    void escapesAmpersandExactlyOnce() {
        String html = render("""
                {"title":"A&B","facts":"<script>alert(1)</script>",
                 "plaintiffs":[{"name":"<张三>","phone":"13800000001"}]}
                """);
        assertTrue(html.contains("A&amp;B"), "& 未转义");
        assertFalse(html.contains("A&amp;amp;B"), "发生了二次转义");
        assertTrue(html.contains("&lt;script&gt;"), "尖括号未转义");
        assertFalse(html.contains("<script>"), "脚本标签未被转义，存在注入风险");
    }

    /**
     * 多行文本：换行变 `<br/>`（先转义再替换，否则 `&` 会被二次处理）。
     */
    @Test
    void convertsNewlinesToBreaks() {
        String html = render("""
                {"title":"t","facts":"第一行\\n第二行",
                 "plaintiffs":[{"name":"张三","phone":"13800000001"}]}
                """);
        assertTrue(html.contains("第一行<br/>第二行"), "换行未转成 <br/>");
    }

    /**
     * 必填缺失 → 406002（生成前再校验一次，不能只靠 App 端）。
     */
    @Test
    void missingRequiredFieldRejected() {
        String form = """
                {"title":"","facts":"x","plaintiffs":[{"name":"张三","phone":"1"}]}
                """;
        BusinessException ex = assertThrows(BusinessException.class, () -> render(form));
        assertEquals(ErrorCode.DOCUMENT_FORM_INVALID, ex.getErrorCode());
    }

    /**
     * array 低于 minItems → 明确报错（不静默截断/跳过）。
     */
    @Test
    void arrayBelowMinItemsRejected() {
        String form = """
                {"title":"t","facts":"x","plaintiffs":[]}
                """;
        BusinessException ex = assertThrows(BusinessException.class, () -> render(form));
        assertEquals(ErrorCode.DOCUMENT_FORM_INVALID, ex.getErrorCode());
    }

    /**
     * array 超过 maxItems → 明确报错（不静默截断）。
     */
    @Test
    void arrayAboveMaxItemsRejected() {
        String form = """
                {"title":"t","facts":"x","plaintiffs":[
                  {"name":"a","phone":"1"},{"name":"b","phone":"2"},{"name":"c","phone":"3"},
                  {"name":"d","phone":"4"},{"name":"e","phone":"5"},{"name":"f","phone":"6"}]}
                """;
        BusinessException ex = assertThrows(BusinessException.class, () -> render(form));
        assertEquals(ErrorCode.DOCUMENT_FORM_INVALID, ex.getErrorCode());
    }

    /**
     * 模板引用了 schema 没有的字段 → 406002（不是静默替换成空）。
     */
    @Test
    void undefinedPlaceholderRejected() {
        String badTemplate = TEMPLATE.replace("{{title}}", "{{title}}{{not_defined}}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> renderer.render(badTemplate, schema(), JSON.readTree("""
                        {"title":"t","facts":"x","plaintiffs":[{"name":"a","phone":"1"}]}
                        """)));
        assertEquals(ErrorCode.DOCUMENT_FORM_INVALID, ex.getErrorCode());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
