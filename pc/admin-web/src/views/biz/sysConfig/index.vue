<!--
  职责：PC A-08 系统参数 —— 查询业务库参数（faze.sys_config）、按 editable 决定能否编辑、批量保存。
  调用方：RuoYi 动态路由（sys_menu 2107，component = biz/sysConfig/index，route name = BizSysConfig）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET /api/v1/admin/sys-configs?keys=a,b,c   参数列表（R<List<SysConfigVO>>，**不是 TableDataInfo**）
    PUT /api/v1/admin/sys-configs  body {items:[{configKey, configValue}]}  批量保存（R<Void>）
  注意：
    1. 这里查的是**业务库 faze.sys_config**，不是 RuoYi 自己的 faze_admin.sys_config——同名不同库。
    2. `editable` 服务端是 **Boolean**（不是 0/1 整数）：`editable === false` 只读且不渲染输入框，
       服务端也会以 401006 拒绝写入，页面上从交互层就先挡住。
    3. **只提交有改动的项**（configKey + configValue），不回传整表：
       整表回传会把别人并发改过的值覆盖回旧值。
    4. 服务端按 `valueType`（int/long/decimal/bool）校验值格式并回 401001；
       页面侧做一次同规则的前置校验，只为少跑一次往返，不是唯一防线。
    5. 列表接口不分页（配置项总量小），所以本页没有分页器；`keys` 过滤在服务端做。
    6. 四态：加载中 / 空 / 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">系统参数</h1>
        <p class="page-head__desc">
          业务参数集中在业务库 sys_config。只有「可改」的参数能编辑；保存会写操作日志，记录改动前后的值。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <!-- ==================== 错误态（常驻 + 可重试） ==================== -->
    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="系统参数加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="参数键">
          <el-input
            v-model="query.keys"
            placeholder="逗号分隔，如 a.b,c.d；留空=全部"
            clearable
            style="width: 300px"
            @keyup.enter="loadList"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadList">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="query-hint">参数键是大小写敏感的点分字符串（如 account.realname.required），按服务端原值精确匹配。</div>
    </el-card>

    <!-- ==================== 表格 ==================== -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="table-toolbar__left">
          <span class="table-toolbar__count">
            共 {{ rows.length }} 项
            <template v-if="dirtyItems.length">
              · <span class="table-toolbar__dirty">{{ dirtyItems.length }} 项待保存</span>
            </template>
          </span>
        </div>
        <div class="table-toolbar__right">
          <el-button :disabled="!dirtyItems.length" @click="loadList">放弃修改</el-button>
          <el-button type="primary" :disabled="!dirtyItems.length" :loading="saving" @click="saveAll">
            保存修改
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="saveError"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        :title="saveError"
      />

      <el-table v-loading="loading" :data="rows" row-key="configKey" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '参数加载失败，请点上方「重试」' : '没有符合条件的系统参数'"
            :image-size="72"
          />
        </template>
        <el-table-column label="参数键" min-width="220">
          <template #default="{ row }">
            <span class="mono">{{ row.configKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参数值" min-width="240">
          <template #default="{ row }">
            <el-input
              v-if="isEditable(row) && editValues[row.configKey] !== undefined"
              v-model="editValues[row.configKey]"
              size="small"
              :placeholder="valuePlaceholder(row)"
            />
            <span v-else class="mono">{{ textOrDash(row.configValue) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="90">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ textOrDash(row.valueType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分类" min-width="110">
          <template #default="{ row }">{{ textOrDash(row.category) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ textOrDash(row.remark) }}</template>
        </el-table-column>
        <el-table-column label="是否可改" min-width="100">
          <template #default="{ row }">
            <el-tag :type="isEditable(row) ? 'success' : 'info'" effect="light" size="small">
              {{ isEditable(row) ? '可改' : '只读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="150">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="rows.length" class="table-foot">
        只读参数（是否可改 = 只读）由服务端锁定，保存时不会提交，也无法通过本页修改。
      </div>
    </el-card>
  </div>
</template>

<script setup name="BizSysConfig">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listSysConfigs, saveSysConfigs } from '@/api/biz/sysConfig'
import { describeRequestError, formatDateTime, textOrDash } from '@/views/biz/shared/format'

/**
 * 各值类型的前置校验规则（与后端口径一致：格式不符服务端回 401001）。
 *
 * 说明：只做「明显不合格式」的拦截，不臆造业务约束（例如范围上下限应由服务端决定）。
 */
const VALUE_PATTERNS = {
  int: /^-?\d+$/,
  long: /^-?\d+$/,
  decimal: /^-?\d+(\.\d+)?$/,
  bool: /^(true|false|1|0)$/i
}

/** 查询条件（keys 用逗号分隔，空 = 全部）。 */
const query = reactive({
  keys: ''
})

/** 参数列表。 */
const rows = ref([])

/** 编辑值：configKey -> 当前输入值（只有 editable=true 的行会初始化）。 */
const editValues = reactive({})

/** 加载中。 */
const loading = ref(false)

/** 加载错误。 */
const error = ref('')

/** 保存中。 */
const saving = ref(false)

/** 保存错误（留在页面，方便改完重试）。 */
const saveError = ref('')

/**
 * 有改动的项（只算 editable=true 的行）。
 *
 * @returns {Array<object>} 待保存的行
 */
const dirtyItems = computed(() =>
  rows.value.filter(
    (row) =>
      isEditable(row) &&
      editValues[row.configKey] !== undefined &&
      editValues[row.configKey] !== (row.configValue === null || row.configValue === undefined ? '' : row.configValue)
  )
)

/**
 * 判断一行是否可编辑。
 *
 * 服务端 `AdminSysConfigVO.editable` 是 **Boolean**；这里同时兼容 1 / '1'，
 * 避免哪天改成整数时页面静默变成全只读。
 *
 * @param {object} row 参数行
 * @returns {boolean} 是否可编辑
 */
function isEditable(row) {
  const value = row ? row.editable : false
  return value === true || value === 1 || value === '1'
}

/**
 * 输入框占位提示（按类型给出值样例）。
 *
 * @param {object} row 参数行
 * @returns {string} 占位文案
 */
function valuePlaceholder(row) {
  const type = (row.valueType || '').toLowerCase()
  if (type === 'int' || type === 'long') {
    return '整数'
  }
  if (type === 'decimal') {
    return '小数，如 1.5'
  }
  if (type === 'bool') {
    return 'true / false'
  }
  return '字符串'
}

/**
 * 把编辑缓存重置成列表当前值（加载/放弃修改/保存成功后都走这里）。
 *
 * @returns {void}
 */
function resetEditValues() {
  Object.keys(editValues).forEach((key) => {
    delete editValues[key]
  })
  rows.value.forEach((row) => {
    if (isEditable(row)) {
      editValues[row.configKey] = row.configValue === null || row.configValue === undefined ? '' : String(row.configValue)
    }
  })
}

/**
 * 把逗号分隔的 keys 解析成数组。
 *
 * @returns {string[]} 参数键数组（空串会被过滤）
 */
function parseKeys() {
  return query.keys
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

/**
 * 拉取系统参数列表。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadList() {
  loading.value = true
  error.value = ''
  saveError.value = ''
  try {
    const res = await listSysConfigs(parseKeys())
    rows.value = Array.isArray(res.data) ? res.data : []
    resetEditValues()
  } catch (e) {
    rows.value = []
    error.value = describeRequestError(e, '系统参数加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 重置查询条件并重查。
 *
 * @returns {void}
 */
function handleReset() {
  query.keys = ''
  loadList()
}

/**
 * 校验单个参数值是否符合 valueType。
 *
 * @param {object} row 参数行
 * @param {string} value 待保存的值
 * @returns {boolean} 是否符合格式
 */
function validateValue(row, value) {
  const type = (row.valueType || '').toLowerCase()
  const pattern = VALUE_PATTERNS[type]
  if (!pattern) {
    return true
  }
  return pattern.test(String(value).trim())
}

/**
 * 批量保存：校验类型 → 二次确认 → 只提交有改动的项 → 成功后刷新列表。
 *
 * 副作用：写操作（服务端校验 editable 与值类型，并写操作日志）。
 *
 * @returns {Promise<void>} 无返回值
 */
async function saveAll() {
  saveError.value = ''
  const changed = dirtyItems.value
  if (!changed.length) {
    return
  }
  const invalid = changed.find((row) => !validateValue(row, editValues[row.configKey]))
  if (invalid) {
    saveError.value = `「${invalid.configKey}」的值不符合类型 ${invalid.valueType || '未知'}，请按占位提示填写`
    return
  }
  const items = changed.map((row) => ({
    configKey: row.configKey,
    configValue: String(editValues[row.configKey])
  }))
  const preview = items
    .slice(0, 5)
    .map((item) => `${item.configKey} = ${item.configValue}`)
    .join('；')
  try {
    await ElMessageBox.confirm(
      `确认保存 ${items.length} 项参数修改？\n${preview}${items.length > 5 ? ' …' : ''}\n保存会写操作日志（含 before/after）。`,
      '保存确认',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await saveSysConfigs(items)
    ElMessage.success(`已保存 ${items.length} 项参数`)
    // 保存后重取：以服务端最终值为准（服务端可能做归一化）
    await loadList()
  } catch (e) {
    saveError.value = describeRequestError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-head__desc {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.page-head__actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.block-gap {
  margin-bottom: 16px;
}

.error-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.query-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.table-toolbar__left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-toolbar__count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.table-toolbar__dirty {
  color: var(--el-color-warning);
}

.table-toolbar__right {
  display: flex;
  gap: 8px;
}

.mono {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.table-foot {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
