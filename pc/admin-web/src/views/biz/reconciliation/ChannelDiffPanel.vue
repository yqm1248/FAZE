<!--
  职责：PC A-09「渠道对账」Tab（第 6 段 B4）——reconciliation_diff 差异工单的查询与处置。
  调用方：src/views/biz/reconciliation/index.vue 的第二个 el-tab-pane。
  数据来源：faze-api
    GET /api/v1/admin/reconciliation/channel-diffs  差异列表（分页 + 4 个筛选条件）
    PUT /api/v1/admin/reconciliation/channel-diffs/{id}  标记已处理（备注必填）
  注意：
    1. **与 Tab1 是两份数据**：Tab1 是恒等式巡检的内存态现算结果，本 Tab 是落库的差异工单。
       两者互不影响，本组件不碰 Tab1 的任何状态。
    2. **金额后端传分、前端除 100**：本组件里所有金额运算都是整数分，展示时才换算，
       不许出现 `parseFloat(x) * 0.01` 这类浮点累加。
    3. **状态有两个「待处理」值**：段 5 的巡检写 `open`、段 6 的渠道差异写 `pending`，
       筛选下拉把它们合并成「待处理」一项，后端用 IN 查询。
    4. **差异类型的中文与颜色**：channel_only 红（可能漏单）、local_only 橙（可能伪造回调）、
       amount_mismatch 橙、identity_mismatch 红。
    5. 四态：加载（v-loading）/ 空（「暂无差异」——这是真实状态，不塞占位数据）/ 错误（el-alert + 重试）/ 正常。
    6. 已处理的行走只读态（不显示「标记已处理」按钮）；无权限时后端 403，页面提示即可。
