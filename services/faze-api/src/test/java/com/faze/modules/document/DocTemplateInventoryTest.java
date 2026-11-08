/*
 * 职责：5 份中文模板的产出验收（段 4 B3）——模板与 schema 是否一一对应、是否合法 XHTML。
 * 调用方：`mvn test`。
 * 数据来源：`assets/doc-templates/*.html` 与其同名 `*.schema.json`（仓库内源文件，非同目录的库数据）。
 * 注意：
 *   1. 这里**不渲染 PDF**：渲染要先做占位符替换（C2），否则 `{{}}` 会被当成普通文本印上去。
 *      渲染验证在 C 组完成后由 `4-A1`/`4-B3` 的端到端验收负责。
 *   2. 断言「必须正好 5 份」——少了说明模板没产出齐；多了说明有没人维护的野模板进仓库。
 *   3. 模板文件在仓库根 `assets/` 下，测试工作目录是 `services/faze-api`，所以是 `../../assets`。
 *      这个相对路径写死是有意的：路径变了测试就红，不会静默跳过。
 */
package com.faze.modules.document;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.modules.document.bo.FieldSchema;
import com.faze.modules.document.validator.TemplateHtmlValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 5 份模板的清单与校验验收。
 */
class DocTemplateInventoryTest {

    private static final Path TEMPLATE_DIR = Path.of("..", "..", "assets", "doc-templates");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TemplateHtmlValidator validator = new TemplateHtmlValidator();

    /**
     * 必须正好 5 份，且每份都能配上同名 schema 并通过校验。
     *
     * @throws IOException 读文件失败
     */
    @Test
    void allTemplatesValidateAgainstTheirSchema() throws IOException {
        assertTrue(Files.isDirectory(TEMPLATE_DIR), "模板目录不存在：" + TEMPLATE_DIR.toAbsolutePath());
        List<Path> htmlFiles;
        try (var stream = Files.list(TEMPLATE_DIR)) {
            htmlFiles = stream.filter(p -> p.getFileName().toString().endsWith(".html"))
                    .collect(Collectors.toList());
        }
        assertEquals(5, htmlFiles.size(), "应有 5 份模板，实际：" + htmlFiles.size());

        for (Path html : htmlFiles) {
            String fileName = html.getFileName().toString();
            String code = fileName.substring(0, fileName.length() - ".html".length());
            Path schemaPath = TEMPLATE_DIR.resolve(code + ".schema.json");
            assertTrue(Files.exists(schemaPath), "缺少 schema：" + schemaPath);
            FieldSchema schema = FieldSchema.parse(JSON.readTree(Files.readString(schemaPath)));
            String content = Files.readString(html);
            assertDoesNotThrow(() -> validator.validate(content, schema), code + " 校验失败");
        }
    }

    /**
     * 模板里不许出现 `<br>` 不带斜杠（XML 解析器会直接拒，报错还看不出是 br 的问题）。
     *
     * @throws IOException 读文件失败
     */
    @Test
    void templatesUseSelfClosingBr() throws IOException {
        try (var stream = Files.list(TEMPLATE_DIR)) {
            for (Path html : stream.filter(p -> p.getFileName().toString().endsWith(".html"))
                    .collect(Collectors.toList())) {
                String content = Files.readString(html);
                String lower = content.toLowerCase();
                int idx = lower.indexOf("<br>");
                assertTrue(idx < 0, html.getFileName() + " 含未自闭合的 <br>");
            }
        }
    }
}
