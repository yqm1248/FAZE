<!--
  职责：PC A-28 AI 对话检索 —— 按用户 + 时间检索 AI 会话，查看回答原文与生成时冻结的 refs 法条快照（第 3 段 H2）。
  调用方：RuoYi 动态路由（sys_menu 菜单：AI 对话检索，component = biz/ai/ai-retrieval/index）。
  数据来源：GET /api/v1/admin/ai-conversations                     会话列表（含已软删，RuoYi 口径 rows/total）
            GET /api/v1/admin/ai-conversations/{id}/messages       全量消息 + refs 快照 + currentLawItems
            GET /api/v1/admin/ai-conversations/{id}/export         导出单条 JSON（服务端写审计）
  注意：
    1. **只读页面**：不改写任何原文、没有保存按钮；复核接口（POST /{id}/review）按 §零.3 第 9 项不做。
    2. **设计稿删除项（本段硬要求，别加回来）**：
       - 页头「导出检索结果」：契约只有单条会话导出，列表级导出会让前端绕开审计，不做；
       - 查询项「法条引用」：`ai_message.ref_check_status` 已按 §零.3 第 9 项删除，没有数据源；
       - 查询项「复核状态」：`ai_conversation` 没有复核字段；
       - 页脚「标记已复核」按钮：本段不做复核，页脚只剩「导出本条记录」；
       - refs 区的「校验状态」标签（已校验 / 条号不存在）：不做复核校验状态展示。
       另外左栏的「全部/命中/未命中」胶囊与「N 条待复核」标签也一并去掉（没有对应查询参数/字段）。
    3. **「快照 vs 当前」的数据源是 messages 接口的 `currentLawItems`**（后端按 lawItemId 给当前
       law_item 的 status/isEnabled/effectiveDate/expireDate/updatedAt），页面**不逐条回查**
       /law-items/{id}（那是 N+1）。快照里的 status/content 永远不变，只有当前值可能变。
    4. 已软删的会话仍能检索到（合规追溯），列表行用 deletedAt 标「用户已删除」。
    5. 导出是文件流：业务 axios 拦截器只回 Blob、拿不到响应头，下载文件名按 E3 规范在前端
       用同一格式拼（AI-CONV-<id>-<yyyyMMddHHmm>.json，Asia/Shanghai）；截断标记从文件
       JSON 的 `truncated` 字段读。导出动作的审计由服务端写，前端不管。
    6. 四态齐全：列表/详情的加载中、空、错误（可重试）、正常。
