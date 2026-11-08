/*
 * 职责：文书渲染相关配置（段 4 A2）——中文字体路径、渲染超时。
 * 调用方：infra/pdf/PdfRenderer。
 * 数据来源：application.yml 的 `faze.doc.*`；字体路径可被环境变量覆盖。
 * 注意：
 *   1. **不许硬编码 `C:\Windows\Fonts`**：同一份代码要在开发机、测试机、生产机跑，
 *      字体路径必须可配（工作流 §三 A2 第 3 条）。
 *   2. 段 4 只做中文，字体用「仿宋」(`simfang.ttf`)，**不用「仿宋_GB2312」**——
 *      后者系统不自带，配错的表现是「PDF 里全是方块」，而 openhtmltopdf 不会报错。
 *   3. `boldFontPath` 单独配：`simfang.ttf` 只有 Regular，CSS 的 `font-weight: bold`
 *      在没有 Bold 字重时会**静默不加粗**（不报错），起诉状标题不加粗是明显的版式事故。
 *      没有仿宋粗体时用黑体 `simhei.ttf` 承担 700 字重。
 *   4. 超时是「单次渲染上限」：畸形 HTML（超深嵌套）会把工作线程卡死，
 *      有上限才能失败得干脆（工作流 §三 A2 第 6 条）。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 文书渲染配置。
 *
 * @param fontPath             正文字体文件绝对路径（仿宋 Regular）
 * @param boldFontPath         粗体字体文件绝对路径（仿宋无 Bold 时用黑体）
 * @param renderTimeoutSeconds 单次渲染超时（秒）
 * @param maxPages             单份文书最大页数，超过判为畸形模板
 */
@ConfigurationProperties(prefix = "faze.doc")
public record FazeDocProperties(
        String fontPath,
        String boldFontPath,
        @DefaultValue("10") int renderTimeoutSeconds,
        @DefaultValue("200") int maxPages) {
}
