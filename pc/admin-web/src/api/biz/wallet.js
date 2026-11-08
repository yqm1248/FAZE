/*
 * 职责：PC A-07 余额与充值的接口封装（第 1 段 F3，契约见《F2+F3 接口冻结契约》§四 A-07）。
 * 调用方：src/views/biz/wallet/index.vue。
 * 数据来源：faze-api `/api/v1/admin/wallets/**` 与 `/api/v1/admin/balance-flows`。
 * 注意：
 *   1. **幂等键走 HTTP 头 `Idempotency-Key`**，不在 body 里（接口规范 §6.4）。
 *      键由调用方生成并在「同一次提交的重试」中复用，本文件只负责把它塞进头。
 *   2. `POST /api/v1/admin/wallet/recharge`（body 带 userId，D 组已验收）**仍然可用但已 deprecated**，
 *      本页一律走新的 `/wallets/{userId}/recharge`，不要为了省事回退到旧路径。
 *   3. 金额是整数分：充值 `amountCents` > 0；调账 `deltaCents` ≠ 0（可负）。
 *   4. 入参的 `userId` / `amountCents` 里，**userId 是字符串**（BIGINT 序列化成字符串），
 *      直接拼进路径，不做 Number()；`amountCents` 是金额（安全范围内），按数字传。
 *   5. 列表顶层 rows / total；钱包与写接口是 R<T>（`res.data`）。
 *   6. 成功返回里 `duplicated === true` 表示命中了幂等、**没有重复入账**——这是成功语义，
 *      页面应提示「该凭证已处理，未重复入账」，不能当错误报。
 */
import request from '@/api/biz/request'

/**
 * 查询指定用户的账户余额（余额来自 balance_flow 汇总，不是缓存字段）。
 *
 * @param {string} userId 用户 ID（字符串，勿转数字）
 * @returns {Promise<{code:number,msg:string,data:{userId:string,balanceCents:number,frozenCents:number,totalRechargeCents:number,totalConsumeCents:number,lastFlowAt:string|null}}>} 钱包
 */
export function getWallet(userId) {
  return request({
    url: `/api/v1/admin/wallets/${userId}`,
    method: 'get'
  })
}

/**
 * 手工充值。
 *
 * @param {string} userId 用户 ID（字符串）
 * @param {{amountCents:number, reason:string, bizNo:string, remark?:string}} data 充值内容（金额为分，> 0）
 * @param {string} idempotencyKey 幂等键（`newIdempotencyKey('recharge')` 生成，同一次提交的重试必须复用）
 * @returns {Promise<{code:number,msg:string,data:{flowId:string,balanceCents:number,bizNo:string,createdAt:string,duplicated:boolean}}>} 入账结果
 */
export function rechargeWallet(userId, data, idempotencyKey) {
  return request({
    url: `/api/v1/admin/wallets/${userId}/recharge`,
    method: 'post',
    data,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/**
 * 手工调账（正负均可）。
 *
 * @param {string} userId 用户 ID（字符串）
 * @param {{deltaCents:number, reason:string, remark?:string}} data 调账内容（变动的分为单位，≠ 0）
 * @param {string} idempotencyKey 幂等键（`newIdempotencyKey('adjust')` 生成，重试必须复用）
 * @returns {Promise<{code:number,msg:string,data:{flowId:string,balanceCents:number,bizNo:string,createdAt:string,duplicated:boolean}}>} 调账结果
 */
export function adjustWallet(userId, data, idempotencyKey) {
  return request({
    url: `/api/v1/admin/wallets/${userId}/adjust`,
    method: 'post',
    data,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/**
 * 分页查询余额流水。
 *
 * @param {object} params 查询条件
 * @param {string} params.userId 用户 ID（必填，字符串）
 * @param {number} params.pageNum 页码（从 1 开始）
 * @param {number} params.pageSize 每页条数
 * @param {string} [params.bizType] 业务类型（**契约未冻结枚举**，按服务端原值精确匹配）
 * @param {string} [params.beginTime] 时间起（ISO date-time）
 * @param {string} [params.endTime] 时间止（ISO date-time）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 流水列表
 */
export function listBalanceFlows(params) {
  return request({
    url: '/api/v1/admin/balance-flows',
    method: 'get',
    params
  })
}