-->
<template>
  <div class="app-container ai-page">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">AI 对话检索</h1>
        <p class="page-head__desc">
          按用户与时间检索 AI 回答原文及其引用的法条快照。原文与快照只读，用于合规追溯与投诉核查。
        </p>
      </div>
    </div>

    <!-- ==================== 列表错误态（常驻 + 可重试） ==================== -->
    <el-alert
      v-if="listError"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="AI 会话列表加载失败"
    >
      <div class="error-body">
        <span>{{ listError }}</span>
        <el-button type="primary" link @click="loadConversations">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap ai-query" shadow="never">
      <el-form :model="query" inline label-width="84px" @submit.prevent>
        <el-form-item label="用户 ID">
          <el-input
            v-model="query.userId"
            placeholder="用户 ID（数字）"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="query.timeRange" style="width: 130px" @change="handleTimeRangeChange">
            <el-option v-for="item in TIME_RANGE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-date-picker
            v-if="query.timeRange === 'custom'"
            v-model="customRange"
            class="custom-range"
            type="daterange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="defaultRangeTime"
            range-separator="至"
            start-placeholder="起始"
            end-placeholder="截止"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="listLoading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ==================== refs 快照说明（设计稿保留） ==================== -->
    <el-alert class="block-gap" type="warning" :closable="false" show-icon title="refs 快照在回答生成时冻结，法条后续修订不会改写历史记录。">
      <template #default>
        所以「回答里引的条文」与「快照里的条文内容」不一致时，说明法条在回答之后被改过，需要人工核对。
      </template>
    </el-alert>

    <!-- ==================== 左：会话队列 / 右：回答原文 + 快照 ==================== -->
    <el-row :gutter="16" class="ai-split">
      <el-col :xs="24" :md="9" :lg="8">
        <el-card class="panel ai-list" shadow="never">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">对话记录</span>
              <span class="panel-count">共 {{ total }} 条</span>
            </div>
          </template>

          <div v-loading="listLoading" class="rec-list">
            <el-empty
              v-if="!rows.length && !listError"
              :description="listError ? '会话加载失败，请点上方「重试」' : '没有符合条件的 AI 会话'"
              :image-size="60"
            />
            <div
              v-for="row in rows"
              :key="row.id"
              class="rec"
              :class="{ 'rec--active': String(row.id) === activeId }"
              @click="selectConversation(row)"
            >
              <div class="rec__top">
                <span class="rec__title">{{ row.userName || `用户 ${row.userId}` }}</span>
                <el-tag
                  size="small"
                  effect="plain"
                  :type="refCountOf(row) === 0 ? 'danger' : 'info'"
                >
                  {{ refCountOf(row) }} 条引用
                </el-tag>
                <el-tag v-if="row.deletedAt" size="small" type="danger" effect="plain">用户已删除</el-tag>
              </div>
              <div class="rec__text">{{ row.questionSummary || row.title || '—' }}</div>
              <div class="rec__meta">U-{{ row.userId }} · {{ formatDateTime(row.createdAt) }}</div>
            </div>
          </div>

          <template #footer>
            <el-pagination
              class="list-pagination"
              small
              layout="prev, pager, next"
              :total="total"
              :page-size="query.pageSize"
              :current-page="query.pageNum"
              @current-change="handlePageChange"
            />
          </template>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="15" :lg="16">
        <el-card class="panel ai-detail" shadow="never">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">回答原文与法条快照</span>
              <el-tag v-if="activeConversation" size="small" effect="plain">{{ conversationCode }}</el-tag>
            </div>
          </template>

          <!-- 未选中会话 -->
          <el-empty
            v-if="!activeId"
            description="从左侧选择一条会话，查看回答原文与 refs 快照"
            :image-size="80"
          />

          <div v-else v-loading="detailLoading">
            <el-alert
              v-if="detailError"
              type="error"
              :closable="false"
              show-icon
              title="会话内容加载失败"
            >
              <div class="error-body">
                <span>{{ detailError }}</span>
                <el-button type="primary" link @click="loadMessages(activeId)">重试</el-button>
              </div>
            </el-alert>

            <template v-else-if="activeConversation">
              <!-- 会话头：用户 + 会话号 + 生成元信息 -->
              <div class="ai-meta">
                <span class="avatar">{{ avatarText }}</span>
                <div class="ai-meta__main">
                  <div class="ai-meta__title">
                    {{ activeConversation.userName || `用户 ${activeConversation.userId}` }}（U-{{ activeConversation.userId }}）
                  </div>
                  <div class="ai-meta__sub">
                    会话 #{{ activeConversation.id }}<template v-if="conversationModel"> · 模型 {{ conversationModel }}</template>
                    · 创建 {{ formatDateTime(activeConversation.createdAt) }}
                  </div>
                </div>
                <div class="ai-meta__tags">
                  <el-tag size="small" effect="plain">AI 生成</el-tag>
                  <el-tag v-if="activeConversation.deletedAt" size="small" type="danger" effect="plain">
                    用户已删除
                  </el-tag>
                </div>
              </div>

              <!-- 问答气泡 + 每条 assistant 消息的 refs 快照 -->
              <div class="ai-chat">
                <template v-for="(msg, index) in messages" :key="msg.id || index">
                  <div class="ai-msg" :class="{ 'ai-msg--user': msg.role === 'user' }">
                    <div class="ai-msg__body">
                      <div v-if="msg.role === 'assistant'" class="ai-msg__head">
                        <el-tag size="small" effect="plain">AI 生成</el-tag>
                        <span class="ai-msg__time">{{ formatTime(msg.createdAt) }}</span>
                        <span v-if="durationText(msg)" class="ai-msg__time">· 用时 {{ durationText(msg) }}</span>
                        <span v-if="msg.model" class="ai-msg__time">· {{ msg.model }}</span>
                      </div>
                      <div class="ai-msg__content">{{ msg.content || '—' }}</div>
                      <div v-if="msg.role === 'user'" class="ai-msg__time user-time">{{ formatTime(msg.createdAt) }}</div>
                      <div v-if="msg.role === 'assistant' && msg.contentId" class="ai-msg__content-id">
                        内容编号 {{ msg.contentId }}
                      </div>
                    </div>
                  </div>

                  <!-- refs 快照：生成时冻结；法条后来被修正/失效时标一行差异说明 -->
                  <div v-if="msg.role === 'assistant' && refsOf(msg).length" class="ai-refs">
                    <div class="ai-refs__head">
                      <span>refs 快照</span>
                      <span class="ai-refs__note">生成时冻结 · {{ refsOf(msg).length }} 条</span>
                    </div>
                    <div v-for="(ref, refIndex) in refsOf(msg)" :key="ref.lawItemId || refIndex" class="ai-ref">
                      <div class="ai-ref__main">
                        <div class="ai-ref__title">
                          {{ textOrDash(ref.lawName) }}<span class="ai-ref__no">{{ ref.articleNo }}</span>
                        </div>
                        <div class="ai-ref__text">{{ ref.content || '—' }}</div>
                        <div v-if="snapshotNotice(ref)" class="ai-ref__notice">{{ snapshotNotice(ref) }}</div>
                      </div>
                    </div>
                  </div>
                </template>

                <el-empty v-if="!messages.length" description="该会话没有消息" :image-size="60" />
              </div>
            </template>
          </div>

          <template #footer>
            <div class="detail-foot">
              <el-button
                v-hasPermi="['biz:ai:export']"
                type="primary"
                :disabled="!activeId"
                :loading="exporting"
                @click="handleExport"
              >
                导出本条记录
              </el-button>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
