<!--
  职责：PC A-27 法条库的「批量导入」对话框（第 3 段 H1）。
  调用方：src/views/biz/ai/law-library/index.vue。
  数据来源：POST /api/v1/admin/law-items/import
            两种入参：multipart/form-data（文件字段名 file，≤5MB）
                      application/json（顶层法条对象数组，≤500 条）
            返回 {total, inserted, skipped, failed:[{index, reason}]}
  注意：
    1. **幂等键 = (lawCode, articleNo, effectiveDate)**：重复条目由后端计入 `skipped`，
       不报错。页面不要自己先做去重（前端拿不到全库数据，做了也不准）。
    2. **单次上限 500 条**是刻意的：同步写库不排队；超过请分批导，不要改成异步任务。
    3. `failed[].index` 是**数组下标（0 开始）**；页面展示给运营时换算成「第 N 条」，
       并保留原始下标提示，避免与后端日志对不上。
    4. 必填字段：`lawCode(≤50) / lawName / articleNo / content / effectiveDate(yyyy-MM-dd)`。
       前端只做「能否提交」的前置校验（少跑一次往返），最终以后端校验为准。
    5. 导入成功（inserted>0）后向父页面 emit `success`，父页面重拉列表——**不缓存、不异步**，
       导完立刻可查。
-->
<template>
  <el-dialog
    :model-value="modelValue"
    title="批量导入法条"
    width="780px"
    append-to-body
    @update:model-value="handleVisibleChange"
    @open="handleOpen"
  >
    <el-alert
      class="block-gap"
      type="info"
      :closable="false"
      show-icon
      title="重复的法条（lawCode + articleNo + effectiveDate 相同）会被跳过，不计为失败；单次最多 500 条。"
    />

    <!-- ==================== 结果面板（导入后显示，替代表单） ==================== -->
    <div v-if="result" class="import-result">
      <el-alert
        :type="result.failed.length ? 'warning' : 'success'"
        :closable="false"
        show-icon
        :title="resultTitle"
      />
      <div class="result-nums">
        <div class="result-num"><span class="result-num__value">{{ result.total }}</span><span class="result-num__label">总条数</span></div>
        <div class="result-num"><span class="result-num__value">{{ result.inserted }}</span><span class="result-num__label">新增</span></div>
        <div class="result-num"><span class="result-num__value">{{ result.skipped }}</span><span class="result-num__label">跳过（重复）</span></div>
        <div class="result-num" :class="{ 'result-num--danger': result.failed.length }">
          <span class="result-num__value">{{ result.failed.length }}</span><span class="result-num__label">失败</span>
        </div>
      </div>

      <div v-if="result.failed.length" class="failed-block">
        <div class="failed-block__title">失败明细（index 是数组下标，0 开始；表中已换算成第 N 条）</div>
        <el-table :data="result.failed" size="small" max-height="260" border>
          <el-table-column label="第 N 条" width="100">
            <template #default="{ row }">{{ Number(row.index) + 1 }}</template>
          </el-table-column>
          <el-table-column label="原始下标 index" width="130">
            <template #default="{ row }"><span class="mono">{{ row.index }}</span></template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <p class="result-hint">导入是同步写库的：关闭本弹窗后列表已能看到新增条文，无需等待。</p>
    </div>

    <!-- ==================== 导入表单 ==================== -->
    <template v-else>
      <el-radio-group v-model="mode" class="block-gap">
        <el-radio-button label="file">上传 JSON 文件</el-radio-button>
        <el-radio-button label="paste">直接粘贴 JSON</el-radio-button>
      </el-radio-group>

      <!-- 方式一：上传文件（multipart，字段名 file） -->
      <div v-if="mode === 'file'" class="mode-panel">
        <div class="file-picker">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept=".json,application/json"
            :on-change="handleFileChange"
          >
            <el-button type="primary" plain>选择 JSON 文件</el-button>
          </el-upload>
          <span v-if="selectedFile" class="file-picker__name">
            {{ selectedFile.name }}（{{ fileSizeText(selectedFile.size) }}）
            <el-button type="danger" link @click="clearFile">移除</el-button>
          </span>
          <span v-else class="file-picker__hint">仅支持 .json，≤ 5MB</span>
        </div>
        <div v-if="fileError" class="form-error">{{ fileError }}</div>
        <div class="format-hint">
          文件内容是一个法条对象数组，字段：<span class="mono">lawCode / lawName / articleNo / articleSeq? /
          content / effectiveDate / expireDate? / status? / keywords?</span>。
        </div>
        <div class="format-example">示例：
