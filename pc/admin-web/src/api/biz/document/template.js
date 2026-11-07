/*
 * 职责：PC A-18「文书模板」的接口封装（第 4 段 B1）。
 * 调用方：src/views/biz/document/template/index.vue、edit.vue。
 * 数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
 *   GET    /api/v1/admin/doc-templates               列表（RuoYi 口径 rows / total）
 *   GET    /api/v1/admin/doc-templates/{id}          详情（含 templateHtml 与 fieldSchema）
 *   POST   /api/v1/admin/doc-templates               新建（version=1，默认 draft）
 *   PUT    /api/v1/admin/doc-templates/{id}          保存 = **生成新版本行**（旧行置 inactive）
 *   POST   /api/v1/admin/doc-templates/{id}/preview  渲染预览 PNG，返回 60 秒短时地址
 * 注意：
 *   1. **保存不是原地改**：后端会 INSERT 一条 version+1 的新行、把旧行置 inactive。
 *      前端调完 PUT 拿到的是**新行 id**，要用它跳转/刷新，不能继续用旧 id 编辑
 *      （继续用旧 id 编辑会再生成一版，版本号越滚越大）。
 *   2. 预览返回的 `url` 是 60 秒有效的签名地址，**不要缓存到 localStorage**，过期即 403。
 *   3. 分类是自由文本（诉讼文书 / 合同文书 / 劳动文书 / 跨境电商），前端只做建议不强制。
 *   4. 模板 HTML 与 fieldSchema 的合法性**由服务端校验**（well-formed、标签白名单、
 *      占位符与字段一一对应）。前端不做 HTML 解析，避免两套规则漂移。
 */
import request from '@/api/biz/request'

/** 接口根路径（request.js 会再拼 /biz-api 前缀）。 */
const BASE = '/api/v1/admin/doc-templates'

/**
 * 分页查询模板列表。
 *
 * @param {{type?:string,status?:string,lang?:string,kw?:string,pageNum:number,pageSize:number}} query
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 口径分页响应
 */
export function listDocTemplates(query) {
  return request({
    url: BASE,
    method: 'get',
    params: query
  })
}

/**
 * 查模板详情（含 HTML 与字段定义，供编辑器回填）。
 *
 * @param {string|number} id 模板主键
 * @returns {Promise<{code:number,data:object}>}
 */
export function getDocTemplate(id) {
  return request({
    url: `${BASE}/${id}`,
    method: 'get'
  })
}

/**
 * 新建模板。
 *
 * @param {{code:string,name:string,category?:string,templateHtml:string,fieldSchema:string,sortOrder?:number,status?:string}} data
 * @returns {Promise<{code:number,data:string|number}>} 新模板 id
 */
export function createDocTemplate(data) {
  return request({
    url: BASE,
    method: 'post',
    data
  })
}

/**
 * 保存模板（生成新版本行）。
 *
 * @param {string|number} id 当前编辑的模板行 id
 * @param {{code:string,name:string,category?:string,templateHtml:string,fieldSchema:string,sortOrder?:number,status?:string}} data
 * @returns {Promise<{code:number,data:string|number}>} **新版本行的 id**
 */
export function updateDocTemplate(id, data) {
  return request({
    url: `${BASE}/${id}`,
    method: 'put',
    data
  })
}

/**
 * 渲染预览图（真 PDF 转 PNG，服务端会顺带刷新 previewOssKey）。
 *
 * @param {string|number} id 模板主键
 * @returns {Promise<{code:number,data:{url:string,expiresIn:number}}>} 60 秒有效地址
 */
export function previewDocTemplate(id) {
  return request({
    url: `${BASE}/${id}/preview`,
    method: 'post',
    // 首次渲染要建字体缓存（~2s）+ 转 PNG，给 30 秒
    timeout: 30000
  })
}
