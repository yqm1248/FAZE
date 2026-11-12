<!--
  职责：PC A-11 订单列表 —— 状态/时间/律师/用户筛选、分页、CSV 导出（G9.1）。
  调用方：RuoYi 动态路由（sys_menu 菜单：订单管理，component = biz/trade/order/index）。
  数据来源：faze-api `GET /api/v1/admin/orders`（RuoYi 口径 rows/total）。
  注意：
    1. **金额统一用 formatCents（分→元）**，页面不做第二套换算。
    2. **8 种状态各自颜色**：颜色只用 Element Plus 的 tag type（success/warning/danger/info/primary），
       不自定义色值——PC 后台色板继承设计令牌，页面写死颜色会与 tokens 漂移。
    3. 导出在段 2 是**前端同步导出当前筛选结果的前 1000 条**（CSV）。
       服务端异步导出属段 7（>5000 行才必须异步）；这里留了 `EXPORT_LIMIT` 与注释，
       数据量上来后改成任务表导出。
    4. 四态：加载中（表格 v-loading）/ 空（el-empty）/ 错误（常驻 el-alert + 重试）/ 正常。
    5. 时间展示统一走 formatDateTime（UTC → 本地），不做字符串截断。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">订单管理</h1>
        <p class="page-head__desc">
          全部订单按创建时间倒序。状态由服务端状态机推进，本页只读；退款审批在订单详情页。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="exporting" :disabled="rows.length === 0" @click="handleExport">导出当前筛选</el-button>
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="订单列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="订单状态">
          <el-select v-model="query.status" style="width: 150px" clearable placeholder="全部" @change="handleSearch">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="下单时间">
          <el-date-picker
            v-model="timeRange"
            type="daterange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="defaultRangeTime"
            range-separator="至"
            start-placeholder="起始"
            end-placeholder="截止"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="律师 ID">
          <el-input v-model="query.lawyerId" placeholder="可空" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input v-model="query.userId" placeholder="可空" clearable style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__count">共 {{ total }} 条</span>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '订单加载失败，请点上方「重试」' : '当前筛选条件下没有订单'"
            :image-size="72"
          />
        </template>
        <el-table-column label="订单号" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务" min-width="100">
          <template #default="{ row }">{{ serviceText(row.serviceType) }}</template>
        </el-table-column>
        <el-table-column label="金额" min-width="100" align="right">
          <template #default="{ row }">
            <span class="mono">¥ {{ formatCents(row.amountCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" min-width="150">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">{{ textOrDash(row.userName) }}</span>
              <span class="cell-stack__sub mono">{{ textOrDash(row.userPhoneMasked) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="律师" min-width="120">
          <template #default="{ row }">{{ textOrDash(row.lawyerName) }}</template>
        </el-table-column>
        <el-table-column label="下单时间" min-width="130">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="支付时间" min-width="130">
          <template #default="{ row }">
            <span class="mono muted">{{ row.paidAt ? formatDateTime(row.paidAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        class="table-pagination"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadList"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup>
/**
 * 订单列表页脚本。
 *
 * 副作用：挂载时拉第一页；筛选/分页重拉；导出时按当前筛选拉最多 1000 行生成 CSV。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listOrders } from '@/api/biz/order'
import { formatCents, formatDateTime, textOrDash } from '@/views/biz/shared/format'

/** 导出上限（段 2 同步导出；超过 1000 行属段 7 的异步导出任务）。 */
const EXPORT_LIMIT = 1000

/** 状态文案与颜色。 */
const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'draft', label: '草稿' },
  { value: 'pending', label: '待支付' },
  { value: 'paid', label: '等待接单' },
  { value: 'in_service', label: '进行中' },
  { value: 'completed', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
  { value: 'refunding', label: '退款中' },
  { value: 'refunded', label: '已退款' },
  { value: 'refund_rejected', label: '退款被驳回' }
]

/** 状态 → 文案。 */
const STATUS_TEXT = {
  draft: '草稿',
  pending: '待支付',
  paid: '等待接单',
  in_service: '进行中',
  completed: '已完成',
  cancelled: '已取消',
  refunding: '退款中',
  refunded: '已退款',
  refund_rejected: '退款被驳回'
}

/** 状态 → 标签颜色。 */
const STATUS_TAG = {
  draft: 'info',
  pending: 'warning',
  paid: 'warning',
  in_service: 'primary',
  completed: 'success',
  cancelled: 'info',
  refunding: 'warning',
  refunded: 'danger',
  refund_rejected: 'danger'
}

/** 服务类型 → 文案。 */
const SERVICE_TEXT = {
  consult_text: '图文咨询',
  consult_phone: '电话咨询',
  document: '文书代写',
  case: '诉讼代理'
}

/** 区间选择器默认时间（与段 1 审核页一致）。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

const router = useRouter()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const timeRange = ref([])
const query = reactive({ status: '', lawyerId: '', userId: '', pageNum: 1, pageSize: 10 })

/** 状态文案。 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/** 状态标签颜色。 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/** 服务类型文案。 */
function serviceText(type) {
  return SERVICE_TEXT[type] || type || '—'
}

/**
 * 把筛选条件转成接口参数（去掉空值，时间区间拆成两端）。
 *
 * @returns {object} 接口参数
 */
function buildParams() {
  const params = {
    pageNum: query.pageNum,
    pageSize: query.pageSize
  }
  if (query.status) {
    params.status = query.status
  }
  if (query.lawyerId) {
    params.lawyerId = query.lawyerId
  }
  if (query.userId) {
    params.userId = query.userId
  }
  if (timeRange.value && timeRange.value.length === 2) {
    params.startDate = timeRange.value[0]
    params.endDate = timeRange.value[1]
  }
  return params
}

/** 拉列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const data = await listOrders(buildParams())
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    error.value = e.message || '订单列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询（回到第一页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置筛选。 */
function handleReset() {
  query.status = ''
  query.lawyerId = ''
  query.userId = ''
  query.pageNum = 1
  query.pageSize = 10
  timeRange.value = []
  loadList()
}

/** 打开详情。 */
function openDetail(row) {
  router.push({ path: '/order/detail', query: { id: row.id } })
}

/** CSV 单元格转义。 */
function csvCell(value) {
  const text = value === null || value === undefined ? '' : String(value)
  return `"${text.replace(/"/g, '""')}"`
}

/**
 * 导出当前筛选结果（最多 EXPORT_LIMIT 行）。
 *
 * 说明：段 2 用前端同步导出；数据量超过 1000 行时应改走任务表异步导出（段 7）。
 */
async function handleExport() {
  exporting.value = true
  try {
    const params = { ...buildParams(), pageNum: 1, pageSize: EXPORT_LIMIT }
    const data = await listOrders(params)
    const list = data.rows || []
    const header = ['订单号', '状态', '服务类型', '金额(分)', '用户昵称', '用户手机号(脱敏)', '律师', '下单时间', '支付时间']
    const lines = [header.map(csvCell).join(',')]
    list.forEach((row) => {
      lines.push([
        row.orderNo,
        statusText(row.status),
        serviceText(row.serviceType),
        row.amountCents,
        row.userName,
        row.userPhoneMasked,
        row.lawyerName,
        row.createdAt,
        row.paidAt
      ].map(csvCell).join(','))
    })
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `orders-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${list.length} 条`)
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
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
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
}
.page-head__desc {
  margin: 0;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.6;
}
.page-head__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.block-gap {
  margin-bottom: 16px;
}
.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.table-toolbar__count {
  color: #6b7280;
  font-size: 13px;
}
.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cell-stack__main {
  color: #111827;
}
.cell-stack__sub {
  color: #6b7280;
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: #6b7280;
}
</style>