[{"lawCode":"civil_code","lawName":"中华人民共和国民法典","articleNo":"第五百七十七条","articleSeq":577,"content":"……","effectiveDate":"2021-01-01"}]</div>
      </div>

      <!-- 方式二：直接贴 JSON（application/json 数组） -->
      <div v-else class="mode-panel">
        <el-input
          v-model="jsonText"
          type="textarea"
          :rows="12"
          class="json-input"
          placeholder='粘贴法条对象数组，例如：[{"lawCode":"civil_code","lawName":"中华人民共和国民法典","articleNo":"第五百七十七条","articleSeq":577,"content":"……","effectiveDate":"2021-01-01"}]'
        />
        <div class="format-hint">
          必填：<span class="mono">lawCode（≤50）/ lawName / articleNo / content / effectiveDate（yyyy-MM-dd）</span>；
          可选：<span class="mono">articleSeq / expireDate / status / keywords</span>。
          <span v-if="jsonCount !== null">当前 {{ jsonCount }} 条。</span>
        </div>
        <div v-if="pasteProblems.length" class="form-error">
          <div>数据校验未通过（共 {{ pasteProblems.length }} 处，最多显示 10 处）：</div>
          <ul class="problem-list">
            <li v-for="(item, index) in pasteProblems.slice(0, 10)" :key="index">{{ item }}</li>
          </ul>
        </div>
        <div v-else-if="pasteError" class="form-error">{{ pasteError }}</div>
      </div>
    </template>

    <template #footer>
      <el-button @click="handleVisibleChange(false)">{{ result ? '关闭' : '取消' }}</el-button>
      <el-button v-if="result" @click="resetAll">继续导入</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">开始导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 批量导入对话框脚本。
 *
 * 副作用：导入成功后 emit('success')；关闭时把表单与结果重置，避免下次打开看到上次残留。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { importLawItems } from '@/api/biz/ai/law-library'

/** 单个文件上限 5MB（与后端 multipart 配置一致）。 */
const MAX_FILE_SIZE = 5 * 1024 * 1024

/** 单次导入上限 500 条（与后端校验一致）。 */
const MAX_ITEMS = 500

/** 必填字段（前端前置校验用；后端仍是最终防线）。 */
const REQUIRED_FIELDS = ['lawCode', 'lawName', 'articleNo', 'content', 'effectiveDate']

defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'success'])

const mode = ref('file')
const selectedFile = ref(null)
const fileError = ref('')
const jsonText = ref('')
const jsonCount = ref(null)
const pasteError = ref('')
const pasteProblems = ref([])
const submitting = ref(false)
const result = ref(null)

/** 结果标题。 */
const resultTitle = computed(() => {
  if (!result.value) {
    return ''
  }
  const base = `导入完成：新增 ${result.value.inserted} 条，跳过 ${result.value.skipped} 条`
  return result.value.failed.length ? `${base}，失败 ${result.value.failed.length} 条（见下方明细）` : base
})

/**
 * 切换弹窗可见性（支持 v-model）。
 *
 * @param {boolean} visible 是否可见
 */
function handleVisibleChange(visible) {
  emit('update:modelValue', visible)
}

/** 每次打开重置，避免上次导入结果残留。 */
function handleOpen() {
  resetAll()
}

/** 重置表单与结果。 */
function resetAll() {
  mode.value = 'file'
  selectedFile.value = null
  fileError.value = ''
  jsonText.value = ''
  jsonCount.value = null
  pasteError.value = ''
  pasteProblems.value = []
  result.value = null
  submitting.value = false
}

