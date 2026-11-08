/*
 * 职责：PC A-27「法条库」的接口封装（第 3 段 H1）。
 * 调用方：src/views/biz/ai/law-library/index.vue、law-library/import-dialog.vue。
 * 数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
 *   GET    /api/v1/admin/law-items          列表（RuoYi 口径 rows / total）
 *   GET    /api/v1/admin/law-items/{id}     详情（含全文 content）
 *   PUT    /api/v1/admin/law-items/{id}     修正 = 写新版本（不改旧行）
 *   POST   /api/v1/admin/law-items/import   批量导入（multipart 文件或 JSON 数组）
 *   POST   /api/v1/admin/law-items/{id}/disable 标记失效（不做物理删除）
 * 注意：
 *   1. **这里没有「新增单条」和「导出」**：工作流 H1 明确不做（负责人从后台导入，不手录单条）。
 *      设计稿的「新增法条」「导出条文」「导出筛选结果」三个按钮在本段删除，接口也不留占位函数。
 *   2. 列表行**不含全文 content**，只有 `contentSummary`（≤200 字、已压平空白）；
 *      全文只在 `getLawItem(id)` 的详情里返回——列表页不要拿摘要当全文展示。
 *   3. `aiRefCount` = B4 口径的 AI 引用次数（assistant 消息 refs 中被引用的次数，含已软删会话）。
 *      未被引用过时后端直接回 0，前端不要自己算（那是两套口径）。
 *   4. 导入的幂等键是 `(lawCode, articleNo, effectiveDate)`：重复条目后端计入 skipped 不报错。
 *   5. 修正（PUT）的语义是「旧行 expire_date=修正生效日 / status=amended / is_enabled=0，
 *      同时 INSERT 一条新的 active 行」，两个动作同一事务。**前端不要自己拼两行**，只提交修正内容。
 *   6. 列表查询的 `isEnabled` 是后端扩展参数：不传=只看启用中（默认），传 0=只看已失效。
 *      设计稿的「复核状态」筛选已被删除（§零.3 第 9 项），本页用它做「启用状态」筛选。
 */
import request from '@/api/biz/request'

/** 接口根路径（request.js 会再拼 /biz-api 前缀）。 */
const BASE = '/api/v1/admin/law-items'

/**
 * 分页查询法条列表。
 *
 * @param {{lawCode?:string,articleNo?:string,kw?:string,isEnabled?:string|number,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 口径分页响应
 */
export function listLawItems(query) {
  // 列表是 RuoYi 口径（顶层 rows/total）：拦截器返回的就是 body，不能再取 .data
  return request({
    url: BASE,
    method: 'get',
    params: query
  })
}

/**
 * 查法条详情（含全文与录入人）。
 *
 * @param {string|number} lawItemId 法条主键
 * @returns {Promise<{code:number,data:object}>} 详情；amended / 已停用的旧行也能按 id 查到
 */
export function getLawItem(lawItemId) {
  return request({
    url: `${BASE}/${lawItemId}`,
    method: 'get'
  })
}

/**
 * 修正法条（写新版本，不改旧行）。
 *
 * @param {string|number} lawItemId 被修正的旧法条主键
 * @param {{content:string,effectiveDate:string,keywords?:string,lawName?:string,articleSeq?:number}} data
 *        修正内容；`effectiveDate` 是**修正生效日**（yyyy-MM-dd），必须晚于旧行生效日期
 * @returns {Promise<{code:number,data:string|number}>} 新版本行 id
 */
export function amendLawItem(lawItemId, data) {
  return request({
    url: `${BASE}/${lawItemId}`,
    method: 'put',
    data
  })
}

/**
 * 批量导入法条。
 *
 * 两种入参二选一：
 * - 上传 JSON 文件：`payload` 为 FormData，文件字段名 `file`（≤5MB）；
 * - 直接贴 JSON：`payload` 为数组，按 application/json 提交。
 * 注意：multipart 场景**不要手写 Content-Type**——浏览器要自己补 boundary，写死会解析失败。
 *
 * @param {FormData|Array<object>} payload 上传文件或法条数组
 * @returns {Promise<{code:number,data:{total:number,inserted:number,skipped:number,failed:Array<{index:number,reason:string}>}}>}
 *          导入结果；`index` 是数组下标（0 开始）
 */
export function importLawItems(payload) {
  return request({
    url: `${BASE}/import`,
    method: 'post',
    data: payload,
    // 导入可能比普通查询慢（单次最多 500 条），给到 60 秒；仍同步返回，不做异步任务
    timeout: 60000
  })
}

/**
 * 标记失效（不物理删除：物理删会让历史 AI 回答的 refs 快照悬空）。
 *
 * @param {string|number} lawItemId 法条主键
 * @returns {Promise<{code:number}>} 空响应；重复调用幂等成功
 */
export function disableLawItem(lawItemId) {
  return request({
    url: `${BASE}/${lawItemId}/disable`,
    method: 'post'
  })
}
