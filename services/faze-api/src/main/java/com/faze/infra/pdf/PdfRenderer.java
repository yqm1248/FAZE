/*
 * 职责：XHTML → PDF 的唯一渲染通道（段 4 A2）。
 * 调用方：modules/document 的生成 handler（C3）、A-18 的模板预览渲染（B1）。
 * 数据来源：config/properties/FazeDocProperties（字体路径、渲染超时、页数上限）。
 * 注意：
 *   1. **只做渲染，不做业务**：占位符替换在 TemplateRenderer（C2），failure 的落库与错误码在 C4。
 *      本类对外只承诺一件事：给一段合法 XHTML，回一份 PDF 字节。
 *   2. openhtmltopdf 用的是 **XML 解析器**，不是浏览器的容错 HTML 解析器：
 *      `<br>` 必须写 `<br/>`、裸 `&` 必须写 `&amp;`、`<meta charset="utf-8">` 必须自闭合。
 *      不合法会抛 `XRRuntimeException`，本类翻成 506002，**原始异常消息不进响应**（后端规范 §5.3）。
 *   3. 每次 render 新建 builder，实例字段不可变，**本类是线程安全的**。
 *      但 openhtmltopdf 内部有静态字体缓存，首次调用慢（首次会 scan 系统字体）。
 *   4. 超时用独立线程 + `Future.get`：畸形 HTML（超深嵌套）会把渲染线程卡死，
 *      必须有上限；`cancel(true)` 只是尽力中断（XML 解析不保证响应中断），
 *      所以**同时**用页数上限兜底。
 *   5. **字重必须注册两个（400 + 700）**：只注册 400 时 CSS 的 `font-weight: bold`
 *      会静默不加粗、也不报错——起诉状标题不加粗是明显的版式事故。
 *      ⚠️ **`PdfRendererBuilder.FontStyle` 枚举只有 NORMAL / ITALIC / OBLIQUE，没有 BOLD**
 *      （2026-11-07 实测：写 `FontStyle.BOLD` 编译不过）。粗体靠 `useFont(..., 700, NORMAL, ...)`
 *      的 **weight 参数**表达——工作流 §三 A2 示例代码里的 `FontStyle.BOLD` 是错的。
 *   6. **PDFBox 实际是 2.0.24**（openhtmltopdf 1.0.10 传递），不是工作流写的 2.0.30：
 *      2.0.24 没有 `org.apache.pdfbox.Loader` 类（那是 2.0.25+ 才有），只能用
 *      `PDDocument.load(byte[])`（已 deprecated 但可编译）。升级 PDFBox 版本前不要改回 Loader。
 */
package com.faze.infra.pdf;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.properties.FazeDocProperties;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;

/**
 * HTML → PDF 渲染器。
 *
 * <p>本类**不判断业务合法性**：模板占位符、表单必填、状态机都由上层负责。
 * 它只回答「这段 XHTML 能不能变成 PDF、Ping 出来的字体嵌没嵌」。</p>
 */
@Component
@Slf4j
public class PdfRenderer {

    /** 模板里 `font-family` 引用的族名；与 CSS 里的写法必须一致。 */
    public static final String FONT_FAMILY = "FangSong";

    /** 渲染线程数：生成是异步任务，2 个并发足够，多了只会抢内存。 */
    private static final int RENDER_THREADS = 2;

    private final FazeDocProperties properties;
    private final ExecutorService renderExecutor;

    /**
     * 构造渲染器。
     *
     * <p>构造期**不检查字体文件是否存在**：配置缺失（路径为空）是部署错误，立刻失败；
     * 但「配了路径、文件却不存在」按 506003 在渲染时报——这样同一份代码在没装仿宋的机器上
     * 起得来、只在真的生成时失败，错误码可被 C4 归因。</p>
     *
     * @param properties 文书渲染配置
     */
    public PdfRenderer(FazeDocProperties properties) {
        this.properties = properties;
        if (properties.fontPath() == null || properties.fontPath().isBlank()) {
            throw new IllegalStateException("faze.doc.font-path 未配置：文书渲染需要中文字体，拒绝启动");
        }
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "faze-pdf-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.renderExecutor = Executors.newFixedThreadPool(RENDER_THREADS, factory);
    }

