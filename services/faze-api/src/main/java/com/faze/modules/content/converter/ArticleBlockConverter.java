/*
 * 职责：文章内容的 JSON 块解析（`article.content` / `article_translation.content`）。
 * 调用方：modules/content/service/ArticleService。
 * 数据来源：库内 JSON 块数组字符串（结构见《技术路线》§5.8 的 18b 示例）。
 * 注意：
 *   1. **解析失败不抛异常**：一段脏 JSON 不该让整篇文章打不开——记 warn 并返回空块列表，
 *      页面显示「暂无内容」而不是崩溃（与 G3.2 的 specialties 解析同一口径）。
 *   2. 未知 `type` 的块保留 `type`、其余字段为空：运营后续加新块类型时，
 *      旧客户端忽略它即可，新客户端才渲染；服务端不因为「不认识」而丢掉整篇。
 *   3. 只做「JSON → VO」的搬运，不做字段改写（签名 URL、法条原文由 Service 补）。
 */
package com.faze.modules.content.converter;

import com.faze.modules.content.vo.ArticleBlockVO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 文章内容块转换器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleBlockConverter {

    private final JsonMapper jsonMapper;

    /**
     * 解析正文 JSON 为块列表。
     *
     * @param raw JSON 块数组字符串
     * @return 块列表；空值或解析失败返回空列表
     */
    public List<ArticleBlockVO> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("文章正文 JSON 解析失败，按空处理 rawLength={}", raw.length());
            return List.of();
        }
        if (root == null || !root.isArray()) {
            log.warn("文章正文不是数组，按空处理");
            return List.of();
        }
        List<ArticleBlockVO> blocks = new ArrayList<>();
        for (JsonNode node : root) {
            ArticleBlockVO block = toBlock(node);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    /**
     * 单个 JSON 节点 → 块 VO。
     *
     * @param node 节点
     * @return 块；不是对象返回 null
     */
    private ArticleBlockVO toBlock(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        ArticleBlockVO block = new ArticleBlockVO();
        block.setType(textOf(node, "type"));
        block.setText(textOf(node, "text"));
        if (node.hasNonNull("level")) {
            block.setLevel(node.get("level").asInt());
        }
        if (node.hasNonNull("ordered")) {
            block.setOrdered(node.get("ordered").asBoolean());
        }
        if (node.hasNonNull("items") && node.get("items").isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : node.get("items")) {
                items.add(item.asText());
            }
            block.setItems(items);
        }
        block.setLawName(textOf(node, "lawName"));
        block.setArticleNo(textOf(node, "articleNo"));
        if (node.hasNonNull("lawItemId")) {
            block.setLawItemId(node.get("lawItemId").asLong());
        }
        block.setUrl(textOf(node, "ossKey"));
        block.setAlt(textOf(node, "alt"));
        block.setCaption(textOf(node, "caption"));
        return block;
    }

    /**
     * 取文本字段。
     *
     * @param node  对象节点
     * @param field 字段名
     * @return 文本；缺失或空返回 null
     */
    private String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text : null;
    }
}
