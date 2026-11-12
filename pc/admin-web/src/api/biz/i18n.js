/*
 * 职责：PC A-17 多语言翻译管理的接口封装。
 * 调用方：src/views/biz/i18n/index.vue。
 * 数据来源：faze-api `/api/v1/admin/translations`、`/api/v1/admin/i18n-glossary`
 *          （表：faze.article_translation / faze.i18n_glossary，**没有新增表**）。
 * 注意：
 *   1. 两个列表接口返回 **RuoYi 口径**（顶层 rows / total），不是 `R<T>`。
 *      所以页面里直接读 `res.rows` / `res.total`，不要读 `res.data.rows`。
 *   2. 详情与写接口返回 `R<T>`。
 *   3. 译文保存**只提交改动的字段**：`status` 传 null 表示"不改状态"，
 *      `title`/`summary`/`content` 传 null 表示"不改内容"。整对象回传会把并发改过的内容覆盖回旧值。
 *   4. 术语的 `targetText` 传空串是**合法操作**（清空译文、退回待填），传 null 才是"不改"。
 */
import request from '@/api/biz/request'

/**
 * 译文队列（A-17 左栏）。
 *
 * @param {object} params 查询条件：lang / status / keyword / pageNum / pageSize
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 分页结果
 */
export function listTranslations(params) {
  return request({
    url: '/api/v1/admin/translations',
    method: 'get',
    params
  })
}

/**
 * 译文详情（含正文）。
 *
 * @param {string|number} id 译文主键
 * @returns {Promise<{code:number,msg:string,data:object}>} 详情
 */
export function getTranslation(id) {
  return request({
    url: `/api/v1/admin/translations/${id}`,
    method: 'get'
  })
}

/**
 * 保存译文。
 *
 * @param {string|number} id 译文主键
 * @param {object} data 保存内容（title / summary / content / status / translator / translationEngine）
 * @returns {Promise<{code:number,msg:string,data:null}>} 保存结果
 */
export function updateTranslation(id, data) {
  return request({
    url: `/api/v1/admin/translations/${id}`,
    method: 'put',
    data
  })
}

/**
 * 术语表（A-17 右栏）。
 *
 * @param {object} params 查询条件：lang / keyword / pendingOnly / pageNum / pageSize
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 分页结果
 */
export function listGlossary(params) {
  return request({
    url: '/api/v1/admin/i18n-glossary',
    method: 'get',
    params
  })
}

/**
 * 保存术语译文。
 *
 * @param {string|number} id 术语主键
 * @param {object} data 保存内容（targetText / note / status / enabled）
 * @returns {Promise<{code:number,msg:string,data:null}>} 保存结果
 */
export function updateGlossary(id, data) {
  return request({
    url: `/api/v1/admin/i18n-glossary/${id}`,
    method: 'put',
    data
  })
}
