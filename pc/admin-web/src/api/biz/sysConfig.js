/*
 * 职责：PC A-08 系统参数的接口封装（第 1 段 F3，契约见《F2+F3 接口冻结契约》§四 A-08）。
 * 调用方：src/views/biz/sysConfig/index.vue。
 * 数据来源：faze-api `/api/v1/admin/sys-configs`（注意是 **faze.sys_config**，不是 faze_admin.sys_config）。
 * 注意：
 *   1. 查询接口返回 `R<List<SysConfigVO>>`（`res.data` 是数组），**不是** TableDataInfo——
 *      配置项总量很小，服务端不做分页；页面因此不显示分页器，但保留了本地 `keys` 过滤。
 *   2. `editable=0` 的项服务端会拒绝写入（401006），所以页面对这类行**不渲染输入框**，
 *      从交互上就避免用户白改一遍再被拒。
 *   3. 批量保存是 `PUT` + `{items:[{configKey, configValue}]}`：**只提交有改动的项**，
 *      不要把整表回传——整表回传会把别人并发改过的值覆盖回旧值。
 *   4. 服务端还会按 `valueType`（int/long/decimal/bool）校验值格式并回 401001，
 *      页面侧做一次同规则的前置校验，只为少跑一次往返，不作为唯一防线。
 */
import request from '@/api/biz/request'

/**
 * 查询系统参数。
 *
 * @param {string[]} [keys] 参数键列表；传空数组或不传 = 全部
 * @returns {Promise<{code:number,msg:string,data:Array<object>}>} 参数列表
 */
export function listSysConfigs(keys) {
  return request({
    url: '/api/v1/admin/sys-configs',
    method: 'get',
    // 契约形式是 `?keys=a,b,c`：axios 默认把数组序列化成 keys[]=a&keys[]=b，所以这里手工拼逗号串
    params: keys && keys.length ? { keys: keys.join(',') } : {}
  })
}

/**
 * 批量保存系统参数（只允许改 editable=1 的项）。
 *
 * @param {Array<{configKey:string, configValue:string}>} items 变更项
 * @returns {Promise<{code:number,msg:string,data:null}>} 保存结果
 */
export function saveSysConfigs(items) {
  return request({
    url: '/api/v1/admin/sys-configs',
    method: 'put',
    data: { items }
  })
}
