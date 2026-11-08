/*
 * 职责：PC A-06 律师列表的接口封装（第 1 段 F2，契约见《F2+F3 接口冻结契约》§三 A-06）。
 * 调用方：src/views/biz/lawyer/index.vue。
 * 数据来源：faze-api `/api/v1/admin/lawyers**`（lawyer 表，逻辑删除过滤在服务端）。
 * 注意：
 *   1. 本段**不做**违规 / 限流 / 下架 / 清退（段 7），所以只有两个只读接口，没有任何写接口。
 *      页面上的「服务开关」因此是**只读展示**，不提供 toggle——给了可点的开关却没有接口是欺骗使用者。
 *   2. 价格字段是整数分（consultPriceCents / phonePriceCents / documentPriceCents），
 *      展示走 shared/format.js 的 formatCents。
 *   3. `withdrawBankAccountTail` 只是卡号后 4 位，页面不得声称能展示完整卡号（服务端也不给）。
 *   4. licenseImageUrl 是 60 秒签名 URL，只在打开详情抽屉时取。
 *   5. 列表顶层 rows / total；详情是 R<T>（`res.data`）。
 */
import request from '@/api/biz/request'

/**
 * 分页查询律师列表。
 *
 * @param {object} params 查询条件
 * @param {number} params.pageNum 页码（从 1 开始）
 * @param {number} params.pageSize 每页条数
 * @param {string} [params.keyword] 姓名 / 律所（模糊）
 * @param {string} [params.region] 地区
 * @param {string} [params.auditStatus] 入驻审核状态：PENDING|APPROVED|REJECTED
 * @param {string} [params.beginTime] 入驻时间起（ISO date-time）
 * @param {string} [params.endTime] 入驻时间止（ISO date-time）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 列表数据
 */
export function listLawyers(params) {
  return request({
    url: '/api/v1/admin/lawyers',
    method: 'get',
    params
  })
}

/**
 * 查询律师详情（资质、服务价格、服务开关、银行卡尾号、关联用户）。
 *
 * @param {string} id 律师主键（字符串，勿转数字）
 * @returns {Promise<{code:number,msg:string,data:object}>} 详情
 */
export function getLawyerDetail(id) {
  return request({
    url: `/api/v1/admin/lawyers/${id}`,
    method: 'get'
  })
}