    @PreDestroy
    void shutdown() {
        renderExecutor.shutdownNow();
    }

    /**
     * 把 XHTML 渲染成 PDF 字节。
     *
     * <p>前置：`xhtml` 必须是 well-formed XML（见类注释第 2 条）。
     * 副作用：无（不落盘、不写库）。
     *
     * @param xhtml 完整 XHTML 文档字符串（含 `<html>`/`<style>`）
     * @return PDF 字节
     * @throws BusinessException 字体缺失 506003 / 渲染失败 506002 / 超时 506002
     */
    public byte[] render(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID);
        }
        assertFontReadable(properties.fontPath());

        Future<byte[]> future = renderExecutor.submit(() -> doRender(xhtml));
        try {
            byte[] pdf = future.get(properties.renderTimeoutSeconds(), TimeUnit.SECONDS);
            assertPageCount(pdf);
            return pdf;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("PDF 渲染超时（{}s）：模板可能存在畸形嵌套", properties.renderTimeoutSeconds());
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BusinessException be) {
                throw be;
            }
            log.error("PDF 渲染失败", cause);
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    /**
     * 检查 PDF 里是否所有字体都已嵌入。
     *
     * <p>这是 `4-A2` 的隐藏判据：字体没嵌入时，本机装了仿宋看着正常，换台机器就变字形。</p>
     *
     * @param pdf PDF 字节
     * @return 未嵌入的字体名列表；空列表表示全部已嵌入
     */
    public List<String> findUnembeddedFonts(byte[] pdf) {
        List<String> missing = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (font != null && !font.isEmbedded()) {
                        missing.add(font.getName());
                    }
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
        return missing;
    }

    /**
     * 取 PDF 页数（供 C3 的预览渲染与页数上限检查复用）。
     *
     * @param pdf PDF 字节
     * @return 页数
     */
    public int pageCount(byte[] pdf) {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 实际渲染（在线程池里跑）。
     *
     * @param xhtml XHTML 文档
     * @return PDF 字节
     */
    private byte[] doRender(String xhtml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useFont(() -> openFont(properties.fontPath()), FONT_FAMILY, 400,
                PdfRendererBuilder.FontStyle.NORMAL, true);
        String boldPath = resolveBoldPath();
        if (boldPath != null) {
            builder.useFont(() -> openFont(boldPath), FONT_FAMILY, 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
        }
        builder.withHtmlContent(xhtml, null);
        builder.toStream(out);
        builder.run();
        return out.toByteArray();
    }

    /**
     * 解析粗体字体路径。
     *
     * <p>仿宋只有 Regular，`font-weight: bold` 需要另一个字体文件承担 700 字重；
     * 未配置时返回 null（渲染照常，只是不加粗），不阻断生成。</p>
     *
     * @return 可读的粗体字体路径；未配置或不可读时返回 null
     */
    private String resolveBoldPath() {
        String path = properties.boldFontPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        if (!Files.isReadable(Path.of(path))) {
            log.warn("粗体字体不可读，标题将不加粗（不影响生成）：{}", path);
            return null;
        }
        return path;
    }

    /**
     * 打开字体文件流。
     *
     * @param path 字体绝对路径
     * @return 输入流（由 openhtmltopdf 读取并关闭）
     */
    private InputStream openFont(String path) {
        try {
            return new ByteArrayInputStream(Files.readAllBytes(Path.of(path)));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FONT_MISSING);
        }
    }

    /**
     * 校验字体文件可读。
     *
     * @param path 字体路径
     * @throws BusinessException 506003
     */
    private void assertFontReadable(String path) {
        if (path == null || path.isBlank() || !Files.isReadable(Path.of(path))) {
            log.error("中文字体文件不可读：{}（检查 faze.doc.font-path / FAZE_FONT_PATH）", path);
            throw new BusinessException(ErrorCode.DOCUMENT_FONT_MISSING);
        }
    }

    /**
     * 页数上限兜底（防畸形模板把后续处理拖死）。
     *
     * @param pdf PDF 字节
     * @throws BusinessException 506002
     */
    private void assertPageCount(byte[] pdf) {
        int pages = pageCount(pdf);
        if (pages > properties.maxPages()) {
            log.error("PDF 页数超上限：{} > {}", pages, properties.maxPages());
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }
}
