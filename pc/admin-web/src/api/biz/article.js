/*
 * 职责：PC A-14 文章列表 / A-15 文章编辑的接口封装（第 2 段 G9.3 / G9.4）。
 * 调用方：src/views/biz/content/article/index.vue、edit.vue。
 * 数据来源：faze-api `/api/v1/admin/articles/**`。
 * 注意：
 *   1. 列表是 RuoYi 口径（rows / total），`listArticles` 返回 `res.data`；
 *      其余是 `R<T>`。
 *   2. **上架是独立动作**（`publishArticle`）：编辑页保存按钮不带 published，
 *      上架校验（四语言齐全）只在 publish 接口里做，页面不能绕过。
 *   3. `content` 字段是**JSON 块数组字符串**：编辑页负责拼装，保存原样提交。
 *   4. 语言完成度由服务端算好（`languages` 数组），页面只做 `languages.length / 4` 展示。
 */
import request from '@/api/biz/request'

/**
 * 分页查文章列表（A-14）。
 *
 * @param {{category?:string,status?:string,lang?:string,keyword?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 分页响应（行内含 languages）
 */
export function listArticles(query) {
  // 列表是 RuoYi 口径（顶层 rows/total）：拦截器返回的就是 body，**不能再取 .data**
  return request({
    url: '/api/v1/admin/articles',
    method: 'get',
    params: query
  })
}

/**
 * 查文章详情（A-15）。
 *
 * @param {string} articleId 文章主键
 * @returns {Promise<{code:number,data:object}>} 详情（源内容 + 已有译文）
 */
export function getArticle(articleId) {
  return request({
    url: `/api/v1/admin/articles/${articleId}`,
    method: 'get'
  })
}

/**
 * 新建文章（A-15，保存为草稿）。
 *
 * @param {object} data 文章内容（含 translations 数组）
 * @returns {Promise<{code:number,data:string}>} 新文章主键
 */
export function createArticle(data) {
  return request({
    url: '/api/v1/admin/articles',
    method: 'post',
    data
  })
}

/**
 * 保存文章（A-15）。
 *
 * @param {string} articleId 文章主键
 * @param {object} data 文章内容
 * @returns {Promise<{code:number}>} 空响应
 */
export function updateArticle(articleId, data) {
  return request({
    url: `/api/v1/admin/articles/${articleId}`,
    method: 'put',
    data
  })
}

/**
 * 上架（A-15；四语言不齐全会被服务端拒）。
 *
 * @param {string} articleId 文章主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function publishArticle(articleId) {
  return request({
    url: `/api/v1/admin/articles/${articleId}/publish`,
    method: 'post'
  })
}

/**
 * 下架（A-14）。
 *
 * @param {string} articleId 文章主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function offlineArticle(articleId) {
  return request({
    url: `/api/v1/admin/articles/${articleId}/offline`,
    method: 'post'
  })
}
