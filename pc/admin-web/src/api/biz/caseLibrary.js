/*
 * 职责：PC A-16 案例库（G1）的接口封装。
 * 调用方：src/views/biz/content/caseLibrary/index.vue。
 * 数据来源：faze-api `/api/v1/admin/case-library/**`。
 * 注意：
 *   1. **列表是 RuoYi 口径**（顶层 `rows` / `total`），其余是 `R<T>`（取 `res.data`）。
 *   2. 新建用 `POST`、保存用 `PUT`：新建成功后返回新 id，再走详情回显。
 *   3. **上线有门禁**：源语言四块正文齐全 + 至少一条法条引用，不满足时服务端返回
 *      409006 / 409005，页面要把错误文案原样提示（别自己编）。
 *   4. 批量下架是 V1 唯一批量操作；**批量导出不做**（页面按钮置灰）。
 */
import request from '@/api/biz/request'

/**
 * 案例列表（分页）。
 *
 * @param {{kw?:string,category?:string,caseType?:string,status?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 分页响应
 */
export function listCaseLibrary(query) {
  return request({
    url: '/api/v1/admin/case-library',
    method: 'get',
    params: query
  })
}

/**
 * 案例详情（含三种译文，用于录入抽屉回显）。
 *
 * @param {string} id 案例主键
 * @returns {Promise<{code:number,data:object}>} 详情
 */
export function getCaseLibraryDetail(id) {
  return request({
    url: `/api/v1/admin/case-library/${id}`,
    method: 'get'
  })
}

/**
 * 新建案例。
 *
 * @param {object} payload 保存入参
 * @returns {Promise<{code:number,data:{id:number}}>} 新案例 id
 */
export function createCaseLibrary(payload) {
  return request({
    url: '/api/v1/admin/case-library',
    method: 'post',
    data: payload
  })
}

/**
 * 保存案例。
 *
 * @param {string} id 案例主键
 * @param {object} payload 保存入参
 * @returns {Promise<{code:number}>} 空响应
 */
export function updateCaseLibrary(id, payload) {
  return request({
    url: `/api/v1/admin/case-library/${id}`,
    method: 'put',
    data: payload
  })
}

/**
 * 设为上线（服务端有门禁）。
 *
 * @param {string} id 案例主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function publishCaseLibrary(id) {
  return request({
    url: `/api/v1/admin/case-library/${id}/publish`,
    method: 'post'
  })
}

/**
 * 下架。
 *
 * @param {string} id 案例主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function offlineCaseLibrary(id) {
  return request({
    url: `/api/v1/admin/case-library/${id}/offline`,
    method: 'post'
  })
}

/**
 * 批量下架。
 *
 * @param {Array<string>} ids 案例主键列表
 * @returns {Promise<{code:number,data:{rows:number}}>} 实际下架条数
 */
export function offlineCaseLibraryBatch(ids) {
  return request({
    url: '/api/v1/admin/case-library/offline',
    method: 'post',
    data: ids
  })
}

/**
 * 检索法条（录入抽屉「法条引用」Tab 用，复用段 3 的公开法条库）。
 *
 * @param {string} kw 关键词（法律名或条号）
 * @returns {Promise<{code:number,data:Array<object>}>} 法条列表
 */
export function searchLawItems(kw) {
  return request({
    url: '/api/v1/pub/law-items',
    method: 'get',
    params: { kw, pageSize: 20 }
  })
}