/**
 * A-28 AI 对话检索页脚本。
 *
 * 副作用：挂载拉会话第一页；选中会话后拉该会话消息；导出成功后触发浏览器下载。
 * 本页**没有任何写接口调用**。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportAiConversation, getAiConversationMessages, listAiConversations } from '@/api/biz/ai/ai-retrieval'
import { formatDate, formatDateTime, textOrDash, describeRequestError } from '@/views/biz/shared/format'

/** 时间范围预设（与设计稿一致：近 7 / 30 / 90 天 / 自定义）。 */
const TIME_RANGE_OPTIONS = [
  { value: '7', label: '近 7 天' },
  { value: '30', label: '近 30 天' },
  { value: '90', label: '近 90 天' },
  { value: 'custom', label: '自定义' }
]

const rows = ref([])
const total = ref(0)
const listLoading = ref(false)
const listError = ref('')

const query = reactive({
  userId: '',
  timeRange: '7',
  pageNum: 1,
  pageSize: 10
})

/** 自定义时间范围（仅 timeRange === 'custom' 时使用）。 */
const customRange = ref([])

/** 日期区间默认时刻：00:00:00 ~ 23:59:59（与用户管理页一致）。 */
const defaultRangeTime = [new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]

const activeId = ref('')
const activeConversation = ref(null)
const messages = ref([])
const currentLawItems = ref([])
const detailLoading = ref(false)
const detailError = ref('')
const exporting = ref(false)

/** lawItemId → 当前法条行（Long 已被后端序列化为字符串，统一用 String 做键）。 */
const currentLawMap = computed(() => {
  const map = new Map()
  currentLawItems.value.forEach((item) => {
    if (item && item.lawItemId !== null && item.lawItemId !== undefined) {
      map.set(String(item.lawItemId), item)
    }
  })
  return map
})

/** 会话编号（面板头标签）：优先用首条 assistant 消息的内容编号，没有就回退会话 id。 */
const conversationCode = computed(() => {
  if (!activeConversation.value) {
    return ''
  }
  const assistant = messages.value.find((msg) => msg.role === 'assistant' && msg.contentId)
  return assistant ? assistant.contentId : `会话 #${activeConversation.value.id}`
})

/** 会话模型：取第一条带 model 的 assistant 消息（user 消息没有 model）。 */
const conversationModel = computed(() => {
  const withModel = messages.value.find((msg) => msg.role === 'assistant' && msg.model)
  return withModel ? withModel.model : ''
})

