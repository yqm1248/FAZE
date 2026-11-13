<!--
  A-13 律师结算（第 5 段 E5）
  职责：结算批次列表 / 生成 / 明细 / 确认 / 导出 CSV / 收入回补入口。
  调用方：RuoYi 菜单「法泽业务 → 律师结算」（menu_id 2221）。
  数据来源：`/api/v1/admin/lawyer-settlements/**`、`/api/v1/admin/settlements/backfill`。
  注意：
    1. **本页任何动作都不动律师余额**：生成只写批次表，确认只改状态。
       二次确认文案必须写清「不改余额、不产生流水」，这是 E5 的必验项。
    2. 表格 8 列（设计稿原文）：结算单号+状态 / 律师 / 周期 / 订单数 / 订单收入 /
       平台服务费（显示为负）/ 应结金额 / 操作。
    3. 平台服务费显示为负数（`− ¥540.00`）与设计稿一致；接口给的是正数。
    4. 明细是**实时查 orders**，所以抽屉里要给出「明细合计 vs 订单收入」的一致性提示。
       口径（2026-11-13 负责人拍板）：**订单收入 = Σ订单成交额**；明细合计同口径，
       两者应相等；「应结金额 = 订单收入 − 平台服务费」是另一个数，不要拿来对比。
    5. V1 不做 PDF，导出走前端 CSV（结算单号/律师/周期/订单数/收入/服务费/应结）。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">律师结算</h1>
        <p class="page-head__desc">
          应结金额 = 订单收入 − 平台服务费；结算需财务在银行渠道完成转账后点确认。
          <strong>系统只记录结算状态，不做转账。</strong>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
        <el-button @click="handleBackfill">收入回补</el-button>
        <el-button @click="handleExport">导出 CSV</el-button>
        <el-button type="primary" @click="openGenerate">生成结算单</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="结算数据加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadAll">重试</el-button>
      </div>
    </el-alert>

    <el-row :gutter="12" class="block-gap">
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-label">待结算总额</div>
          <div class="stat-value">{{ formatCents(summary.pendingTotalCents) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-label">本月已结算</div>
          <div class="stat-value">{{ formatCents(summary.settledThisMonthCents) }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-label">涉及律师</div>
          <div class="stat-value">{{ summary.lawyerCount }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="80px" @submit.prevent>
        <el-form-item label="律师">
          <el-input
            v-model="query.lawyerKw"
            placeholder="姓名 / 律所"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="结算周期">
          <el-select v-model="query.period" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
            <el-option v-for="item in periodOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="结算状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column label="结算单号" min-width="180">
          <template #default="{ row }">
            <div class="mono">{{ row.settlementNo }}</div>
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="律师" min-width="180">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-strong">{{ textOrDash(row.lawyerName) }}</span>
              <span class="cell-sub">{{ textOrDash(row.lawFirm) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="period" label="结算周期" width="110" />
        <el-table-column prop="orderCount" label="订单数" width="90" align="right" />
        <el-table-column label="订单收入" width="130" align="right">
          <template #default="{ row }">{{ formatCents(row.incomeCents) }}</template>
        </el-table-column>
        <el-table-column label="平台服务费" width="130" align="right">
          <template #default="{ row }">
            <span class="fee-negative">{{ formatNegativeCents(row.feeCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应结金额" width="140" align="right">
          <template #default="{ row }">
            <strong>{{ formatCents(row.settleCents) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">明细</el-button>
            <el-button
              v-if="row.status === 'pending'"
              type="success"
              link
              @click="handleConfirm(row)"
            >
              确认
            </el-button>
            <el-button v-else-if="row.status === 'rejected'" type="warning" link @click="openGenerate(row)">
              重新生成
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该条件下没有结算批次" />
        </template>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadList"
          @size-change="handleSearch"
        />
      </div>
    </el-card>

    <!-- 生成结算单 -->
    <el-dialog v-model="generateVisible" title="生成结算单" width="460px">
      <el-form :model="generateForm" label-width="90px">
        <el-form-item label="结算周期">
          <el-select v-model="generateForm.period" style="width: 100%">
            <el-option v-for="item in periodOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="生成范围">
          <el-radio-group v-model="generateForm.scope">
            <el-radio value="all">全部律师</el-radio>
            <el-radio value="one">指定律师</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="generateForm.scope === 'one'" label="律师">
          <el-input v-model="generateForm.lawyerId" placeholder="律师 ID（数字）" />
        </el-form-item>
      </el-form>
      <p class="dialog-hint">
        生成只写批次记录，不动余额、不产生资金流水。同律师同周期已存在批次时会跳过。
      </p>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="submitGenerate">
          {{ generating ? '生成中…' : '开始生成' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 明细抽屉 -->
    <el-drawer v-model="detailVisible" title="结算明细" size="720px">
      <div v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="结算单号">{{ detail.settlement.settlementNo }}</el-descriptions-item>
          <el-descriptions-item label="结算周期">{{ detail.settlement.period }}</el-descriptions-item>
          <el-descriptions-item label="律师">{{ detail.settlement.lawyerName }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(detail.settlement.status) }}</el-descriptions-item>
          <el-descriptions-item label="订单收入">{{ formatCents(detail.settlement.incomeCents) }}</el-descriptions-item>
          <el-descriptions-item label="平台服务费">
            {{ formatNegativeCents(detail.settlement.feeCents) }}
          </el-descriptions-item>
          <el-descriptions-item label="应结金额">{{ formatCents(detail.settlement.settleCents) }}</el-descriptions-item>
          <el-descriptions-item label="明细合计">{{ formatCents(detail.itemsTotalCents) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="detail.consistent === false"
          class="block-gap"
          type="warning"
          :closable="false"
          show-icon
          title="明细合计与订单收入不一致"
        >
          该批次生成后订单发生了变动，请人工核对后再确认。
        </el-alert>

        <el-table :data="detail.items" class="block-gap" border size="small">
          <el-table-column prop="orderNo" label="订单号" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="完成时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column label="订单金额" width="110" align="right">
            <template #default="{ row }">{{ formatCents(row.amountCents) }}</template>
          </el-table-column>
          <el-table-column label="服务费" width="110" align="right">
            <template #default="{ row }">
              <span class="fee-negative">{{ formatNegativeCents(row.feeCents) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="净收入" width="110" align="right">
            <template #default="{ row }">{{ formatCents(row.incomeCents) }}</template>
          </el-table-column>
          <template #empty>
            <el-empty description="该周期没有已完成订单" />
          </template>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/*
 * A-13 律师结算页逻辑。
 *
 * 副作用：列表/统计查询、生成批次、确认批次（只改状态）、回补入口、CSV 导出。
 * 注意：确认前必须二次确认，文案写清「不动余额、不转账」。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmSettlement,
  generateSettlements,
  getBackfillPending,
  getSettlementDetail,
  getSettlementSummary,
  listSettlements,
  runBackfill
} from '@/api/biz/settlement'
import { formatCents, formatDateTime, textOrDash } from '@/views/biz/shared/format'

/** 结算状态选项（与后端 SettlementStatus 一一对应）。 */
const STATUS_OPTIONS = [
  { value: 'pending', label: '待结算' },
  { value: 'settled', label: '已结算' },
  { value: 'rejected', label: '已驳回' }
]

/** 列表与分页状态。 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

/** 统计卡。 */
const summary = reactive({ pendingTotalCents: 0, settledThisMonthCents: 0, lawyerCount: 0 })

/** 查询条件。 */
const query = reactive({
  lawyerKw: '',
  period: '',
  status: '',
  pageNum: 1,
  pageSize: 20
})

/** 生成弹窗状态。 */
const generateVisible = ref(false)
const generating = ref(false)
const generateForm = reactive({ period: '', scope: 'all', lawyerId: '' })

/** 明细抽屉状态。 */
const detailVisible = ref(false)
const detail = ref(null)

/**
 * 最近 12 个账期（含当月），给下拉用。
 *
 * @returns {Array<{value:string,label:string}>} 账期选项
 */
function buildPeriodOptions() {
  const list = []
  const now = new Date()
  for (let i = 0; i < 12; i += 1) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
    list.push({ value, label: value })
  }
  return list
}

const periodOptions = buildPeriodOptions()

/**
 * 状态中文名。
 *
 * @param {string} status 状态值
 * @returns {string} 中文名
 */
function statusLabel(status) {
  const hit = STATUS_OPTIONS.find((item) => item.value === status)
  return hit ? hit.label : status
}

/**
 * 状态 tag 颜色。
 *
 * @param {string} status 状态值
 * @returns {string} Element Plus tag type
 */
function statusTagType(status) {
  if (status === 'settled') return 'success'
  if (status === 'rejected') return 'danger'
  return 'warning'
}

/**
 * 服务费显示为负数（设计稿口径）。
 *
 * @param {number} cents 服务费（分，正数）
 * @returns {string} 形如 `− ¥540.00`
 */
function formatNegativeCents(cents) {
  return `− ${formatCents(cents)}`
}

/** 加载列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listSettlements(query)
    rows.value = res.rows || []
    total.value = res.total || 0
  } catch (e) {
    error.value = e?.message || '请求失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 加载统计卡。 */
async function loadSummary() {
  try {
    const res = await getSettlementSummary()
    Object.assign(summary, res.data || {})
  } catch (e) {
    // 统计卡失败不阻断列表：页面主体是表格
    summary.pendingTotalCents = 0
    summary.settledThisMonthCents = 0
    summary.lawyerCount = 0
  }
}

/** 同时刷新列表与统计。 */
function loadAll() {
  loadList()
  loadSummary()
}

/** 查询（回到第 1 页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置查询条件。 */
function handleReset() {
  query.lawyerKw = ''
  query.period = ''
  query.status = ''
  query.pageNum = 1
  loadList()
}

/**
 * 打开生成弹窗。
 *
 * @param {object} [row] 传了 row 表示「重新生成」该律师该周期
 */
function openGenerate(row) {
  generateForm.period = row?.period || periodOptions[0].value
  generateForm.scope = row ? 'one' : 'all'
  generateForm.lawyerId = row ? String(row.lawyerId) : ''
  generateVisible.value = true
}

/** 提交生成。 */
async function submitGenerate() {
  if (generateForm.scope === 'one' && !generateForm.lawyerId) {
    ElMessage.warning('请填写律师 ID')
    return
  }
  generating.value = true
  try {
    const payload = { period: generateForm.period }
    if (generateForm.scope === 'one') {
      payload.lawyerId = generateForm.lawyerId
    }
    const res = await generateSettlements(payload)
    const data = res.data || {}
    ElMessage.success(`成功 ${data.generated || 0} 条 / 跳过 ${data.skipped || 0} 条（已存在）`)
    generateVisible.value = false
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    generating.value = false
  }
}

/**
 * 打开明细抽屉。
 *
 * @param {object} row 列表行
 */
async function openDetail(row) {
  detailVisible.value = true
  detail.value = null
  try {
    const res = await getSettlementDetail(row.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error(e?.message || '明细加载失败')
  }
}

/**
 * 确认结算（二次确认，文案写明不动钱）。
 *
 * @param {object} row 列表行
 */
async function handleConfirm(row) {
  try {
    await ElMessageBox.confirm(
      '确认后本批次标记为已结算。本操作不改动律师余额、不产生资金流水，'
        + '实际打款由财务在银行渠道完成转账后点确认。',
      '确认结算',
      { confirmButtonText: '确认结算', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await confirmSettlement(row.id)
    ElMessage.success('已确认（未改动任何余额与流水）')
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败')
  }
}

/** 收入回补：先看只读核对，再二次确认执行。 */
async function handleBackfill() {
  try {
    const pending = await getBackfillPending()
    const data = pending.data || {}
    if (!data.scanned) {
      ElMessage.success('没有待回补的订单（已完成订单都已入账）')
      return
    }
    await ElMessageBox.confirm(
      `待回补订单 ${data.scanned} 笔，净收入合计 ${formatCents(data.pendingCents)}。`
        + '回补会真实增加律师待结算余额，且不可自动撤销，确认执行？',
      '收入回补',
      { confirmButtonText: '执行回补', cancelButtonText: '取消', type: 'warning' }
    )
    const res = await runBackfill()
    const result = res.data || {}
    ElMessage.success(
      `回补完成：扫描 ${result.scanned || 0} 笔，入账 ${result.credited || 0} 笔，跳过 ${result.skipped || 0} 笔`
    )
    loadAll()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.message || '回补失败')
  }
}

/** 导出当前列表为 CSV（V1 不做 PDF）。 */
function handleExport() {
  if (!rows.value.length) {
    ElMessage.warning('当前列表为空，没有可导出的数据')
    return
  }
  const header = ['settlement_no', 'lawyer', 'period', 'order_count', 'income', 'fee', 'settle']
  const lines = rows.value.map((row) => [
    row.settlementNo,
    row.lawyerName || '',
    row.period,
    row.orderCount,
    (row.incomeCents / 100).toFixed(2),
    (row.feeCents / 100).toFixed(2),
    (row.settleCents / 100).toFixed(2)
  ])
  const csv = [header, ...lines]
    .map((cols) => cols.map((v) => `"${String(v).replaceAll('"', '""')}"`).join(','))
    .join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `lawyer-settlement-${Date.now()}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出当前页')
}

onMounted(loadAll)
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
  font-size: 20px;
  font-weight: 600;
}

.page-head__desc {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.page-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.block-gap {
  margin-bottom: 16px;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.stat-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
}

.cell-stack {
  display: flex;
  flex-direction: column;
  line-height: 1.5;
}

.cell-strong {
  font-weight: 500;
}

.cell-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mono {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.fee-negative {
  color: var(--el-color-danger);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dialog-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
