/*
 * 职责：律师在线状态入参（`PUT /app/workbench/online-status`，G3.4）。
 * 调用方：modules/trade/controller/WorkbenchController。
 * 数据来源：请求体。
 * 注意：
 *   1. 只接受 online / busy / offline 三个值，**在 Service 里用白名单校验**：
 *      用 `@Pattern` 也能校验，但白名单集合放在 Service 里与 `ALLOWED_ONLINE` 常量同源，
 *      改值域时只改一处。
 *   2. 不接收 lawyerId：律师身份来自 token。
 */
package com.faze.modules.trade.bo;

import lombok.Data;

/**
 * 在线状态入参。
 */
@Data
public class OnlineStatusBO {

    /** 目标状态：online / busy / offline。 */
    private String onlineStatus;
}