/** 头像文字：取用户昵称首字。 */
const avatarText = computed(() => {
  const name = (activeConversation.value && activeConversation.value.userName) || ''
  return name ? name.slice(0, 1) : '用'
})

/** 左侧记录条的「引用条数」（后端聚合的 refCount）。 */
function refCountOf(row) {
  const count = Number(row && row.refCount)
  return Number.isFinite(count) ? count : 0
}

/** 时间串 → `HH:mm`（消息气泡里的时间）。 */
function formatTime(value) {
  const text = formatDateTime(value)
  const matched = /(\d{2}:\d{2})$/.exec(text)
  return matched ? matched[1] : text
}

/** 服务端无时区时间串 → 毫秒时间戳（用于算「用时」，解析规则与 shared/format.js 一致）。 */
function parseServerTime(value) {
  if (!value) {
    return NaN
  }
  const text = String(value).trim()
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(text)
  const iso = hasZone ? text : `${text.replace(' ', 'T')}Z`
  return new Date(iso).getTime()
}

/**
 * 回答用时（秒）。
 *
 * 口径：assistant 行的 updatedAt（流式结束落全量）减 createdAt（行创建），是**写入窗口**，
 * 不等于首字节延迟（TTFT 没有落库字段，不编造）。差值无效或过大时返回空串不展示。
 *
 * @param {object} msg 消息
 * @returns {string} 形如 `3.2 s`；无有效差值回空串
 */
function durationText(msg) {
  if (!msg || msg.role !== 'assistant' || !msg.createdAt || !msg.updatedAt) {
    return ''
  }
  const diff = parseServerTime(msg.updatedAt) - parseServerTime(msg.createdAt)
  if (!Number.isFinite(diff) || diff <= 0 || diff > 600000) {
    return ''
  }
  return `${(diff / 1000).toFixed(1)} s`
}

/**
 * 取消息的 refs 数组（后端已解析；对 JSON 字符串做兜底，不因格式不认识就白屏）。
 *
 * @param {object} msg 消息
 * @returns {Array<object>} 快照数组
 */
function refsOf(msg) {
  if (!msg || !msg.refs) {
    return []
  }
  let list = msg.refs
  if (typeof list === 'string') {
    try {
      list = JSON.parse(list)
    } catch {
      return []
    }
  }
  if (!Array.isArray(list)) {
    return []
  }
  return list.filter((item) => item && typeof item === 'object')
}

/**
 * 快照 vs 当前：法条后来被修正/失效时给出提示行。
 *
 * 数据源是 messages 接口的 `currentLawItems`（不逐条回查），比较：
 * - 当前行不存在 → 已查不到；
 * - isEnabled=0 或 status=repealed → 失效；
 * - 当前 status 与快照 status 不同（如 active → amended）→ 更新。
 * 日期优先取当前行的 expireDate（修正时=修正生效日），没有就取 updatedAt。
 *
 * @param {object} ref refs 快照元素
 * @returns {string} 提示文案；无变化回空串
 */
function snapshotNotice(ref) {
  if (!ref || ref.lawItemId === null || ref.lawItemId === undefined) {
    return ''
  }
  const current = currentLawMap.value.get(String(ref.lawItemId))
  if (!current) {
    return '该条文的当前版本已查不到，以下为引用当时快照。'
  }
  const dateText = current.expireDate ? formatDate(current.expireDate) : formatDate(current.updatedAt)
  // 先看效力状态：amended（旧行被新版本替代）要说「更新」，不能因为旧行 is_enabled=0 就说「失效」
  if (current.status !== ref.status) {
    if (current.status === 'repealed') {
      return `该条文已于 ${dateText} 失效，以下为引用当时快照。`
    }
    return `该条文已于 ${dateText} 更新，以下为引用当时快照。`
  }
  const disabled = Number(current.isEnabled) === 0 || current.isEnabled === false
  if (disabled) {
    return `该条文已于 ${dateText} 失效，以下为引用当时快照。`
  }
  return ''
}

