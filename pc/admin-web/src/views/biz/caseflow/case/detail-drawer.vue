<!--
  职责：PC A-19 案件详情抽屉（段 5 B2/C1）——三个 Tab：立案材料 / 进度 / 送达。
  调用方：src/views/biz/caseflow/case/index.vue（点「详情」或「推进节点」打开）。
  数据来源：getCaseDetail / listCaseProgress / listCaseDeliveries / listCaseDocuments /
           auditCase / correctCaseProgress / sendCaseDelivery。
  注意：
    1. **必须是抽屉，不是新页面**：RuoYi 单页范式，框架层布局（src/layout/）不许动。
    2. 「立案材料」Tab 的 `form_snapshot` 是四步表单原样 JSON，**只读渲染四个分组**；
       身份证与手机号按脱敏分层展示（身份证留前 6 后 4、手机号留前 3 后 4），
       页面不需要完整证件号——审核看的是材料是否齐备，不是读号码。
    3. 审核：通过 → accepted、驳回 → rejected，**驳回理由必填**（≤500 字），
       审计动作码 CASE_AUDIT 由服务端 Service 上的 `@OperationLog` 采集。
    4. 「进度」Tab 的纠错**必须填原因**（映射到 `description`），审计动作码
       CASE_PROGRESS_CORRECT；只能从 8 个节点枚举里选，不许自由输入（B4 第 3 条）。
    5. 「送达」Tab 只送**平台自有文书**；文书没有 PDF 时「发起送达」置灰并说明原因
       （工作流 C1 第 1 条与「卡住怎么办」）。
    6. 抽屉内所有写操作成功后 `emit('changed')`，让列表刷新状态与当前节点。
