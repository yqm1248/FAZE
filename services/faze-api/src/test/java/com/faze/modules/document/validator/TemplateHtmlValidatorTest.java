/*
 * 职责：TemplateHtmlValidator + FieldSchema 的单测（段 4 B2 的自查清单）。
 * 调用方：`mvn test`。
 * 数据来源：内联的合法/非法模板与 schema 样例（不读库、不起 Spring）。
 * 注意：
 *   1. 每个用例对应工作流 §三 B2「自查」里的一条，**用例名直接写结论**，
 *      失败时不用回头翻文档才知道漏了什么。
 *   2. 「占位符多一个/少一个」必须断言错误信息里**点出了具体 key**——
 *      只说「不一致」等于让运营自己去 2000 个占位符里找。
 */
package com.faze.modules.document.validator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.bo.FieldSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模板校验器单测。
 */
class TemplateHtmlValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SCHEMA_JSON = """
            {"version":1,"fields":[
              {"key":"title","label":"标题","type":"text","required":true,"maxLength":100},
              {"key":"plaintiff_name","label":"原告姓名","type":"text","required":true},
              {"key":"plaintiffs","label":"原告信息","type":"array","required":true,"minItems":1,"maxItems":5,
               "itemSchema":{"fields":[
                 {"key":"name","label":"姓名","type":"text"},
                 {"key":"phone","label":"电话","type":"text"}]}}
            ]}
            """;

    private static final String VALID_HTML = """
            <html><head><style>
              @page { size: A4; margin: 72pt 72pt 72pt 90pt; }
              body { font-family: 'FangSong'; font-size: 16pt; line-height: 28pt; }
            </style></head><body>
              <h1>{{title}}</h1>
              <p>原告：{{plaintiff_name}}</p>
              <div>{{#plaintiffs}}<p>{{item.name}} {{item.phone}}</p>{{/plaintiffs}}</div>
            </body></html>
            """;

    private final TemplateHtmlValidator validator = new TemplateHtmlValidator();

    /**
     * 合法模板 + 合法 schema → 通过。
     */
    @Test
    void validTemplatePasses() {
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        assertDoesNotThrow(() -> validator.validate(VALID_HTML, schema));
    }

    /**
     * 6 种 type 的 schema 都能解析（每个新类型都要 App 有渲染分支，所以先确认解析层不挑）。
     */
    @Test
    void allSixFieldTypesParse() {
        String json = """
                {"version":1,"fields":[
                  {"key":"a","label":"A","type":"text"},
                  {"key":"b","label":"B","type":"textarea"},
                  {"key":"c","label":"C","type":"number"},
                  {"key":"d","label":"D","type":"date"},
                  {"key":"e","label":"E","type":"select"},
                  {"key":"f","label":"F","type":"array","itemSchema":{"fields":[
                     {"key":"x","label":"X","type":"text"}]}}
                ]}
                """;
        FieldSchema schema = FieldSchema.parse(JSON.readTree(json));
        assertTrue(schema.fields().size() == 6);
    }

    /**
     * `<script>` 必须被拒（安全 + openhtmltopdf 不执行它）。
     */
    @Test
    void scriptTagRejected() {
        String html = VALID_HTML.replace("</body>", "<script>alert(1)</script></body>");
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(html, schema));
        assertTrue(ex.getMessage().contains("script") || String.valueOf(ex.getDetail()).contains("script"));
    }

    /**
     * `display: flex` 必须被拒——openhtmltopdf 不支持且**静默忽略**，不拒就会做出竖排版式。
     */
    @Test
    void flexRejected() {
        String html = VALID_HTML.replace("body {", "body { display: flex;");
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(html, schema));
        assertEqualsCode(ErrorCode.DOCUMENT_FORM_INVALID, ex);
    }

    /**
     * `<br>` 不带斜杠 → 拒，且错误信息带行号（运营要知道改哪一行）。
     */
    @Test
    void unclosedBrRejectedWithLineNumber() {
        String html = VALID_HTML.replace("</body>", "<p>第一行<br>第二行</p></body>");
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.assertWellFormed(html));
        String detail = String.valueOf(ex.getDetail());
        assertTrue(detail.contains("行"), "错误信息应带行号：" + detail);
    }

    /**
     * 模板少一个字段 → 拒，且点出缺哪个。
     */
    @Test
    void placeholderMissingRejectedWithKey() {
        String html = VALID_HTML.replace("{{plaintiff_name}}", "");
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(html, schema));
        assertTrue(String.valueOf(ex.getDetail()).contains("plaintiff_name"),
                "应指出缺少的 key：" + ex.getDetail());
    }

    /**
     * 模板多一个未定义占位符 → 拒（否则渲染时被替换成空，静默丢内容）。
     */
    @Test
    void placeholderExtraRejectedWithKey() {
        String html = VALID_HTML.replace("</body>", "<p>{{not_in_schema}}</p></body>");
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(html, schema));
        assertTrue(String.valueOf(ex.getDetail()).contains("not_in_schema"),
                "应指出多出的 key：" + ex.getDetail());
    }

    /**
     * array 缺 `itemSchema.fields` → 解析阶段就拒。
     */
    @Test
    void arrayWithoutItemSchemaRejected() {
        String json = """
                {"version":1,"fields":[{"key":"items","label":"条目","type":"array"}]}
                """;
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FieldSchema.parse(JSON.readTree(json)));
        assertEqualsCode(ErrorCode.DOCUMENT_FORM_INVALID, ex);
    }

    /**
     * 未定义的 type → 拒（不许扩展第 7 种）。
     */
    @Test
    void unknownTypeRejected() {
        String json = """
                {"version":1,"fields":[{"key":"a","label":"A","type":"rich-text"}]}
                """;
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FieldSchema.parse(JSON.readTree(json)));
        assertEqualsCode(ErrorCode.DOCUMENT_FORM_INVALID, ex);
    }

    /**
     * 非法标签（白名单之外）→ 拒。
     */
    @Test
    void unknownTagRejected() {
        String html = VALID_HTML.replace("</body>", "<canvas></canvas></body>");
        FieldSchema schema = FieldSchema.parse(JSON.readTree(SCHEMA_JSON));
        BusinessException ex = assertThrows(BusinessException.class, () -> validator.validate(html, schema));
        assertEqualsCode(ErrorCode.DOCUMENT_FORM_INVALID, ex);
    }

    private static void assertEqualsCode(ErrorCode expected, BusinessException ex) {
        assertTrue(ex.getErrorCode() == expected,
                "错误码应为 " + expected + "，实际 " + ex.getErrorCode());
    }
}