/** 本地「近 N 天」起止（UTC 墙钟串，与用户管理页的时间参数口径一致）。 */
function buildPresetRange(days) {
  const pad = (n) => String(n).padStart(2, '0')
  const toText = (date) =>
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  const end = new Date()
  const begin = new Date(end.getTime() - days * 24 * 60 * 60 * 1000)
  return [toText(begin), toText(end)]
}

/** 组装列表查询参数。 */
function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.userId && query.userId.trim()) {
    params.userId = query.userId.trim()
  }
  if (query.timeRange === 'custom') {
    if (Array.isArray(customRange.value) && customRange.value.length === 2) {
      params.beginTime = customRange.value[0]
      params.endTime = customRange.value[1]
    }
  } else {
    const range = buildPresetRange(Number(query.timeRange) || 7)
    params.beginTime = range[0]
    params.endTime = range[1]
  }
  return params
}

/** 拉会话列表。 */
async function loadConversations() {
  listLoading.value = true
  listError.value = ''
  try {
    const data = await listAiConversations(buildParams())
    rows.value = data.rows || []
    total.value = data.total || 0
    // 当前选中项不在新列表里（翻页/筛选后）→ 清空右侧，避免显示与左栏不对应的旧详情
    if (activeId.value && !rows.value.some((row) => String(row.id) === activeId.value)) {
      clearDetail()
    }
    // 首次进入自动选中第一条，右侧不空着（设计稿就是左选中 + 右详情）
    if (!activeId.value && rows.value.length > 0) {
      selectConversation(rows.value[0])
    }
  } catch (e) {
    listError.value = describeRequestError(e, 'AI 会话列表加载失败')
    rows.value = []
    total.value = 0
    clearDetail()
  } finally {
    listLoading.value = false
  }
}

/** 查询（回第一页）。 */
function handleSearch() {
  if (query.timeRange === 'custom' && (!Array.isArray(customRange.value) || customRange.value.length !== 2)) {
    ElMessage.warning('选择「自定义」时请先选好起止时间')
    return
  }
  query.pageNum = 1
  clearDetail()
  loadConversations()
}

/** 时间范围切换：切回预设时清掉自定义区间。 */
function handleTimeRangeChange(value) {
  if (value !== 'custom') {
    customRange.value = []
  }
}

/** 重置筛选。 */
function handleReset() {
  query.userId = ''
  query.timeRange = '7'
  customRange.value = []
  query.pageNum = 1
  clearDetail()
  loadConversations()
}

/** 翻页。 */
function handlePageChange(page) {
  query.pageNum = page
  clearDetail()
  loadConversations()
}

/** 选中会话。 */
function selectConversation(row) {
  if (!row) {
    return
  }
  activeId.value = String(row.id)
  activeConversation.value = row
  loadMessages(row.id)
}

/** 清空右侧详情。 */
function clearDetail() {
  activeId.value = ''
  activeConversation.value = null
  messages.value = []
  currentLawItems.value = []
  detailError.value = ''
}

/** 拉会话消息 + refs 快照 + 当前法条状态。 */
async function loadMessages(conversationId) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await getAiConversationMessages(conversationId)
    const data = (res && res.data) || {}
    if (data.conversation) {
      activeConversation.value = data.conversation
    }
    messages.value = Array.isArray(data.messages) ? data.messages : []
    currentLawItems.value = Array.isArray(data.currentLawItems) ? data.currentLawItems : []
  } catch (e) {
    detailError.value = describeRequestError(e, '会话内容加载失败')
    messages.value = []
    currentLawItems.value = []
  } finally {
    detailLoading.value = false
  }
}

/** 导出时间戳：Asia/Shanghai 的 `yyyyMMddHHmm`（与 E3 的文件名格式一致）。 */
function shanghaiStamp() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).formatToParts(new Date())
  const get = (type) => (parts.find((part) => part.type === type) || {}).value || ''
  return `${get('year')}${get('month')}${get('day')}${get('hour')}${get('minute')}`
}

/** 触发浏览器下载。 */
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

