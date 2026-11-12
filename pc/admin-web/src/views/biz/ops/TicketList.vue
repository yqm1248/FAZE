<!--
  职责：PC 后台 A-21 工单列表——两个 Tab（处理 / 查看）+ 筛选 + 批量分配 + 关闭 + 导出。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/TicketList）。
  数据来源：src/api/biz/ops.js（第 7 段 C3 的接口）。
  注意：
    1. **Tab 是视图不是状态**：`处理` = waiting/processing，`查看` = replied/closed，
       由服务端 `tab` 参数切分，前端不自己过滤。
    2. **超时工单整行标红**（稿面要求）：服务端给 `overtime` 与 `overtimeHours`，前端只渲染。
    3. 关闭必须填原因（终态不可回退），提交后自动给用户发站内信。
    4. 「批量分配」在单管理员场景 = 批量指派给自己（裁决：只有一个管理员角色）。
-->
<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>工单管理</span>
          <el-button :loading="exporting" @click="handleExport">导出</el-button>
        </div>
      </template>

      <el-tabs v-model="query.tab" @tab-change="reload">
        <el-tab-pane label="处理" name="processing" />
        <el-tab-pane label="查看" name="finished" />
      </el-tabs>

      <el-form :inline="true" :model="query">
        <el-form-item label="工单">
          <el-input v-model="query.keyword" placeholder="单号或标题" clearable style="width: 180px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.category" clearable placeholder="全部" style="width: 120px">
            <el-option label="投诉" value="complaint" />
            <el-option label="反馈" value="feedback" />
            <el-option label="客服" value="service" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button :disabled="selectedIds.length === 0" @click="handleBatchAssign">
            批量分配（{{ selectedIds.length }}）
          </el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        :row-class-name="rowClass"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column prop="ticketNo" label="工单号" width="170" />
        <el-table-column prop="categoryLabel" label="类型" width="80" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="优先级" width="90">
          <template #default="{ row }">
            <el-tag :type="row.priority === 'high' || row.priority === 'urgent' ? 'danger' : 'info'" size="small">
              {{ row.priorityLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'closed' ? 'info' : 'primary'" size="small">{{ row.statusLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SLA" width="150">
          <template #default="{ row }">
            <span :class="{ overtime: row.overtime }">
              {{ row.overtime ? `超时 ${row.overtimeHours} 小时` : formatTime(row.slaDueAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="100">
          <template #default="{ row }">{{ row.agentId || '—' }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
            <el-button v-if="!row.agentId" link type="primary" @click="handleAssign(row)">分配</el-button>
            <el-button v-if="row.status !== 'closed'" link type="danger" @click="handleClose(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
        @size-change="reload"
      />
    </el-card>
  </div>
</template>

<script setup name="OpsTicketList">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTickets, assignTicket, batchAssignTickets, closeTicket, exportTickets } from '@/api/biz/ops'

const router = useRouter()
const loading = ref(false)
const exporting = ref(false)
const rows = ref([])
const total = ref(0)
const selectedIds = ref([])
const query = reactive({ tab: 'processing', keyword: '', category: '', pageNum: 1, pageSize: 10 })

/**
 * 加载列表。
 */
async function load() {
  loading.value = true
  try {
    const res = await listTickets(query)
    rows.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 重置到第一页。
 */
function reload() {
  query.pageNum = 1
  load()
}

/**
 * 重置筛选。
 */
function resetQuery() {
  query.keyword = ''
  query.category = ''
  reload()
}

/**
 * 表格选择变化。
 *
 * @param {object[]} selection 选中行
 */
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(r => r.id)
}

/**
 * 行样式：超时标红。
 *
 * @param {object} param 表格行参数
 * @returns {string} class 名
 */
function rowClass({ row }) {
  return row.overtime ? 'overtime-row' : ''
}

/**
 * 跳详情。
 *
 * @param {number} id 工单 id
 */
function goDetail(id) {
  router.push(`/ticket/detail/${id}`)
}

/**
 * 单个分配。
 *
 * @param {object} row 行数据
 */
async function handleAssign(row) {
  await assignTicket(row.id)
  ElMessage.success('已分配给当前管理员')
  load()
}

/**
 * 批量分配。
 */
async function handleBatchAssign() {
  const res = await batchAssignTickets(selectedIds.value)
  ElMessage.success(`已分配 ${res.data} 条`)
  load()
}

/**
 * 关闭工单。
 *
 * @param {object} row 行数据
 */
async function handleClose(row) {
  const { value } = await ElMessageBox.prompt(`关闭工单 ${row.ticketNo}？关闭后不可回退，将通知用户。`, '关闭工单', {
    inputPlaceholder: '关闭原因（必填）',
    inputValidator: v => !!v || '关闭原因不能为空'
  })
  await closeTicket(row.id, { reason: value })
  ElMessage.success('已关闭')
  load()
}

/**
 * 导出。
 */
async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportTickets({ tab: query.tab, keyword: query.keyword, category: query.category })
    const url = window.URL.createObjectURL(new Blob([blob]))
    const a = document.createElement('a')
    a.href = url
    a.download = 'tickets.csv'
    a.click()
    window.URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

/**
 * 时间格式化。
 *
 * @param {string} time ISO 时间
 * @returns {string} 本地时间
 */
function formatTime(time) {
  if (!time) return '—'
  const d = new Date(time)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
:deep(.overtime-row) {
  background-color: #fef0f0 !important;
}
.overtime {
  color: #f56c6c;
  font-weight: 600;
}
</style>