-->
<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    size="720px"
    destroy-on-close
    @open="handleOpen"
  >
    <el-skeleton v-if="loading" :rows="8" animated />
    <el-alert v-else-if="error" type="error" :closable="false" show-icon title="案件详情加载失败">
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadAll">重试</el-button>
      </div>
    </el-alert>

    <template v-else-if="detail">
      <div class="summary">
        <div class="summary__row">
          <span class="mono summary__no">{{ detail.caseNo }}</span>
          <el-tag :type="statusTagType(detail.status)" effect="light" size="small">
            {{ statusText(detail.status) }}
          </el-tag>
        </div>
        <p class="summary__title">{{ textOrDash(detail.title) }}</p>
      </div>

      <el-tabs v-model="activeTab">
        <!-- ── Tab 1：立案材料 ─────────────────────────────────────────── -->
        <el-tab-pane label="立案材料" name="material">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="案件类型">{{ typeText(detail.caseType) }}</el-descriptions-item>
            <el-descriptions-item label="争议金额">
              <span class="mono">¥ {{ formatCents(detail.amountCents) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="承办律师">{{ textOrDash(detail.lawyerName) }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">
              <span class="mono">{{ formatDateTime(detail.createdAt) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="管辖法院">{{ textOrDash(detail.courtName) }}</el-descriptions-item>
            <el-descriptions-item label="法院案号">{{ textOrDash(detail.courtCaseNo) }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="snapshot">
            <h4 class="block-title">第一步 · 案件类型</h4>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="案件类型">{{ typeText(snapshot.caseType) }}</el-descriptions-item>
              <el-descriptions-item label="具体纠纷类型">{{ textOrDash(snapshot.disputeType) }}</el-descriptions-item>
              <el-descriptions-item label="事发地 / 合同履行地">{{ textOrDash(snapshot.location) }}</el-descriptions-item>
            </el-descriptions>

            <h4 class="block-title">第二步 · 当事人</h4>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="原告姓名">{{ textOrDash(snapshot.plaintiff && snapshot.plaintiff.name) }}</el-descriptions-item>
              <el-descriptions-item label="身份证号">{{ maskIdCard(snapshot.plaintiff && snapshot.plaintiff.idCard) }}</el-descriptions-item>
              <el-descriptions-item label="联系电话">{{ maskPhone(snapshot.plaintiff && snapshot.plaintiff.phone) }}</el-descriptions-item>
              <el-descriptions-item label="被告姓名 / 单位名称">{{ textOrDash(snapshot.defendant && snapshot.defendant.name) }}</el-descriptions-item>
              <el-descriptions-item label="被告住址 / 注册地址">{{ textOrDash(snapshot.defendant && snapshot.defendant.address) }}</el-descriptions-item>
            </el-descriptions>

            <h4 class="block-title">第三步 · 案情与诉求</h4>
            <div class="text-block">
              <p class="text-block__label">案情陈述</p>
              <p class="text-block__body">{{ textOrDash(snapshot.statement) }}</p>
            </div>
            <div class="text-block">
              <p class="text-block__label">诉讼请求</p>
              <p class="text-block__body">{{ textOrDash(snapshot.claims) }}</p>
            </div>

            <h4 class="block-title">第四步 · 证据材料</h4>
            <div v-if="evidenceImages.length > 0" class="evidence-grid">
              <el-image
                v-for="(url, index) in evidenceImages"
                :key="index"
                :src="url"
                :preview-src-list="evidenceImages"
                :initial-index="index"
                fit="cover"
                class="evidence-grid__item"
                preview-teleported
              />
            </div>
            <p v-else class="muted">本次提交没有证据图片。</p>

            <h4 class="block-title">审核操作</h4>
            <el-alert
              v-if="detail.status !== 'filed'"
              type="info"
              :closable="false"
              show-icon
              :title="`当前状态为「${statusText(detail.status)}」，只有「待立案审核」的案件可以审核。`"
            />
            <el-form v-else :model="auditForm" label-width="90px">
              <el-form-item label="驳回理由" :required="!auditForm.approved">
                <el-input
                  v-model="auditForm.reason"
                  type="textarea"
                  :rows="3"
                  maxlength="500"
                  show-word-limit
                  :disabled="auditForm.approved"
                  placeholder="驳回时必填，会原样显示给用户（≤500 字）"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="success" :loading="auditing" @click="handleAudit(true)">通过</el-button>
                <el-button type="danger" :loading="auditing" @click="handleAudit(false)">驳回</el-button>
              </el-form-item>
            </el-form>
            <el-alert
              v-if="detail.status === 'rejected' && detail.rejectReason"
              class="block-gap"
              type="warning"
              :closable="false"
              show-icon
              :title="`驳回理由：${detail.rejectReason}`"
            />
          </template>
        </el-tab-pane>

        <!-- ── Tab 2：进度 ────────────────────────────────────────────── -->
        <el-tab-pane label="进度" name="progress">
          <el-timeline v-if="progress.length > 0">
            <el-timeline-item
              v-for="node in progress"
              :key="node.id"
              :timestamp="formatDateTime(node.createdAt)"
              :type="node.nodeCode === 'closed' ? 'success' : 'primary'"
            >
              <div class="timeline-line">
                <span class="timeline-line__title">{{ node.title }}</span>
                <span class="muted">{{ operatorText(node.operatorRole) }}</span>
              </div>
              <p v-if="node.description" class="timeline-line__desc">{{ node.description }}</p>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无进度节点" :image-size="64" />

          <h4 class="block-title">节点纠错</h4>
          <el-alert
            class="block-gap"
            type="info"
            :closable="false"
            show-icon
            title="纠错用于修正律师漏推/推错的节点。必须填写原因，写入审计日志。"
          />
          <el-form :model="correctForm" label-width="90px">
            <el-form-item label="节点" required>
              <el-select v-model="correctForm.nodeCode" placeholder="请选择节点" style="width: 200px">
                <el-option v-for="item in NODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="法院案号">
              <el-input
                v-model="correctForm.courtCaseNo"
                placeholder="选「受理并立案」时填"
                maxlength="64"
                style="width: 260px"
              />
            </el-form-item>
            <el-form-item label="纠错原因" required>
              <el-input
                v-model="correctForm.description"
                type="textarea"
                :rows="3"
                maxlength="2000"
                show-word-limit
                placeholder="必填，写清为什么补这个节点"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="correcting" @click="handleCorrect">写入纠错节点</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ── Tab 3：送达 ────────────────────────────────────────────── -->
        <el-tab-pane label="送达" name="delivery">
          <el-alert
            class="block-gap"
            type="info"
            :closable="false"
            show-icon
            title="本平台只送达平台生成的文书，不做法院文书转达。回执号是平台送达凭证。"
          />
          <div class="table-toolbar">
            <span class="table-toolbar__count">共 {{ deliveries.length }} 份</span>
            <el-tooltip
              :disabled="deliverableDocs.length > 0"
              content="本案尚无文书，请先在文书中心生成"
              placement="top"
            >
              <span>
                <el-button
                  type="primary"
                  :disabled="deliverableDocs.length === 0"
                  @click="openSendDialog"
                >
                  发起送达
                </el-button>
              </span>
            </el-tooltip>
          </div>

          <el-table :data="deliveries" row-key="id" stripe size="small" style="width: 100%">
            <template #empty><el-empty description="本案还没有送达记录" :image-size="64" /></template>
            <el-table-column label="回执号" min-width="160">
              <template #default="{ row }"><span class="mono">{{ row.receiptNo }}</span></template>
            </el-table-column>
            <el-table-column label="文书" min-width="160">
              <template #default="{ row }">{{ textOrDash(row.documentTitle) }}</template>
            </el-table-column>
            <el-table-column label="方式" min-width="110">
              <template #default="{ row }">{{ methodText(row.method) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'signed' ? 'success' : 'warning'" effect="light" size="small">
                  {{ row.status === 'signed' ? '已签收' : '待签收' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="送达时间" min-width="130">
              <template #default="{ row }">
                <span class="mono muted">{{ formatDateTime(row.sentAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="签收时间" min-width="130">
              <template #default="{ row }">
                <span class="mono muted">{{ row.signedAt ? formatDateTime(row.signedAt) : '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 发起送达弹窗（设计稿没画，本段自定） -->
    <el-dialog v-model="sendVisible" title="发起送达" width="520px" append-to-body>
      <el-form :model="sendForm" label-width="90px">
        <el-form-item label="文书" required>
          <el-select v-model="sendForm.documentId" placeholder="选一份已生成 PDF 的文书" style="width: 100%">
            <el-option
              v-for="doc in deliverableDocs"
              :key="doc.id"
              :label="doc.title || `文书 #${doc.id}`"
              :value="String(doc.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="送达方式" required>
          <el-radio-group v-model="sendForm.method">
            <el-radio value="platform">平台内电子送达</el-radio>
            <el-radio value="sms_pickup">短信通知自行查看</el-radio>
            <el-radio value="mail">邮寄纸质</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="handleSend">确认送达</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
/**
 * 案件详情抽屉脚本。
 *
 * 职责：加载详情/进度/送达三块数据、提交审核、提交纠错、发起送达。
 * 副作用：任一写操作成功后 `emit('changed')`；`emit('update:modelValue')` 控制显隐。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  auditCase,
  correctCaseProgress,
  getCaseDetail,
  listCaseDeliveries,
  listCaseDocuments,
  listCaseProgress,
  sendCaseDelivery
} from '@/api/biz/caseflow/case'
import { formatCents, formatDateTime, textOrDash } from '@/views/biz/shared/format'

const props = defineProps({
  /** 抽屉是否可见（v-model）。 */
  modelValue: { type: Boolean, default: false },
  /** 案件主键（字符串）。 */
  caseId: { type: String, default: '' },
  /** 打开时定位的 Tab。 */
  initialTab: { type: String, default: 'material' }
})

const emit = defineEmits(['update:modelValue', 'changed'])

/** 状态中英文映射。 */
const STATUS_TEXT = {
  filed: '待立案审核',
  accepted: '立案中',
  in_progress: '审理中',
  closed: '已结案',
  rejected: '已驳回'
}

const STATUS_TAG = {
  filed: 'warning',
  accepted: 'primary',
  in_progress: 'success',
  closed: 'info',
  rejected: 'danger'
}

/** 8 个进度节点（与后端 case_progress.node_code 一致，不许自由输入）。 */
const NODE_OPTIONS = [
  { value: 'submitted', label: '材料已提交' },
  { value: 'review', label: '法院审查' },
  { value: 'accepted', label: '受理并立案' },
  { value: 'supplement', label: '材料补正' },
  { value: 'hearing', label: '开庭审理' },
  { value: 'judgment', label: '一审判决' },
  { value: 'effective', label: '判决生效' },
  { value: 'closed', label: '结案' }
]

/** 案件类型 → 中文。 */
const TYPE_TEXT = {
  civil: '民事',
  criminal: '刑事',
  administrative: '行政',
  labor: '劳动仲裁',
  family: '家事'
}

/** 送达方式 → 中文（本平台三种，不含法院侧方式）。 */
const METHOD_TEXT = {
  platform: '平台内电子送达',
  sms_pickup: '短信通知自行查看',
  mail: '邮寄纸质'
}

const loading = ref(false)
const error = ref('')
const detail = ref(null)
const progress = ref([])
const deliveries = ref([])
const documents = ref([])
const activeTab = ref('material')

const auditForm = reactive({ approved: true, reason: '' })
const auditing = ref(false)

const correctForm = reactive({ nodeCode: '', courtCaseNo: '', description: '' })
const correcting = ref(false)

const sendVisible = ref(false)
const sending = ref(false)
const sendForm = reactive({ documentId: '', method: 'platform' })

/** 抽屉双向绑定。 */
const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})

/** 抽屉标题（带单号，方便用户对照）。 */
const drawerTitle = computed(() => (detail.value ? `案件详情 · ${detail.value.caseNo}` : '案件详情'))

/** 四步表单快照（解析失败返回 null，不让一条脏数据把整个抽屉打空）。 */
const snapshot = computed(() => {
  if (!detail.value || !detail.value.formSnapshot) {
    return null
  }
  try {
    return JSON.parse(detail.value.formSnapshot)
  } catch (e) {
    return null
  }
})

/** 证据图片的短时签名地址（服务端现签）。 */
const evidenceImages = computed(() => (detail.value && detail.value.evidenceImageUrls) || [])

/** 可送达的文书：必须有 PDF（hasPdf），否则不许发起送达。 */
const deliverableDocs = computed(() => documents.value.filter(doc => doc.hasPdf === true))

/**
 * 状态/类型/方式文案。
 *
 * @param {string} status 状态 code
 * @returns {string} 中文
 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/**
 * 状态标签颜色。
 *
 * @param {string} status 状态 code
 * @returns {string} tag type
 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/**
 * 类型文案。
 *
 * @param {string} type 类型 code
 * @returns {string} 中文
 */
function typeText(type) {
  return TYPE_TEXT[type] || type || '—'
}

/**
 * 送达方式文案。
 *
 * @param {string} method 方式 code
 * @returns {string} 中文
 */
function methodText(method) {
  return METHOD_TEXT[method] || method || '—'
}

/**
 * 操作人角色文案。
 *
 * @param {string} role user / lawyer / admin / system
 * @returns {string} 中文
 */
function operatorText(role) {
  if (role === 'user') {
    return '操作人：用户本人'
  }
  if (role === 'lawyer') {
    return '操作人：承办律师'
  }
  if (role === 'admin') {
    return '操作人：平台法务'
  }
  return '操作人：系统'
}

/**
 * 身份证脱敏（留前 6 后 4）。
 *
 * 审核看的是材料是否齐备，不需要完整证件号；完整号码属 L4 敏感字段，
 * 不该出现在后台页面的常驻 DOM 里（《审计与日志规范》§4.3 的同一套脱敏口径）。
 *
 * @param {string} value 身份证号
 * @returns {string} 脱敏结果
 */
function maskIdCard(value) {
  if (!value || value.length < 10) {
    return '—'
  }
  return `${value.slice(0, 6)}********${value.slice(-4)}`
}

/**
 * 手机号脱敏（留前 3 后 4）。
 *
 * @param {string} value 手机号
 * @returns {string} 脱敏结果
 */
function maskPhone(value) {
  if (!value || value.length < 7) {
    return '—'
  }
  return `${value.slice(0, 3)}****${value.slice(-4)}`
}

/** 拉抽屉需要的三块数据（详情必拉，进度与送达按需一次拉全，量都很小）。 */
async function loadAll() {
  if (!props.caseId) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [detailRes, progressRes, deliveryRes, docRes] = await Promise.all([
      getCaseDetail(props.caseId),
      listCaseProgress(props.caseId),
      listCaseDeliveries(props.caseId),
      listCaseDocuments(props.caseId)
    ])
    detail.value = detailRes.data
    progress.value = progressRes.data || []
    deliveries.value = deliveryRes.data || []
    documents.value = docRes.data || []
    auditForm.approved = true
    auditForm.reason = ''
    correctForm.nodeCode = ''
    correctForm.courtCaseNo = ''
    correctForm.description = ''
  } catch (e) {
    error.value = e && e.message ? e.message : '网络异常'
    detail.value = null
  } finally {
    loading.value = false
  }
}

/** 抽屉打开时定位 Tab 并拉数据。 */
function handleOpen() {
  activeTab.value = props.initialTab || 'material'
  loadAll()
}

// caseId 变化（同一个抽屉换案件）时重新拉；首次由 @open 触发，避免重复请求
watch(
  () => props.caseId,
  (next, prev) => {
    if (props.modelValue && next && next !== prev) {
      loadAll()
    }
  }
)

/**
 * 审核。
 *
 * @param {boolean} approve true = 通过，false = 驳回
 */
async function handleAudit(approve) {
  if (!approve && !auditForm.reason.trim()) {
    ElMessage.warning('驳回必须填写理由')
    return
  }
  try {
    await ElMessageBox.confirm(
      approve ? '确认通过该立案材料？通过后用户端状态变为「立案中」。' : '确认驳回？用户会看到驳回理由并可重新提交。',
      approve ? '通过立案审核' : '驳回立案审核',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  auditing.value = true
  try {
    await auditCase(props.caseId, {
      action: approve ? 'approve' : 'reject',
      reason: approve ? undefined : auditForm.reason.trim()
    })
    ElMessage.success(approve ? '已通过' : '已驳回')
    await loadAll()
    emit('changed')
  } finally {
    auditing.value = false
  }
}

/** 进度纠错（必须填原因）。 */
async function handleCorrect() {
  if (!correctForm.nodeCode) {
    ElMessage.warning('请选择节点')
    return
  }
  if (!correctForm.description.trim()) {
    ElMessage.warning('纠错必须填写原因')
    return
  }
  if (correctForm.nodeCode === 'accepted' && !correctForm.courtCaseNo.trim()) {
    ElMessage.warning('「受理并立案」必须填写法院案号')
    return
  }
  correcting.value = true
  try {
    await correctCaseProgress(props.caseId, {
      nodeCode: correctForm.nodeCode,
      description: correctForm.description.trim(),
      courtCaseNo: correctForm.courtCaseNo.trim() || undefined
    })
    ElMessage.success('已写入纠错节点')
    await loadAll()
    emit('changed')
  } finally {
    correcting.value = false
  }
}

/** 打开发起送达弹窗（默认选第一份可送达文书）。 */
function openSendDialog() {
  if (deliverableDocs.value.length === 0) {
    return
  }
  sendForm.documentId = String(deliverableDocs.value[0].id)
  sendForm.method = 'platform'
  sendVisible.value = true
}

/** 发起送达。 */
async function handleSend() {
  if (!sendForm.documentId) {
    ElMessage.warning('请选择文书')
    return
  }
  sending.value = true
  try {
    await sendCaseDelivery(props.caseId, {
      documentId: sendForm.documentId,
      method: sendForm.method
    })
    ElMessage.success('已发起送达')
    sendVisible.value = false
    await loadAll()
    emit('changed')
  } finally {
    sending.value = false
  }
}
</script>

<style scoped>
.summary {
  margin-bottom: 12px;
}
.summary__row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.summary__no {
  font-size: 13px;
  color: #1a1a1a;
}
.summary__title {
  margin: 4px 0 0;
  font-size: 15px;
  color: #1a1a1a;
}
.block-title {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #1a1a1a;
}
.text-block {
  margin-bottom: 8px;
}
.text-block__label {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
}
.text-block__body {
  margin: 4px 0 0;
  font-size: 13px;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}
.evidence-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.evidence-grid__item {
  width: 96px;
  height: 96px;
  border-radius: 6px;
  border: 1px solid #eef0f3;
}
.timeline-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.timeline-line__title {
  font-size: 14px;
  color: #1a1a1a;
}
.timeline-line__desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: #6b7280;
  white-space: pre-wrap;
  word-break: break-word;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: #6b7280;
  font-size: 12px;
}
</style>
