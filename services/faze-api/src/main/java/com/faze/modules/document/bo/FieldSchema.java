/*
 * 职责：模板字段定义（`doc_template.field_schema`）的解析与校验（段 4 B2）。
 * 调用方：TemplateHtmlValidator（占位符与字段一一对应校验）、A-18 保存模板、App 动态表单渲染。
 * 数据来源：doc_template.field_schema（JSON 文本），结构见《第 4 段工作流》§三 B2。
 * 注意：
 *   1. `type` **只允许 6 种**：text / textarea / number / date / select / array。
 *      不许扩展——每加一种都要在 App 端加渲染分支，是「模板能存但 App 渲染不出」的典型来源。
 *   2. `array` 必须带 `itemSchema.fields`，否则循环块里的 `{{item.xxx}}` 无处校验。
 *   3. 解析失败一律抛 406002（表单校验失败），**不吞异常、不降级成空 schema**：
 *      空 schema 会让「占位符与字段对应」的校验全部通过，等于校验器失效。
 *   4. 本类是不可变值对象，`parse` 之后只读。
 */
package com.faze.modules.document.bo;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 字段定义（表单怎么渲染、占位符怎么替换）。
 */
public final class FieldSchema {

    /** 允许的 6 种字段类型。 */
    public static final Set<String> ALLOWED_TYPES = Set.of(
            "text", "textarea", "number", "date", "select", "array");

    /** 单份模板的字段数上限：防一次提交 5000 个字段把校验器与 App 拖死。 */
    private static final int MAX_FIELDS = 200;

    /** 循环块嵌套深度上限（本段只支持一层 array，留余量给将来）。 */
    private static final int MAX_DEPTH = 2;

    private final List<FieldDef> fields;

    private FieldSchema(List<FieldDef> fields) {
        this.fields = List.copyOf(fields);
    }

    /**
     * 解析 `field_schema` JSON。
     *
     * @param root 已解析的 JSON 根节点
     * @return 字段定义
     * @throws BusinessException 406002（结构不合法）
     */
    public static FieldSchema parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return new FieldSchema(List.of());
        }
        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode == null || !fieldsNode.isArray()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "field_schema.fields 必须是数组");
        }
        List<FieldDef> list = new ArrayList<>();
        for (JsonNode node : fieldsNode) {
            list.add(parseField(node, 1));
        }
        if (list.size() > MAX_FIELDS) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "字段数超上限：" + list.size() + " > " + MAX_FIELDS);
        }
        return new FieldSchema(list);
    }

    /**
     * 顶层字段列表。
     *
     * @return 不可变列表
     */
    public List<FieldDef> fields() {
        return fields;
    }

    /**
     * 所有可被 `{{xxx}}` 引用的 key（含 array 的 `item.xxx`）。
     *
     * <p>这是「占位符 ↔ 字段」一一对应校验的**唯一依据**：模板里的每个占位符都必须在这个集合里。</p>
     *
     * @return key → 字段定义
     */
    public Map<String, FieldDef> placeholderKeys() {
        Map<String, FieldDef> keys = new LinkedHashMap<>();
        for (FieldDef f : fields) {
            keys.put(f.key(), f);
            if (f.itemFields() != null) {
                for (FieldDef item : f.itemFields()) {
                    keys.put(f.key() + ".item." + item.key(), item);
                }
            }
        }
        return keys;
    }

    /**
     * 按 key 查顶层字段。
     *
     * @param key 字段 key
     * @return 字段定义
     */
    public Optional<FieldDef> find(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /**
     * 必填字段的 key 集合（生成前表单校验用）。
     *
     * @return 必填 key
     */
    public Set<String> requiredKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (FieldDef f : fields) {
            if (f.required()) {
                keys.add(f.key());
            }
        }
        return keys;
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /**
     * 解析单个字段。
     *
     * @param node  JSON 节点
     * @param depth 嵌套深度
     * @return 字段定义
     */
    private static FieldDef parseField(JsonNode node, int depth) {
        if (node == null || !node.isObject()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "字段定义必须是对象");
        }
        String key = text(node, "key");
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "字段缺少 key");
        }
        if (!key.matches("^[a-z][a-zA-Z0-9_]{0,49}$")) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "字段 key 非法（须小驼峰或下划线、首字母小写、≤50 字符）：" + key);
        }
        String type = text(node, "type");
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                    "字段 " + key + " 的 type 非法：" + type + "（只允许 " + ALLOWED_TYPES + "）");
        }
        List<FieldDef> itemFields = null;
        if ("array".equals(type)) {
            if (depth >= MAX_DEPTH) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "array 嵌套超过 " + MAX_DEPTH + " 层：" + key);
            }
            JsonNode itemSchema = node.get("itemSchema");
            JsonNode subFields = itemSchema == null ? null : itemSchema.get("fields");
            if (subFields == null || !subFields.isArray() || subFields.isEmpty()) {
                throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID,
                        "array 字段 " + key + " 缺少 itemSchema.fields");
            }
            itemFields = new ArrayList<>();
            for (JsonNode sub : subFields) {
                itemFields.add(parseField(sub, depth + 1));
            }
        }
        return new FieldDef(key, text(node, "label"), type, bool(node, "required"),
                integer(node, "maxLength"), integer(node, "minItems"), integer(node, "maxItems"),
                itemFields == null ? List.of() : List.copyOf(itemFields));
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && n.asBoolean(false);
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asInt();
    }

    /**
     * 单个字段定义。
     *
     * @param key        占位符 key（模板里写 `{{key}}`）
     * @param label      表单标签
     * @param type       text / textarea / number / date / select / array
     * @param required   是否必填
     * @param maxLength  文本最大长度
     * @param minItems   array 最少条目
     * @param maxItems   array 最多条目
     * @param itemFields array 的条目字段
     */
    public record FieldDef(String key, String label, String type, boolean required,
                           Integer maxLength, Integer minItems, Integer maxItems,
                           List<FieldDef> itemFields) {
    }
}
