/**
 * 文书送达接口（段 5 C 组：C2 送达页 / C3 回执 / C4 列表）。
 *
 * 职责：`features/case/pages/CaseDeliveryPage.tsx` 的唯一数据入口。
 * 调用方：送达页。
 * 数据来源：`/api/v1/app/case-deliveries` 系列（`AppDeliveryController`）。
 * 注意：
 *   1. 送达只送**平台自有文书**，不做法院转达；接口里没有、也不该有法院侧字段。
 *   2. 签收接口**不传设备与 IP**：服务端从 `X-Device-Id` / `X-Client-Platform` 与
 *      `X-Forwarded-For` 取（客户端传的「设备信息」没有证据价值）。
 *   3. 已签收再签返回 `407007`，且**不覆盖首次签收时间**（幂等语义）。
 *   4. 分页响应是 C 端三段式（`list / total / pageNum / pageSize / hasMore`）。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 送达方式 code（3 种，与后端 `DeliveryMethod` 一致）。 */
export type DeliveryMethodCode = 'platform' | 'sms_pickup' | 'mail';

/** 送达状态。 */
export type DeliveryStatusCode = 'pending' | 'signed';

/** 送达记录（后端 `DeliveryRowVO`，列表行与回执共用）。 */
export type DeliveryRow = {
  id: string;
  caseId: string;
  caseNo: string | null;
  caseTitle: string | null;
  documentId: string;
  documentTitle: string | null;
  method: DeliveryMethodCode;
  status: DeliveryStatusCode;
  /** 回执号（SD 开头，发起送达时即生成）。 */
  receiptNo: string;
  /** 发起送达时间（UTC）。 */
  sentAt: string;
  /** 签收时间（UTC）；未签收为 null。 */
  signedAt: string | null;
  /** 签收设备；未签收为 null。 */
  signDevice: string | null;
  lawyerName: string | null;
};

/** C 端分页响应。 */
export type DeliveryPage = {
  list: DeliveryRow[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/**
 * 拉我的送达列表。
 *
 * @param caseId 案件 id（可空：空表示全部案件）
 * @param status 状态筛选（可空：空表示全部）
 * @param pageSize 每页条数
 * @returns 分页列表
 */
export function fetchDeliveries(
  caseId: string | null,
  status: DeliveryStatusCode | null,
  pageSize = 50,
): Promise<ApiResult<DeliveryPage>> {
  const params = new URLSearchParams({ pageNum: '1', pageSize: String(pageSize) });
  if (caseId !== null) {
    params.set('caseId', caseId);
  }
  if (status !== null) {
    params.set('status', status);
  }
  return get<DeliveryPage>(`/api/v1/app/case-deliveries?${params.toString()}`);
}

/**
 * 拉单条送达详情（回执）。
 *
 * @param deliveryId 送达记录 id
 * @returns 送达记录
 */
export function fetchDelivery(deliveryId: string): Promise<ApiResult<DeliveryRow>> {
  return get<DeliveryRow>(`/api/v1/app/case-deliveries/${encodeURIComponent(deliveryId)}`);
}

/**
 * 签收一份文书。
 *
 * @param deliveryId 送达记录 id
 * @returns 成功时空数据；已签收返回业务码 407007
 */
export function confirmDelivery(deliveryId: string): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/case-deliveries/${encodeURIComponent(deliveryId)}/confirm`, {});
}

/**
 * 修改送达方式（只有未签收时可改）。
 *
 * @param deliveryId 送达记录 id
 * @param method 新方式
 * @returns 成功时空数据
 */
export function chooseDeliveryMethod(
  deliveryId: string,
  method: DeliveryMethodCode,
): Promise<ApiResult<null>> {
  return request<null>(`/api/v1/app/case-deliveries/${encodeURIComponent(deliveryId)}/method`, {
    method: 'PUT',
    body: { method },
  });
}