/** 导出本条会话记录（服务端写 operation_log: AI_CONVERSATION_EXPORT）。 */
async function handleExport() {
  if (!activeId.value) {
    return
  }
  exporting.value = true
  try {
    const blob = await exportAiConversation(activeId.value)
    // 响应头拿不到（拦截器只回 body），截断标记从文件 JSON 里读
    let truncated = false
    try {
      const text = await blob.text()
      const parsed = JSON.parse(text)
      truncated = Boolean(parsed && parsed.truncated)
    } catch {
      // 不是 JSON（如后端异常返回）不阻断下载，交给服务端文件内容判断
    }
    downloadBlob(blob, `AI-CONV-${activeId.value}-${shanghaiStamp()}.json`)
    if (truncated) {
      ElMessage.warning('该会话消息超过 200 条，导出内容已截断（文件内 truncated=true）')
    } else {
      ElMessage.success('已导出本条记录')
    }
  } catch (e) {
    ElMessage.error(describeRequestError(e, '导出失败'))
  } finally {
    exporting.value = false
  }
}

onMounted(loadConversations)
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
  color: var(--el-text-color-primary);
}
.page-head__desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.block-gap {
  margin-bottom: 16px;
}
.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-query :deep(.el-form-item) {
  margin-bottom: 0;
}
.custom-range {
  margin-left: 8px;
}
.ai-page {
  /* 设计稿是「整页定高、左右两栏各自滚动、页脚常驻」：
     高度 = 视口 - 顶栏 50 - 标签栏 34 - app-container 上下内边距 40；
     页头/查询栏/提示条 flex 0，分栏 flex 1（错误提示条出现时也不会把页脚顶出视口）。 */
  display: flex;
  flex-direction: column;
  height: calc(100vh - 124px);
  min-height: 560px;
  box-sizing: border-box;
}
.ai-page .page-head,
.ai-page .ai-query,
.ai-page .block-gap {
  flex: 0 0 auto;
}
.ai-split {
  display: flex;
  flex-wrap: nowrap;
  flex: 1 1 auto;
  min-height: 0;
  margin-bottom: 0;
  align-items: stretch;
}
.ai-split .el-col {
  display: flex;
}
.panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 0;
}
.panel :deep(.el-card__body) {
  flex: 1 1 auto;
  min-height: 0;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.panel-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.panel-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ai-list :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.ai-list :deep(.el-card__footer) {
  padding: 8px 16px;
  flex: 0 0 auto;
}
.rec-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}
.rec {
  display: block;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.rec:hover {
  background: var(--el-fill-color-light);
}
.rec--active {
  background: var(--el-color-primary-light-9);
  border-inline-start: 3px solid var(--el-color-primary);
}
.rec__top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.rec__title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.rec__text {
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.rec__meta {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.list-pagination {
  justify-content: flex-end;
}
.ai-detail :deep(.el-card__body) {
  overflow-y: auto;
}
.detail-foot {
  display: flex;
  justify-content: flex-end;
}
.ai-meta {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
  flex: 0 0 auto;
}
.ai-meta__main {
  flex: 1 1 auto;
  min-width: 0;
}
.ai-meta__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.ai-meta__sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.ai-meta__tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}
.ai-chat {
  margin-top: 16px;
}
.ai-msg {
  display: flex;
  margin-bottom: 12px;
}
.ai-msg--user {
  justify-content: flex-end;
}
.ai-msg__body {
  max-width: 78%;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  line-height: 1.75;
  color: var(--el-text-color-regular);
}
.ai-msg--user .ai-msg__body {
  background: var(--el-color-primary-light-9);
  border-color: transparent;
}
.ai-msg__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ai-msg__time {
  font-variant-numeric: tabular-nums;
}
.user-time {
  margin-top: 6px;
  text-align: end;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ai-msg__content {
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-msg__content-id {
  margin-top: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.ai-refs {
  margin: 4px 0 16px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.ai-refs__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.ai-refs__note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
.ai-ref {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}
.ai-ref:first-of-type {
  border-top: 0;
  padding-top: 0;
}
.ai-ref__main {
  flex: 1 1 auto;
  min-width: 0;
}
.ai-ref__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.ai-ref__no {
  margin-inline-start: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--el-color-primary);
}
.ai-ref__text {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-ref__notice {
  margin-top: 6px;
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
}
@media (max-width: 992px) {
  .ai-list {
    margin-bottom: 16px;
  }
}
</style>
