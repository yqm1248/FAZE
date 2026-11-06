<!--
  职责：PC A-12 订单详情 —— 订单信息、状态流转时间线、支付记录（段 2 两条）、退款单与审批、
        关联会话与聊天记录抽屉（G9.2 + G4.2 审批入口 + 拍板第 5/6/7 项）。
  调用方：RuoYi 动态路由（sys_menu 菜单：订单详情，隐藏菜单；或从 A-11 行内跳转）。
  数据来源：
    GET  /api/v1/admin/orders/{id}                 详情（含 payments / refunds / conversationId）
    GET  /api/v1/admin/orders/{id}/status-log      状态流转（来自 order_status_log）
    GET  /api/v1/admin/refunds?orderId=            退款单（与详情里的 refunds 同源，审批后重拉）
    POST /api/v1/admin/refunds/{id}/audit          审批（通过 / 驳回）
    GET  /api/v1/admin/conversations/{id}/messages 聊天记录（每次调用服务端写 operation_log）
  注意：
    1. **状态流转必须来自 order_status_log**（工作流 G9.2 第 2 条）：不能用 orders 的四个时间戳拼，
       那样缺 refunding / refund_rejected 节点，2-A6 会露馅。
    2. **支付记录段 2 显示 2 条**（支付 + 退款），页面注明「服务费结算见段 5」（拍板第 5 项）。
    3. **关联会话按 (user_id, lawyer_id)**（拍板第 6 项），显示会话 id；未聊过显示「尚未建立会话」，
       **不编造 CV 编号**。
    4. **查看聊天记录每次调用都写 operation_log**（拍板第 7 项）：抽屉打开即取第一页，
       翻页也调接口（每页都会留痕）。
    5. 审批弹窗：通过可选备注，驳回**必填原因**（服务端也会拦）；审批成功后整体重拉。
    6. 四态：加载中 / 错误（常驻 alert + 重试）/ 正常；聊天抽屉单独有加载与空态。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">订单详情</h1>
        <p class="page-head__desc">
          单号 <span class="mono">{{ detail ? detail.orderNo : '—' }}</span>
          <span v-if="detail"> · 状态 <el-tag :type="statusTagType(detail.status)" effect="light" size="small">{{ statusText(detail.status) }}</el-tag></span>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" class="block-gap" type="error" :closable="false" show-icon title="订单详情加载失败">
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadAll">重试</el-button>
      </div>
    </el-alert>

    <div v-loading="loading">
      <template v-if="detail">
        <el-card class="block-gap" shadow="never">
          <template #header><span class="card-title">订单信息</span></template>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="订单号"><span class="mono">{{ detail.orderNo }}</span></el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusTagType(detail.status)" effect="light" size="small">{{ statusText(detail.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="服务类型">{{ serviceText(detail.serviceType) }}</el-descriptions-item>
            <el-descriptions-item label="订单金额"><span class="mono">¥ {{ formatCents(detail.amountCents) }}</span></el-descriptions-item>
            <el-descriptions-item label="下单用户">
              {{ textOrDash(detail.userName) }} · <span class="mono">{{ textOrDash(detail.userPhoneMasked) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="承办律师">
              {{ textOrDash(detail.lawyerName) }} <span v-if="detail.lawyerFirm">· {{ detail.lawyerFirm }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="下单时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="支付时间">{{ detail.paidAt ? formatDateTime(detail.paidAt) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="接单时间">{{ detail.acceptedAt ? formatDateTime(detail.acceptedAt) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="完成时间">{{ detail.finishedAt ? formatDateTime(detail.finishedAt) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="取消时间">{{ detail.cancelledAt ? formatDateTime(detail.cancelledAt) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="用户备注">{{ textOrDash(detail.remark) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card class="block-gap" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">关联会话</span>
              <div class="card-head__actions">
                <span v-if="detail.conversationId" class="mono muted">会话 ID {{ detail.conversationId }}</span>
                <el-button
                  v-if="detail.conversationId"
                  type="primary"
                  link
                  @click="openChat"
                >
                  查看聊天记录
                </el-button>
                <span v-else class="muted">该订单尚未建立会话（用户还没联系律师）</span>
              </div>
            </div>
          </template>
          <p class="hint">会话与服务订单解耦：一个用户与一个律师只有一条会话，跨订单复用（拍板第 6 项）。</p>
        </el-card>

        <el-card class="block-gap" shadow="never">
          <template #header><span class="card-title">状态流转</span></template>
          <el-timeline v-if="logs.length > 0">
            <el-timeline-item
              v-for="log in logs"
              :key="log.id"
              :timestamp="formatDateTime(log.createdAt)"
              :type="log.toStatus === 'completed' ? 'success' : (log.toStatus === 'cancelled' || log.toStatus === 'refunded' ? 'danger' : 'primary')"
            >
              <div class="timeline-line">
                <span class="mono">{{ log.fromStatus || '—' }} → {{ log.toStatus }}</span>
                <span class="muted">操作人：{{ operatorText(log.operatorType) }}{{ log.operatorId ? ' #' + log.operatorId : '' }}</span>
              </div>
              <p v-if="log.reason" class="timeline-reason">{{ log.reason }}</p>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无状态流水（历史记录缺失，不补假数据）" :image-size="64" />
        </el-card>

        <el-card class="block-gap" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">支付记录</span>
              <span class="muted">段 2 只有余额渠道；退款行为负数。服务费结算见段 5。</span>
            </div>
          </template>
          <el-table :data="detail.payments" row-key="id" stripe style="width: 100%">
            <template #empty><el-empty description="暂无支付记录" :image-size="64" /></template>
            <el-table-column label="流水号" min-width="150">
              <template #default="{ row }"><span class="mono">{{ textOrDash(row.transactionId) }}</span></template>
            </el-table-column>
            <el-table-column label="渠道" min-width="90">
              <template #default="{ row }">{{ row.channel === 'balance' ? '账户余额' : row.channel }}</template>
            </el-table-column>
            <el-table-column label="金额" min-width="110" align="right">
              <template #default="{ row }">
                <span class="mono" :class="toNumber(row.amountCents) < 0 ? 'amount-out' : 'amount-in'">
                  ¥ {{ formatCents(row.amountCents) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="{ row }">{{ row.status }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="140">
              <template #default="{ row }"><span class="mono muted">{{ formatDateTime(row.paidAt) }}</span></template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">退款单</span></template>
          <el-table :data="detail.refunds" row-key="id" stripe style="width: 100%">
            <template #empty><el-empty description="该订单没有退款单" :image-size="64" /></template>
            <el-table-column label="退款单 ID" min-width="100">
              <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="申请金额" min-width="110" align="right">
              <template #default="{ row }"><span class="mono">¥ {{ formatCents(row.amountCents) }}</span></template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="{ row }">
                <el-tag :type="refundTagType(row.status)" effect="light" size="small">{{ refundText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="申请原因" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ textOrDash(row.reason) }}</template>
            </el-table-column>
            <el-table-column label="审批意见" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ textOrDash(row.auditRemark) }}</template>
            </el-table-column>
            <el-table-column label="申请时间" min-width="140">
              <template #default="{ row }"><span class="mono muted">{{ formatDateTime(row.createdAt) }}</span></template>
            </el-table-column>
            <el-table-column label="操作" min-width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'applying'" type="primary" link @click="openAudit(row)">审批</el-button>
                <span v-else class="muted">已处理</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>
    </div>

    <el-dialog v-model="auditVisible" title="退款审批" width="480px" :close-on-click-modal="false">
      <el-form :model="auditForm" label-width="90px">
        <el-form-item label="审批动作">
          <el-radio-group v-model="auditForm.approved">
            <el-radio :value="true">通过（退款入账）</el-radio>
            <el-radio :value="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="auditForm.approved ? '备注' : '驳回原因'" :required="!auditForm.approved">
          <el-input
            v-model="auditForm.remark"
            type="textarea"
            :rows="3"
            :placeholder="auditForm.approved ? '可选' : '必填：告诉用户为什么不能退'"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditing" @click="submitAudit">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="chatVisible" title="聊天记录" size="520px" :destroy-on-close="true">
      <div class="chat-hint">
        管理端只能查看，不能发送或撤回。每次打开与翻页都会写一条审计记录（拍板第 7 项）。
      </div>
      <div v-loading="chatLoading" class="chat-list">
        <el-button v-if="chatHasMore" link type="primary" :loading="chatLoading" @click="loadMoreChat">
          加载更早的消息
        </el-button>
        <el-empty v-if="!chatLoading && chatMessages.length === 0" description="这段会话还没有消息" :image-size="64" />
        <div v-for="message in chatMessages" :key="message.id" class="chat-row" :class="message.senderRole === 'lawyer' ? 'chat-row--lawyer' : 'chat-row--user'">
          <div class="chat-meta">
            <span>{{ message.senderRole === 'lawyer' ? '律师' : (message.senderRole === 'system' ? '系统' : '用户') }}</span>
            <span class="mono muted">#{{ message.seq }} · {{ formatDateTime(message.createdAt) }}</span>
          </div>
          <div class="chat-bubble" :class="message.recalled ? 'chat-bubble--recalled' : ''">
            <span v-if="message.recalled" class="recall-tag">已撤回（原文存档）</span>
            <p class="chat-content">{{ message.content || (message.msgType === 'image' ? '[图片]' : '[文件]') }}</p>
            <p v-if="message.fileName" class="chat-file">{{ message.fileName }}</p>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 订单详情页脚本。
 *
 * 副作用：挂载时并行拉详情与状态流水；审批后整体重拉；打开抽屉时拉第一页聊天记录。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auditRefund, getOrderDetail, getOrderStatusLog, listChatMessages } from '@/api/biz/order'
import { formatCents, formatDateTime, textOrDash, toNumber } from '@/views/biz/shared/format'

/** 状态文案。 */
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

/** 状态标签颜色。 */
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

/** 服务类型文案。 */
const SERVICE_TEXT = {
  consult_text: '图文咨询',
  consult_phone: '电话咨询',
  document: '文书代写',
  case: '诉讼代理'
}

/** 操作人类型文案。 */
const OPERATOR_TEXT = {
  USER: '用户',
  LAWYER: '律师',
  SYSTEM: '系统',
  ADMIN: '管理员'
}

/** 退款单状态文案。 */
const REFUND_TEXT = { applying: '待审批', refunded: '已退款', rejected: '已驳回' }

/** 退款单状态颜色。 */
const REFUND_TAG = { applying: 'warning', refunded: 'success', rejected: 'danger' }

const route = useRoute()
const router = useRouter()
const orderId = String(route.query.id || '')
const detail = ref(null)
const logs = ref([])
const loading = ref(false)
const error = ref('')

const auditVisible = ref(false)
const auditing = ref(false)
const auditForm = reactive({ refundId: '', approved: true, remark: '' })

const chatVisible = ref(false)
const chatLoading = ref(false)
const chatMessages = ref([])
const chatHasMore = ref(false)
const chatCursor = ref('')

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

/** 操作人类型文案。 */
function operatorText(type) {
  return OPERATOR_TEXT[type] || type || '—'
}

/** 退款单状态文案。 */
function refundText(status) {
  return REFUND_TEXT[status] || status || '—'
}

/** 退款单状态颜色。 */
function refundTagType(status) {
  return REFUND_TAG[status] || 'info'
}

/** 并行拉详情与状态流水。 */
async function loadAll() {
  if (!orderId) {
    error.value = '缺少订单 id（请从订单列表进入）'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [detailRes, logRes] = await Promise.all([getOrderDetail(orderId), getOrderStatusLog(orderId)])
    detail.value = detailRes.data
    logs.value = logRes.data || []
  } catch (e) {
    error.value = e.message || '订单详情加载失败'
    detail.value = null
    logs.value = []
  } finally {
    loading.value = false
  }
}

/** 返回列表。 */
function goBack() {
  router.push('/biz/trade/order')
}

/** 打开审批弹窗。 */
function openAudit(row) {
  auditForm.refundId = row.id
  auditForm.approved = true
  auditForm.remark = ''
  auditVisible.value = true
}

/** 提交审批。 */
async function submitAudit() {
  if (!auditForm.approved && !auditForm.remark.trim()) {
    ElMessage.warning('驳回必须填写原因')
    return
  }
  auditing.value = true
  try {
    await auditRefund(auditForm.refundId, {
      approved: auditForm.approved,
      remark: auditForm.remark.trim() || undefined
    })
    ElMessage.success(auditForm.approved ? '已通过，退款已入账' : '已驳回')
    auditVisible.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '审批失败')
  } finally {
    auditing.value = false
  }
}

/** 打开聊天记录抽屉并拉第一页。 */
async function openChat() {
  chatVisible.value = true
  chatMessages.value = []
  chatCursor.value = ''
  chatHasMore.value = false
  await loadChatPage('')
}

/**
 * 拉一页聊天记录（cursor 为空表示最新一页）。
 *
 * @param {string} cursor 游标（不含该 seq）
 */
async function loadChatPage(cursor) {
  if (!detail.value || !detail.value.conversationId) {
    return
  }
  chatLoading.value = true
  try {
    const res = await listChatMessages(detail.value.conversationId, cursor ? { cursor, limit: 30 } : { limit: 30 })
    const page = res.data
    chatMessages.value = [...(page.list || []), ...chatMessages.value]
    chatHasMore.value = page.hasMore
    chatCursor.value = page.nextCursor || ''
  } catch (e) {
    ElMessage.error(e.message || '聊天记录加载失败')
  } finally {
    chatLoading.value = false
  }
}

/** 加载更早的消息。 */
function loadMoreChat() {
  if (chatCursor.value) {
    loadChatPage(chatCursor.value)
  }
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
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
}
.page-head__desc {
  margin: 0;
  color: #6b7280;
  font-size: 13px;
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
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-head__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-title {
  font-weight: 600;
}
.hint {
  margin: 0;
  color: #6b7280;
  font-size: 13px;
}
.timeline-line {
  display: flex;
  gap: 12px;
  align-items: center;
}
.timeline-reason {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 13px;
}
.amount-in {
  color: #111827;
}
.amount-out {
  color: #dc2626;
}
.chat-hint {
  padding: 8px 12px;
  margin-bottom: 12px;
  background: #f3f4f6;
  border-radius: 4px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
}
.chat-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chat-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chat-row--lawyer {
  align-items: flex-end;
}
.chat-meta {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: #6b7280;
}
.chat-bubble {
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f3f4f6;
}
.chat-row--lawyer .chat-bubble {
  background: #e0e7ff;
}
.chat-bubble--recalled {
  opacity: 0.75;
}
.recall-tag {
  display: inline-block;
  margin-bottom: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fef3c7;
  color: #92400e;
  font-size: 11px;
}
.chat-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
.chat-file {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: #6b7280;
  font-size: 12px;
}
</style>
