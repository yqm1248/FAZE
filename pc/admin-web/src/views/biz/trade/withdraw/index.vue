<!--
  A-10 提现审核（第 5 段 F2 + F3）
  职责：提现队列查看、通过（含凭证上传）、驳回（含理由）、标记已出款、凭证预览。
  调用方：RuoYi 菜单「法泽业务 → 提现审核」（menu_id 2222）。
  数据来源：`/api/v1/admin/withdrawals/**`。
  注意：
    1. **「通过」与「已出款」是两步**：通过只到 `approved`（不动余额），
       点「已出款」才 `approved → paid` 并扣减冻结额 + 写 withdraw 流水。
       页面必须分成两个按钮，不能合并——合并就是绕过 §零之二 第 5 项的四态裁决。
    2. 顺序强调（设计稿原文）：「提现为人工出款：先在银行渠道完成转账，再点「通过」，
       并上传出款凭证留档。」——通过弹窗顶部提示照此写。
    3. 通过必须上传凭证（服务端会拒），驳回必须填理由。
    4. 凭证上传走 base64（原因见 FileRecordService#storeWithdrawVoucher 注释）；
       文件名只用于取扩展名，服务端白名单 jpg/jpeg/png/webp/pdf。
    5. 列表**不显示完整卡号**，只有「银行 · 尾号 4821」。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">提现审核</h1>
        <p class="page-head__desc">
          提现为人工出款：先在银行渠道完成转账，再点「通过」并上传出款凭证留档。
          通过不会改余额，点「已出款」才扣减冻结并写资金流水。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="提现列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadAll">重试</el-button>
      </div>
    </el-alert>

    <el-row :gutter="12" class="block-gap">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="stat-label">待审笔数</div>
          <div class="stat-value">{{ summary.pendingCount }} 笔</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="stat-label">待审金额</div>
          <div class="stat-value">{{ formatCents(summary.pendingAmountCents) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="stat-label">今日已审</div>
          <div class="stat-value">{{ summary.todayAuditedCount }} 笔</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="stat-label">本月已出款</div>
          <div class="stat-value">{{ formatCents(summary.monthPaidCents) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="80px" @submit.prevent>
        <el-form-item label="律师">
          <el-input
            v-model="query.lawyerKw"
            placeholder="姓名 / 律所 / 手机号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="提现状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 150px" @change="handleSearch">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请时间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 260px"
            @change="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column label="提现单号" width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.withdrawNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="律师" min-width="170">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-strong">{{ textOrDash(row.lawyerName) }}</span>
              <span class="cell-sub">{{ textOrDash(row.lawyerPhoneMasked) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="申请金额" width="120" align="right">
          <template #default="{ row }">{{ formatCents(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column label="手续费" width="110" align="right">
          <template #default="{ row }">
            <span class="fee-negative">{{ formatNegativeCents(row.feeCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实际到账" width="120" align="right">
          <template #default="{ row }">
            <strong>{{ formatCents(row.actualCents) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="收款账户" min-width="170">
          <template #default="{ row }">
            {{ formatAccount(row) }}
          </template>
        </el-table-column>
        <el-table-column label="申请时间 / 状态" width="180">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-sub">{{ formatDateTime(row.createdAt) }}</span>
              <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'applying'"
              type="success"
              link
              @click="openApprove(row)"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status === 'applying'"
              type="danger"
              link
              @click="openReject(row)"
            >
              驳回
            </el-button>
            <el-button
              v-if="row.status === 'approved'"
              type="primary"
              link
              @click="handleMarkPaid(row)"
            >
              已出款
            </el-button>
            <el-button
              v-if="row.voucherFileId"
              type="primary"
              link
              @click="previewVoucher(row)"
            >
              凭证
            </el-button>
            <el-button
              v-if="row.status === 'rejected'"
              type="info"
              link
              @click="showRejectReason(row)"
            >
              驳回理由
            </el-button>
            <el-button
              v-if="!row.voucherFileId && (row.status === 'approved' || row.status === 'paid')"
              type="warning"
              link
              @click="openVoucherOnly(row)"
            >
              补传凭证
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该条件下没有提现单" />
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

    <!-- 通过（必须传凭证） -->
    <el-dialog v-model="approveVisible" title="通过提现" width="520px">
      <el-alert type="warning" :closable="false" show-icon class="block-gap"
                title="请先在银行渠道完成转账，再点确认并上传出款凭证留档。" />
      <el-descriptions v-if="activeRow" :column="2" border size="small">
        <el-descriptions-item label="提现单号">{{ activeRow.withdrawNo }}</el-descriptions-item>
        <el-descriptions-item label="律师">{{ activeRow.lawyerName }}</el-descriptions-item>
        <el-descriptions-item label="申请金额">{{ formatCents(activeRow.amountCents) }}</el-descriptions-item>
        <el-descriptions-item label="实际到账">{{ formatCents(activeRow.actualCents) }}</el-descriptions-item>
        <el-descriptions-item label="收款账户" :span="2">{{ formatAccount(activeRow) }}</el-descriptions-item>
      </el-descriptions>
      <el-form :model="approveForm" label-width="100px" class="block-gap">
        <el-form-item label="出款凭证">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="handleVoucherChange"
            :on-remove="handleVoucherRemove"
            accept=".jpg,.jpeg,.png,.webp,.pdf"
          >
            <el-button type="primary" plain>选择文件</el-button>
            <template #tip>
              <span class="upload-tip">支持 jpg / png / webp / pdf，必填</span>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="出款流水号">
          <el-input v-model="approveForm.transactionId" placeholder="网银流水号（选填）" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApprove">
          {{ submitting ? '提交中…' : '确认通过' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 驳回（必须填理由） -->
    <el-dialog v-model="rejectVisible" title="驳回提现" width="460px">
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="驳回理由" required>
          <el-input
            v-model="rejectForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="驳回后律师的冻结金额会立即退回可提现余额"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 补传凭证 -->
    <el-dialog v-model="voucherVisible" title="补传出款凭证" width="460px">
      <el-upload
        :auto-upload="false"
        :limit="1"
        :on-change="handleVoucherChange"
        :on-remove="handleVoucherRemove"
        accept=".jpg,.jpeg,.png,.webp,.pdf"
      >
        <el-button type="primary" plain>选择文件</el-button>
      </el-upload>
      <template #footer>
        <el-button @click="voucherVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitVoucherOnly">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/*
 * A-10 提现审核页逻辑。
 *
 * 副作用：列表/统计查询、通过（上传凭证）、驳回（解冻）、标记已出款（动账）、凭证预览。
 * 注意：通过 ≠ 出款。通过只改状态，已出款才扣冻结并写流水。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  auditWithdrawal,
  getVoucherPreview,
  getWithdrawSummary,
  listWithdrawals,
  markWithdrawalPaid,
  uploadWithdrawalVoucher
} from '@/api/biz/withdraw'
import { formatCents, formatDateTime, textOrDash } from '@/views/biz/shared/format'

/**
 * 当前路由。
 *
 * A-02「待我处理」的提现待办会跳到本页并带 `status=applying`，
 * 本页负责把这个参数落进筛选条件（否则跳转后仍显示全部状态，
 * 与看板上的「提现申请待审核 N」对不上）。
 */
const route = useRoute()

/** 提现状态选项（与后端 WithdrawStatus 一一对应）。 */
const STATUS_OPTIONS = [
  { value: 'applying', label: '待审核' },
  { value: 'approved', label: '已通过' },
  { value: 'rejected', label: '已驳回' },
  { value: 'paid', label: '已出款' }
]

/** 列表与分页。 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

/** 统计卡。 */
const summary = reactive({
  pendingCount: 0,
  pendingAmountCents: 0,
  todayAuditedCount: 0,
  monthPaidCents: 0
})

/** 查询条件。 */
const query = reactive({ lawyerKw: '', status: '', pageNum: 1, pageSize: 20 })
const dateRange = ref([])

/** 弹窗状态。 */
const approveVisible = ref(false)
const rejectVisible = ref(false)
const voucherVisible = ref(false)
const submitting = ref(false)
const activeRow = ref(null)
const approveForm = reactive({ transactionId: '', fileName: '', contentType: '', contentBase64: '' })
const rejectForm = reactive({ reason: '' })

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
  if (status === 'paid') return 'success'
  if (status === 'approved') return 'primary'
  if (status === 'rejected') return 'danger'
  return 'warning'
}

/**
 * 手续费显示为负数。
 *
 * @param {number} cents 手续费（分）
 * @returns {string} 形如 `− ¥6.00`
 */
function formatNegativeCents(cents) {
  return `− ${formatCents(cents)}`
}

/**
 * 收款账户展示（银行 · 尾号）。
 *
 * @param {object} row 列表行
 * @returns {string} 展示文案
 */
function formatAccount(row) {
  if (!row?.bankName && !row?.bankAccountTail) {
    return '—'
  }
  return `${textOrDash(row.bankName)} · 尾号 ${textOrDash(row.bankAccountTail)}`
}

/** 加载列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const params = { ...query }
    if (dateRange.value && dateRange.value.length === 2) {
      ;[params.beginDate, params.endDate] = dateRange.value
    }
    const res = await listWithdrawals(params)
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
    const res = await getWithdrawSummary()
    Object.assign(summary, res.data || {})
  } catch (e) {
    summary.pendingCount = 0
    summary.pendingAmountCents = 0
    summary.todayAuditedCount = 0
    summary.monthPaidCents = 0
  }
}

/** 同时刷新列表与统计。 */
function loadAll() {
  loadList()
  loadSummary()
}

/** 查询（回第 1 页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置查询。 */
function handleReset() {
  query.lawyerKw = ''
  query.status = ''
  dateRange.value = []
  query.pageNum = 1
  loadList()
}

/**
 * 打开「通过」弹窗。
 *
 * @param {object} row 列表行
 */
function openApprove(row) {
  activeRow.value = row
  approveForm.transactionId = ''
  approveForm.fileName = ''
  approveForm.contentType = ''
  approveForm.contentBase64 = ''
  approveVisible.value = true
}

/**
 * 打开「驳回」弹窗。
 *
 * @param {object} row 列表行
 */
function openReject(row) {
  activeRow.value = row
  rejectForm.reason = ''
  rejectVisible.value = true
}

/**
 * 打开「补传凭证」弹窗。
 *
 * @param {object} row 列表行
 */
function openVoucherOnly(row) {
  activeRow.value = row
  approveForm.fileName = ''
  approveForm.contentType = ''
  approveForm.contentBase64 = ''
  voucherVisible.value = true
}

/**
 * 读取所选文件为 base64。
 *
 * @param {object} uploadFile Element Plus 的 upload-file
 */
function handleVoucherChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) {
    return
  }
  if (raw.size > 10 * 1024 * 1024) {
    ElMessage.error('文件不能超过 10MB')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const result = String(reader.result || '')
    const comma = result.indexOf(',')
    approveForm.contentBase64 = comma >= 0 ? result.slice(comma + 1) : result
    approveForm.fileName = raw.name
    approveForm.contentType = raw.type || 'application/octet-stream'
  }
  reader.readAsDataURL(raw)
}

/** 清空已选文件。 */
function handleVoucherRemove() {
  approveForm.fileName = ''
  approveForm.contentType = ''
  approveForm.contentBase64 = ''
}

/** 提交「通过」。 */
async function submitApprove() {
  if (!approveForm.contentBase64) {
    ElMessage.warning('通过审核必须上传出款凭证')
    return
  }
  submitting.value = true
  try {
    // 先上传凭证拿到 id（服务端落盘并登记 file_record），再审核通过
    const uploaded = await uploadWithdrawalVoucher(activeRow.value.id, {
      fileName: approveForm.fileName,
      contentType: approveForm.contentType,
      contentBase64: approveForm.contentBase64
    })
    const voucherFileId = uploaded.data?.voucherFileId
    await auditWithdrawal(activeRow.value.id, {
      action: 'approve',
      voucherFileId: String(voucherFileId),
      transactionId: approveForm.transactionId || undefined
    })
    ElMessage.success('已通过（资金未变动，请在银行转账后点「已出款」）')
    approveVisible.value = false
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '审核失败')
  } finally {
    submitting.value = false
  }
}

/** 提交「驳回」。 */
async function submitReject() {
  if (!rejectForm.reason.trim()) {
    ElMessage.warning('驳回必须填写理由')
    return
  }
  submitting.value = true
  try {
    await auditWithdrawal(activeRow.value.id, { action: 'reject', reason: rejectForm.reason.trim() })
    ElMessage.success('已驳回，冻结金额已退回可提现余额')
    rejectVisible.value = false
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '驳回失败')
  } finally {
    submitting.value = false
  }
}

/** 提交「补传凭证」。 */
async function submitVoucherOnly() {
  if (!approveForm.contentBase64) {
    ElMessage.warning('请选择文件')
    return
  }
  submitting.value = true
  try {
    await uploadWithdrawalVoucher(activeRow.value.id, {
      fileName: approveForm.fileName,
      contentType: approveForm.contentType,
      contentBase64: approveForm.contentBase64
    })
    ElMessage.success('凭证已上传')
    voucherVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 标记已出款（真实动账：扣冻结 + 写 withdraw 流水）。
 *
 * @param {object} row 列表行
 */
async function handleMarkPaid(row) {
  try {
    await ElMessageBox.confirm(
      `确认已在银行完成本笔 ${formatCents(row.actualCents)} 的出款？`
        + '本操作会把「已通过」推进到「已出款」，扣减律师待结算金额并写入资金流水，不可撤销。',
      '标记已出款',
      { confirmButtonText: '确认已出款', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await markWithdrawalPaid(row.id)
    ElMessage.success('已标记出款')
    loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

/**
 * 打开凭证预览（60 秒签名 URL）。
 *
 * @param {object} row 列表行
 */
async function previewVoucher(row) {
  try {
    const res = await getVoucherPreview(row.id)
    const url = res.data?.url
    if (!url) {
      ElMessage.warning('没有可预览的凭证')
      return
    }
    window.open(url, '_blank', 'noopener')
  } catch (e) {
    ElMessage.error(e?.message || '凭证加载失败')
  }
}

/**
 * 显示驳回理由。
 *
 * @param {object} row 列表行
 */
function showRejectReason(row) {
  ElMessageBox.alert(row.rejectReason || '（未填写理由）', '驳回理由', { confirmButtonText: '知道了' })
}

/**
 * 从路由 query 承接筛选条件（A-02「待我处理」跳转带参）。
 *
 * 只接受字符串且非空的状态值；非法值忽略（服务端会按枚举校验，前端不替它兜错值）。
 * 副作用：写 query.status。
 */
function applyRouteQuery() {
  const { status } = route.query
  if (typeof status === 'string' && status) {
    query.status = status
  }
}

onMounted(() => {
  applyRouteQuery()
  loadAll()
})
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

.upload-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