/** 文件大小展示。 */
function fileSizeText(size) {
  if (!size && size !== 0) {
    return '—'
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

/**
 * 选择文件（el-upload 的 onChange；auto-upload=false，只取原始 File 对象）。
 *
 * @param {{raw?:File,name?:string,size?:number}} uploadFile el-upload 文件对象
 */
function handleFileChange(uploadFile) {
  fileError.value = ''
  const raw = uploadFile && uploadFile.raw
  if (!raw) {
    return
  }
  if (!/\.json$/i.test(raw.name || '')) {
    selectedFile.value = null
    fileError.value = '只支持 .json 文件'
    return
  }
  if (raw.size > MAX_FILE_SIZE) {
    selectedFile.value = null
    fileError.value = `文件 ${fileSizeText(raw.size)} 超过 5MB 上限，请拆分后再导`
    return
  }
  selectedFile.value = raw
}

/** 移除已选文件。 */
function clearFile() {
  selectedFile.value = null
  fileError.value = ''
}

/**
 * 解析并前置校验粘贴的 JSON。
 *
 * @returns {{list?:Array<object>, error?:string, problems?:string[]}} 解析结果
 */
function parsePasteJson() {
  const text = (jsonText.value || '').trim()
  if (!text) {
    return { error: '请先粘贴 JSON 数组' }
  }
  let list
  try {
    list = JSON.parse(text)
  } catch (e) {
    return { error: `JSON 解析失败：${e.message}` }
  }
  if (!Array.isArray(list)) {
    return { error: '顶层必须是数组（[...]），不能是单个对象' }
  }
  jsonCount.value = list.length
  if (list.length > MAX_ITEMS) {
    return { error: `单次最多 ${MAX_ITEMS} 条，当前 ${list.length} 条，请分批导入` }
  }
  const problems = []
  const datePattern = /^\d{4}-\d{2}-\d{2}$/
  list.forEach((item, index) => {
    const label = `第 ${index + 1} 条`
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      problems.push(`${label}：不是对象`)
      return
    }
    REQUIRED_FIELDS.forEach((field) => {
      if (item[field] === null || item[field] === undefined || String(item[field]).trim() === '') {
        problems.push(`${label}：缺少必填字段 ${field}`)
      }
    })
    if (item.lawCode && String(item.lawCode).length > 50) {
      problems.push(`${label}：lawCode 超过 50 字符`)
    }
    if (item.effectiveDate && !datePattern.test(String(item.effectiveDate))) {
      problems.push(`${label}：effectiveDate 必须是 yyyy-MM-dd`)
    }
    if (item.expireDate && !datePattern.test(String(item.expireDate))) {
      problems.push(`${label}：expireDate 必须是 yyyy-MM-dd`)
    }
  })
  if (problems.length) {
    return { error: '', problems }
  }
  return { list }
}

/** 归一化后端导入结果。 */
function normalizeResult(data) {
  const source = data || {}
  return {
    total: Number(source.total) || 0,
    inserted: Number(source.inserted) || 0,
    skipped: Number(source.skipped) || 0,
    failed: Array.isArray(source.failed) ? source.failed.filter(Boolean) : []
  }
}

/** 提交导入。 */
async function submit() {
  pasteError.value = ''
  pasteProblems.value = []
  fileError.value = ''

  let payload
  if (mode.value === 'file') {
    if (!selectedFile.value) {
      fileError.value = '请先选择一个 .json 文件'
      return
    }
    // multipart：字段名固定 file；**不要手写 Content-Type**（浏览器要补 boundary）
    payload = new FormData()
    payload.append('file', selectedFile.value)
  } else {
    const parsed = parsePasteJson()
    if (parsed.problems && parsed.problems.length) {
      pasteProblems.value = parsed.problems
      return
    }
    if (parsed.error) {
      pasteError.value = parsed.error
      return
    }
    payload = parsed.list
  }

  submitting.value = true
  try {
    const res = await importLawItems(payload)
    result.value = normalizeResult(res.data)
    if (result.value.inserted > 0) {
      emit('success')
    }
    ElMessage.success(`导入完成：新增 ${result.value.inserted} 条`)
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.block-gap {
  margin-bottom: 16px;
}
.mode-panel {
  min-height: 220px;
}
.file-picker {
  display: flex;
  align-items: center;
  gap: 12px;
}
.file-picker__name {
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.file-picker__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.form-error {
  margin-top: 10px;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
}
.problem-list {
  margin: 6px 0 0;
  padding-left: 20px;
}
.format-hint {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}
.format-example {
  margin-top: 6px;
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  white-space: pre;
  overflow-x: auto;
}
.json-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}
.import-result {
  min-height: 220px;
}
.result-nums {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin: 16px 0;
}
.result-num {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 12px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}
.result-num__value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}
.result-num__label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.result-num--danger .result-num__value {
  color: var(--el-color-danger);
}
.failed-block__title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.result-hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
