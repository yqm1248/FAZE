<!--
  职责：PC 后台 A-22 工单详情——正文 / 处理记录 / 回复框 / 属性四块。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/TicketDetail）。
  数据来源：src/api/biz/ops.js（第 7 段 C4 的接口）。
  注意：
    1. 提交人手机号是**服务端脱敏后的值**（`138****8888`），页面不做二次处理。
    2. 回复会写通知（站内信），工单状态自动转 `replied`；**关闭后不能再回复**（服务端 410012）。
    3. 处理记录的时间线来自服务端 `timeline`（operation_log），不是前端拼的。
    4. 超时提示由服务端给 `overtimeText`（如「已超时 26 小时」），页面只显示。
-->
<template>
  <div v-loading="loading" class="app-container">
    <el-page-header title="返回" content="工单详情" @back="goBack" />

    <el-row v-if="detail" :gutter="16" style="margin-top: 16px">
      <!-- 左栏：正文 / 记录 / 回复 -->
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>{{ detail.ticket.ticketNo }} · {{ detail.ticket.title }}</span>
              <el-tag :type="detail.ticket.status === 'closed' ? 'info' : 'primary'" size="small">
                {{ detail.ticket.statusLabel }}
              </el-tag>
            </div>
          </template>

          <el-alert
            v-if="detail.overtimeText"
            :title="detail.overtimeText"
            type="error"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />

          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="关联记录">{{ detail.relatedDesc || '—' }}</el-descriptions-item>
            <el-descriptions-item label="提示">{{ detail.relatedNote || '—' }}</el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">处理记录</el-divider>
          <el-empty v-if="!detail.timeline || detail.timeline.length === 0" description="暂无处理记录" :image-size="60" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="(item, i) in detail.timeline"
              :key="i"
              :timestamp="formatTime(item.createdAt)"
              placement="top"
            >
              <span class="timeline-action">{{ item.action }}</span>
              <span class="muted"> · 操作人 {{ item.operatorId || '—' }}</span>
            </el-timeline-item>
          </el-timeline>

          <el-divider content-position="left">回复用户</el-divider>
          <el-input
            v-model="replyContent"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="写给用户的回复，通过站内信与 App 推送送达"
            :disabled="detail.ticket.status === 'closed'"
          />
          <div class="actions">
            <el-button
              type="primary"
              :loading="replying"
              :disabled="detail.ticket.status === 'closed'"
              @click="handleReply"
            >
              发送回复
            </el-button>
            <el-button
              type="danger"
              :disabled="detail.ticket.status === 'closed'"
              @click="handleClose"
            >
              关闭工单
            </el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 右栏：属性 -->
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><span>工单属性</span></template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="提交人">
              {{ detail.submitterNickname || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="手机号">
              {{ detail.submitterPhoneMasked || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ detail.ticket.categoryLabel }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ detail.ticket.priorityLabel }}</el-descriptions-item>
            <el-descriptions-item label="SLA 到期">{{ formatTime(detail.ticket.slaDueAt) }}</el-descriptions-item>
            <el-descriptions-item label="处理人">{{ detail.ticket.agentId || '未分配' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(detail.ticket.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="关闭时间">
              {{ detail.ticket.closedAt ? formatTime(detail.ticket.closedAt) : '—' }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="OpsTicketDetail">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getTicket, replyTicket, closeTicket } from '@/api/biz/ops'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const replying = ref(false)
const detail = ref(null)
const replyContent = ref('')

/**
 * 工单 id（路由参数）。
 *
 * @returns {string} id
 */
function ticketId() {
  return route.params.id
}

/**
 * 加载详情。
 */
async function load() {
  loading.value = true
  try {
    const res = await getTicket(ticketId())
    detail.value = res.data
  } finally {
    loading.value = false
  }
}

/**
 * 发送回复。
 */
async function handleReply() {
  if (!replyContent.value.trim()) {
    ElMessage.warning('回复内容不能为空')
    return
  }
  replying.value = true
  try {
    await replyTicket(ticketId(), { content: replyContent.value.trim() })
    ElMessage.success('已发送，用户会在站内信收到')
    replyContent.value = ''
    load()
  } finally {
    replying.value = false
  }
}

/**
 * 关闭工单。
 */
async function handleClose() {
  const { value } = await ElMessageBox.prompt('关闭后不可回退，将通知用户。', '关闭工单', {
    inputPlaceholder: '关闭原因（必填）',
    inputValidator: v => !!v || '关闭原因不能为空'
  })
  await closeTicket(ticketId(), { reason: value })
  ElMessage.success('已关闭')
  load()
}

/**
 * 返回列表。
 */
function goBack() {
  router.push('/ticket/list')
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
.timeline-action {
  font-weight: 600;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