-->
<template>
  <div class="channel-diff">
    <!-- ==================== 筛选栏 ==================== -->
    <el-form :inline="true" class="filter-bar" @submit.prevent>
      <el-form-item label="账单日期">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="true"
        />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select v-model="query.channel" placeholder="全部" clearable style="width: 120px">
          <el-option v-for="item in CHANNEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="差异类型">
        <el-select v-model="query.diffType" placeholder="全部" clearable style="width: 160px">
          <el-option v-for="item in DIFF_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="处理状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
          <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
        <el-button :disabled="loading" @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- ==================== 错误态 ==================== -->
    <el-alert v-if="error" class="block-gap" type="error" :closable="false" show-icon title="差异列表加载失败">
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="load">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 表格 ==================== -->
    <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
      <el-table-column prop="billDate" label="账单日期" width="110" />
      <el-table-column label="渠道" width="90">
        <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
      </el-table-column>
      <el-table-column label="差异类型" width="150">
        <template #default="{ row }">
          <el-tag :type="diffTypeTag(row.diffType)" size="small">{{ diffTypeLabel(row.diffType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="channelTxnId" label="渠道交易号" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.channelTxnId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="orderNo" label="本地单号" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.orderNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="差异金额" width="120" align="right">
        <template #default="{ row }">
          <span class="mono">{{ formatDiffCents(diffAmountCents(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发现时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.foundAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status !== 'handled'"
            type="primary"
            link
            size="small"
            @click="openHandle(row)"
          >
            标记已处理
          </el-button>
          <span v-else class="muted">已处理</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无差异" :image-size="72" />
      </template>
    </el-table>

    <!-- ==================== 分页 ==================== -->
    <el-pagination
      v-if="total > 0"
      class="pager"
      layout="total, prev, pager, next, sizes"
      :total="total"
      :current-page="query.pageNum"
      :page-size="query.pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <!-- ==================== 处置弹窗 ==================== -->
    <el-dialog v-model="handleVisible" title="标记差异已处理" width="480px">
      <el-form label-width="88px">
        <el-form-item label="差异单号">
          <span class="mono">{{ currentRow ? currentRow.id : '—' }}</span>
        </el-form-item>
        <el-form-item label="处理备注" required>
          <el-input
            v-model="handleNote"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="写清楚这条差异怎么处理的，事后要能凭这条备注还原判断"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitHandle">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="ChannelDiffPanel">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { handleChannelDiff, listChannelDiffs } from '@/api/biz/reconciliation'

/** 渠道选项（与 sys_config 的 pay.*.enabled 对应关系无关，这里只做展示映射）。 */
const CHANNEL_OPTIONS = [
  { value: 'wechat', label: '微信支付' },
  { value: 'alipay', label: '支付宝' },
  { value: 'mock', label: 'Mock 通道' }
]

/** 差异类型选项（4 类，见 §三 A4）。 */
const DIFF_TYPE_OPTIONS = [
  { value: 'channel_only', label: '渠道有、本地无' },
  { value: 'local_only', label: '本地有、渠道无' },
  { value: 'amount_mismatch', label: '金额不一致' },
  { value: 'identity_mismatch', label: '恒等式不平' }
]

/** 处理状态选项：pending 在后端会展开成 (open, pending)。 */
const STATUS_OPTIONS = [
  { value: 'pending', label: '待处理' },
  { value: 'auto_fixed', label: '已自动修复' },
  { value: 'handled', label: '已处理' }
]

/** 页面状态。 */
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const dateRange = ref(defaultYesterdayRange())
const handleVisible = ref(false)
const handleNote = ref('')
const currentRow = ref(null)

/** 查询条件。 */
const query = reactive({
  beginDate: '',
  endDate: '',
  channel: '',
  diffType: '',
  status: '',
  pageNum: 1,
  pageSize: 20
})

/**
 * 默认账单日期区间：昨日（B4 第 3 条）。
 *
 * @returns {[string, string]} 形如 ['2026-11-07', '2026-11-07']
 */
function defaultYesterdayRange() {
  const day = new Date()
  day.setDate(day.getDate() - 1)
  const text = formatDate(day)
  return [text, text]
}

/**
 * Date → yyyy-MM-dd（本地时区，不用 toISOString——那是 UTC，跨时区会差一天）。
 *
 * @param {Date} date 日期
 * @returns {string} 形如 2026-11-07
 */
function formatDate(date) {
  const pad = (value) => (value < 10 ? `0${value}` : String(value))
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * ISO 时间 → 本地可读串。
 *
 * @param {string} value UTC 时间（无时区后缀）
 * @returns {string} 形如 2026-11-08 19:20:31
 */
function formatDateTime(value) {
  if (!value) {
    return '—'
  }
  const parsed = new Date(`${value}Z`)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  const pad = (item) => (item < 10 ? `0${item}` : String(item))
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} `
    + `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
}

/**
 * 取「差异金额」的分值——两类差异存在**不同字段**。
 *
 * - 渠道差异（`channel_only` / `local_only` / `amount_mismatch`）：第 6 段 D4 按《第 6 段
 *   工作流》§三 D4 的字段映射写 `amount_cents`（渠道金额 − 本地金额），`diff_cents` 留 NULL。
 * - 恒等式差异（`identity_mismatch`）：段 5 的巡检写 `diff_cents`（实际 − 应有），
 *   `amount_cents` 留 NULL。
 *
 * 表格只有一列「差异金额」，所以按类型选字段。**不能只读 `diffCents`**：那样渠道差异
 * 的行会全部显示 `+0.00`（第一版就是这么错的，Tab2 真实截图才发现——单测和接口
 * 都对，错在页面读了另一个字段）。
 *
 * @param {object} row 差异行（`ChannelDiffRowVO`）
 * @returns {number} 差异金额（分，可正可负）
 */
function diffAmountCents(row) {
  if (!row) return 0
  if (row.diffType === 'identity_mismatch') return row.diffCents ?? 0
  return row.amountCents ?? row.diffCents ?? 0
}

/**
 * 差异金额（分）→ 带符号的元（两位小数），**整数运算**。
 *
 * @param {number|string} cents 差异金额（分）
 * @returns {string} 形如 -1.00
 */
function formatDiffCents(cents) {
  const value = Number(cents ?? 0)
  const abs = Math.abs(Math.trunc(value))
  const yuan = Math.floor(abs / 100)
  const fraction = abs % 100
  const pad = fraction < 10 ? `0${fraction}` : String(fraction)
  return `${value < 0 ? '-' : '+'}${yuan}.${pad}`
}

/**
 * 渠道机器值 → 中文。
 *
 * @param {string} value 渠道
 * @returns {string} 中文名
 */
function channelLabel(value) {
  return CHANNEL_OPTIONS.find((item) => item.value === value)?.label ?? value ?? '—'
}

/**
 * 差异类型机器值 → 中文。
 *
 * @param {string} value 类型
 * @returns {string} 中文名
 */
function diffTypeLabel(value) {
  return DIFF_TYPE_OPTIONS.find((item) => item.value === value)?.label ?? value ?? '—'
}

/**
 * 差异类型 → el-tag 颜色（红=可能漏单/不平，橙=可能伪造或金额差）。
 *
 * @param {string} value 类型
 * @returns {string} el-tag type
 */
function diffTypeTag(value) {
  if (value === 'channel_only' || value === 'identity_mismatch') {
    return 'danger'
  }
  return 'warning'
}

/**
 * 状态机器值 → 中文。
 *
 * @param {string} value 状态
 * @returns {string} 中文名
 */
function statusLabel(value) {
  const item = STATUS_OPTIONS.find((option) => option.value === value)
  if (item) {
    return item.label
  }
  return value === 'open' ? '待处理' : (value ?? '—')
}

/**
 * 状态 → el-tag 颜色。
 *
 * @param {string} value 状态
 * @returns {string} el-tag type
 */
function statusTag(value) {
  if (value === 'handled') {
    return 'success'
  }
  if (value === 'auto_fixed') {
    return 'info'
  }
  return 'warning'
}

/**
 * 拉取差异列表。
 *
 * 副作用：更新 rows / total / error / loading。
 */
async function load() {
  loading.value = true
  error.value = ''
  query.beginDate = dateRange.value?.[0] ?? ''
  query.endDate = dateRange.value?.[1] ?? ''
  try {
    const res = await listChannelDiffs({
      beginDate: query.beginDate || undefined,
      endDate: query.endDate || undefined,
      channel: query.channel || undefined,
      diffType: query.diffType || undefined,
      status: query.status || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    const data = res?.data ?? {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total ?? 0)
  } catch (e) {
    error.value = e?.message || '网络异常'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询（重置到第 1 页）。 */
function handleSearch() {
  query.pageNum = 1
  load()
}

/** 重置筛选条件并重新查询。 */
function handleReset() {
  dateRange.value = defaultYesterdayRange()
  query.channel = ''
  query.diffType = ''
  query.status = ''
  query.pageNum = 1
  load()
}

/**
 * 翻页。
 *
 * @param {number} page 新页码
 */
function handlePageChange(page) {
  query.pageNum = page
  load()
}

/**
 * 改每页条数。
 *
 * @param {number} size 新页大小
 */
function handleSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  load()
}

/**
 * 打开处置弹窗。
 *
 * @param {object} row 当前行
 */
function openHandle(row) {
  currentRow.value = row
  handleNote.value = ''
  handleVisible.value = true
}

/**
 * 提交处置：备注必填，成功后刷新列表。
 *
 * 副作用：调处置接口；成功后关闭弹窗并 load()。
 */
async function submitHandle() {
  if (!handleNote.value.trim()) {
    ElMessage.warning('处理备注必填')
    return
  }
  submitting.value = true
  try {
    await handleChannelDiff(currentRow.value.id, handleNote.value.trim())
    ElMessage.success('已标记处理')
    handleVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.message || '处置失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.filter-bar {
  margin-block-end: var(--app-spacing-sm, 12px);
}

.block-gap {
  margin-block-end: 12px;
}

.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pager {
  margin-block-start: 12px;
  justify-content: flex-end;
}

.mono {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--app-text-muted, #909399);
  font-size: 12px;
}
</style>
