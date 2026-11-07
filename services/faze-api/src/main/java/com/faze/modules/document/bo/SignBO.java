/*
 * 职责：叠章入参（段 4 D2 `POST /app/documents/{id}/sign`）。
 * 调用方：AppDocumentController。
 * 数据来源：App 预览页（E4/E6）用户拖拽后的坐标。
 * 注意：
 *   1. 坐标是**屏幕坐标系**（原点在页面左上角）的 pt，落图时服务端换算成 PDF 的左下原点
 *      （`pdfY = basePageH - (posY + height)`）——这是本段第二容易写错的地方。
 *   2. `basePageW/H` 必须是**用户看到的这一页的真实尺寸**（来自 `/preview` 的 `widthPt/heightPt`）。
 *      它与 PDF 实际页面尺寸不一致时按比例缩放，所以**不能写死 595×842**。
 *   3. 服务端做边界校验：`posX + width <= basePageW`、`posY + height <= basePageH`，
 *      越界直接拒绝（否则签名会画到页面外，肉眼看不到却已入库）。
 */
package com.faze.modules.document.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 叠章入参。
 */
@Data
public class SignBO {

    /** 用签名库里的哪一个。 */
    @NotNull(message = "签名不能为空")
    @Positive(message = "签名主键非法")
    private Long signatureId;

    /** 第几页，从 1 开始。 */
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNo;

    /** 相对页面左上角的 x（pt）。 */
    @NotNull(message = "横坐标不能为空")
    @Min(value = 0, message = "横坐标不能为负")
    private Double posX;

    /** 相对页面左上角的 y（pt）。 */
    @NotNull(message = "纵坐标不能为空")
    @Min(value = 0, message = "纵坐标不能为负")
    private Double posY;

    /** 签名宽度（pt）。 */
    @NotNull(message = "宽度不能为空")
    @Positive(message = "宽度必须大于 0")
    private Double width;

    /** 签名高度（pt）。 */
    @NotNull(message = "高度不能为空")
    @Positive(message = "高度必须大于 0")
    private Double height;

    /** 页面显示宽度（pt）。 */
    @NotNull(message = "基准页宽不能为空")
    @Positive(message = "基准页宽必须大于 0")
    private Double basePageW;

    /** 页面显示高度（pt）。 */
    @NotNull(message = "基准页高不能为空")
    @Positive(message = "基准页高必须大于 0")
    private Double basePageH;
}
