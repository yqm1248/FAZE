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
    7. **长文本参数（超过 80 字的 string，如用户协议 / 隐私政策正文）不在表格里内联编辑**：
       表格只放 60 字摘要 + 「编辑正文（N 字）」按钮，点开是固定高度的多行 textarea 弹窗。
       原先表格用单行 el-input，600~900 字的法务正文既看不全、也打不进换行（G-3，段 1 收口修）。
       判定按「长度 + valueType」而不是按 configKey 硬编码，以后新增法务类参数不用再改代码。
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
        <el-table-column label="参数值" min-width="300">
          <template #default="{ row }">
            <!-- 长文本（用户协议 / 隐私政策正文这类 600~900 字）：表格里只放摘要，
                 编辑走弹窗里的多行 textarea。单行 el-input 装不下也看不见换行。 -->
            <div v-if="isLongText(row)" class="long-text">
              <span class="long-text__preview">{{ previewText(row) }}</span>
              <el-button
                v-if="isEditable(row)"
                type="primary"
                link
                size="small"
                @click="openEditor(row)"
              >
                编辑正文（{{ lengthOf(row) }} 字）
              </el-button>
            </div>
            <el-input
              v-else-if="isEditable(row) && editValues[row.configKey] !== undefined"
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

    <!-- ==================== 长文本编辑器（用户协议 / 隐私政策正文） ==================== -->
    <!-- 为什么单独开弹窗而不是在表格里换 textarea：表格行高会被 900 字撑爆，
         而且输入时整张表的行会跟着抖动。这里固定高度 + 实时字数，编辑体验才是可用的。 -->
    <el-dialog
      v-model="editorVisible"
      :title="editorTitle"
      width="760px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="editor-tip">
        保存后立即对 APP 生效（接口 <span class="mono">GET /api/v1/pub/config/{configKey}</span> 直接读这条）。
        换行请直接按回车——写入的是真实换行符。
      </div>
      <el-input
        v-model="editorValue"
        type="textarea"
        :rows="18"
        resize="vertical"
        placeholder="在此输入正文"
      />
      <div class="editor-foot">
        <span class="editor-foot__count" :class="{ 'is-warn': editorValue.length > MAX_TEXT_LENGTH }">
          {{ editorValue.length }} 字<template v-if="MAX_TEXT_LENGTH"> / 上限 {{ MAX_TEXT_LENGTH }}</template>
        </span>
        <div class="editor-foot__actions">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!editorDirty" @click="confirmEditor">
            确定（回到列表，仍需点「保存修改」）
          </el-button>
        </div>
      </div>
    </el-dialog>
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
 * 长文本判定阈值（字符数）。
 *
 * 超过它的 `string` 参数走弹窗编辑器，不再用表格内联单行输入框。
 * 80 是「一眼看得出这是段文章而不是一个开关值」的粗略分界；按长度判而不是按
 * configKey 硬编码，是为了以后新增法务类参数（如《未成年人保护规则》）不用再改代码。
 */
const LONG_TEXT_MIN_LENGTH = 80

/**
 * 正文长度上限的**提示阈值**（不是硬校验）。
 *
 * 库列是 TEXT（65535 字节），这里给 20000 字符是留足 UTF-8 字节余量的运营上限。
 * 超过只标黄，不拦——真正的长度约束应由服务端决定，前端不臆造业务规则。
 */
const MAX_TEXT_LENGTH = 20000

/** 长文本编辑器可见性。 */
const editorVisible = ref(false)

/** 正在编辑的参数键。 */
const editorKey = ref('')

/** 编辑器草稿值（点「确定」才写回 editValues，取消即丢弃）。 */
const editorValue = ref('')

/** 正在编辑的参数行。 */
const editorRow = computed(
  () => rows.value.find((row) => row.configKey === editorKey.value) || null
)

/** 编辑器标题。 */
const editorTitle = computed(() =>
  editorRow.value ? `编辑正文 · ${editorRow.value.configKey}` : '编辑正文'
)

/** 编辑器草稿相对「待保存值」是否有改动。 */
const editorDirty = computed(() => {
  const key = editorKey.value
  if (!key) {
    return false
  }
  const current = editValues[key] === undefined ? '' : String(editValues[key])
  return editorValue.value !== current
})

/**
 * 取服务端返回的原始值（统一成字符串）。
 *
 * @param {object} row 参数行
 * @returns {string} 原始值，null/undefined 归一为空串
 */
function rawValue(row) {
  return row && row.configValue !== null && row.configValue !== undefined ? String(row.configValue) : ''
}

/**
 * 取该行「当前」显示值：优先编辑器里的待保存值，其次服务端值。
 *
 * @param {object} row 参数行
 * @returns {string} 当前值
 */
function currentValue(row) {
  if (!row) {
    return ''
  }
  const cached = editValues[row.configKey]
  return cached === undefined ? rawValue(row) : String(cached)
}

/**
 * 判断该行是否按长文本渲染。
 *
 * <p>用「原值与当前值的较大者」判，而不是只看当前值：一旦这一项是长文本，
 * 编辑过程中即使暂时删短，也不该掉回单行输入框——控件随输入字数跳变是很糟的体验，
 * 而且用户删到一半时控件消失会直接把光标弄丢。一旦长文本，永远是长文本。</p>
 *
 * @param {object} row 参数行
 * @returns {boolean} 是否长文本
 */
function isLongText(row) {
  if (!row) {
    return false
  }
  if ((row.valueType || '').toLowerCase() !== 'string') {
    return false
  }
  const longest = Math.max(rawValue(row).length, currentValue(row).length)
  return longest > LONG_TEXT_MIN_LENGTH
}

/**
 * 该行当前值长度（表格上显示「编辑正文（N 字）」）。
 *
 * @param {object} row 参数行
 * @returns {number} 字符数
 */
function lengthOf(row) {
  return currentValue(row).length
}

/**
 * 长文本在表格里的摘要（压掉换行与连续空白，只留前 60 字）。
 *
 * @param {object} row 参数行
 * @returns {string} 摘要文案
 */
function previewText(row) {
  const text = currentValue(row).replace(/\s+/g, ' ').trim()
  return text.length > 60 ? `${text.slice(0, 60)}…` : text
}

/**
 * 打开长文本编辑器。
 *
 * @param {object} row 参数行
 * @returns {void}
 */
function openEditor(row) {
  editorKey.value = row.configKey
  editorValue.value = currentValue(row)
  editorVisible.value = true
}

/**
 * 确认编辑：把草稿写进 editValues（此时**还没有提交到服务端**）。
 *
 * @returns {void}
 */
function confirmEditor() {
  if (editorKey.value) {
    editValues[editorKey.value] = editorValue.value
  }
  editorVisible.value = false
}

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
  // 确认框里不能整段塞法务正文：900 字会把弹窗撑满，截到 40 字足够认出是哪一项
  const preview = items
    .slice(0, 5)
    .map((item) => {
      const shown = item.configValue.length > 40 ? `${item.configValue.slice(0, 40)}…` : item.configValue
      return `${item.configKey} = ${shown}`
    })
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

/* ── 长文本（法务正文）────────────────────────────────────────── */
.long-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.long-text__preview {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.editor-tip {
  margin-bottom: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.editor-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
}

.editor-foot__count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.editor-foot__count.is-warn {
  color: var(--el-color-warning);
}

.editor-foot__actions {
  display: flex;
  gap: 8px;
}
</style>
