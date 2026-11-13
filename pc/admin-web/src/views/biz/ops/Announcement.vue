<!--
  职责：PC 后台 A-23 公告与推送——左侧新建表单，右侧已发公告列表。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/Announcement）。
  数据来源：src/api/biz/ops.js（第 7 段 D1 的 5 个接口）。
  注意：
    1. **站内信是必选渠道**（服务端强校验），推送/短信可选；短信本段只留配置不接服务商。
    2. **「预计触达」由服务端算**（改目标人群时重新请求，300ms 防抖），前端不做本地估算。
    3. **「立即发送」必须二次确认，文案含「不可撤回」**（裁决 26 + 稿面要求）。
    4. 触达/打开率都由服务端算好返回，页面只展示。
-->
<template>
  <div class="app-container">
    <el-row :gutter="16">
      <!-- 左栏：新建 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><span>新建公告</span></template>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
            <el-form-item label="公告标题" prop="title">
              <el-input v-model="form.title" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="目标人群" prop="targetType">
              <el-select v-model="form.targetType" style="width: 100%" @change="recomputeEstimate">
                <el-option label="全体用户" value="all" />
                <el-option label="按角色" value="role" />
                <el-option label="指定用户" value="user_ids" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.targetType === 'role'" label="角色">
              <el-select v-model="roleValue" style="width: 100%" @change="recomputeEstimate">
                <el-option label="律师" value="lawyer" />
                <el-option label="普通用户" value="user" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.targetType === 'user_ids'" label="用户 ID">
              <el-input v-model="userIdsText" placeholder="逗号分隔，最多 5000 个" @blur="recomputeEstimate" />
            </el-form-item>
            <el-form-item label="预计触达">
              <span class="estimate">{{ estimated === null ? '—' : estimated + ' 人' }}</span>
            </el-form-item>
            <el-form-item label="发送时间">
              <el-date-picker
                v-model="form.scheduledAt"
                type="datetime"
                placeholder="留空存为草稿"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="发送渠道" prop="channels">
              <el-checkbox-group v-model="form.channels">
                <el-checkbox label="inapp">站内信（必选）</el-checkbox>
                <el-checkbox label="push">App 推送</el-checkbox>
                <el-checkbox label="sms">短信</el-checkbox>
              </el-checkbox-group>
              <div class="hint">站内信必选；App 推送走个推通道，用户未开启通知权限时收不到；短信仅在涉及资金或服务中断时使用，按条计费。</div>
            </el-form-item>
            <el-form-item label="公告正文" prop="content">
              <el-input v-model="form.content" type="textarea" :rows="6" maxlength="5000" show-word-limit />
            </el-form-item>
            <el-form-item>
              <el-button :loading="saving" @click="handleSave(false)">存为草稿</el-button>
              <el-button type="primary" :loading="saving" @click="handleSave(true)">立即发送</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右栏：已发公告 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>已发公告</span>
              <el-button size="small" @click="load">刷新</el-button>
            </div>
          </template>
          <el-table v-loading="loading" :data="rows" border stripe>
            <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
            <el-table-column prop="targetType" label="目标" width="90" />
            <el-table-column label="渠道" width="120">
              <template #default="{ row }">{{ prettyChannels(row.channels) }}</template>
            </el-table-column>
            <el-table-column label="发送时间" width="150">
              <template #default="{ row }">{{ formatTime(row.sentAt || row.scheduledAt) }}</template>
            </el-table-column>
            <el-table-column prop="reachCount" label="触达" width="70" align="right" />
            <el-table-column prop="openRate" label="打开率" width="80" align="right" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'sent' ? 'success' : 'info'" size="small">{{ row.statusLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status !== 'sent'"
                  link
                  type="primary"
                  @click="handlePush(row)"
                >发送</el-button>
                <span v-else class="muted">不可撤回</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="pageNum"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="load"
            @size-change="load"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="OpsAnnouncement">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAnnouncements, createAnnouncement, estimateReach, pushAnnouncement } from '@/api/biz/ops'
import { formatDateTime } from '@/views/biz/shared/format'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const estimated = ref(null)
const roleValue = ref('lawyer')
const userIdsText = ref('')
const formRef = ref(null)

const form = reactive({
  title: '',
  content: '',
  targetType: 'all',
  targetValue: null,
  channels: ['inapp'],
  scheduledAt: null
})
const rules = {
  title: [{ required: true, message: '公告标题不能为空', trigger: 'blur' }],
  content: [{ required: true, message: '公告正文不能为空', trigger: 'blur' }],
  targetType: [{ required: true, message: '请选择目标人群', trigger: 'change' }],
  channels: [{ type: 'array', required: true, message: '至少选择一个渠道', trigger: 'change' }]
}

/** 防抖计时器。 */
let estimateTimer = null

/**
 * 组装 targetValue。
 *
 * @returns {string|null} JSON 字符串
 */
function buildTargetValue() {
  if (form.targetType === 'role') {
    return JSON.stringify({ role: roleValue.value })
  }
  if (form.targetType === 'user_ids') {
    const ids = userIdsText.value.split(',').map(s => Number(s.trim())).filter(n => Number.isFinite(n) && n > 0)
    return JSON.stringify({ userIds: ids })
  }
  return null
}

/**
 * 重新计算预计触达（防抖 300ms）。
 */
function recomputeEstimate() {
  if (estimateTimer) clearTimeout(estimateTimer)
  estimateTimer = setTimeout(async () => {
    const value = buildTargetValue()
    const res = await estimateReach({ targetType: form.targetType, targetValue: value })
    estimated.value = res.data
  }, 300)
}

/**
 * 保存（草稿或立即发送）。
 *
 * @param {boolean} sendNow 是否立即发送
 */
async function handleSave(sendNow) {
  await formRef.value.validate()
  if (sendNow) {
    await ElMessageBox.confirm(
      '公告发出后不可撤回，只能补发一条更正公告。确认立即发送？',
      '发送确认',
      { type: 'warning', confirmButtonText: '确认发送', cancelButtonText: '再想想' }
    )
  }
  saving.value = true
  try {
    const payload = { ...form, targetValue: buildTargetValue(), scheduledAt: sendNow ? null : form.scheduledAt }
    const res = await createAnnouncement(payload)
    if (sendNow) {
      await pushAnnouncement(res.data)
      ElMessage.success('已发送')
    } else {
      ElMessage.success('已保存')
    }
    resetForm()
    load()
  } finally {
    saving.value = false
  }
}

/**
 * 对已有公告点发送。
 *
 * @param {object} row 公告行
 */
async function handlePush(row) {
  await ElMessageBox.confirm(`发送公告「${row.title}」？发出后不可撤回。`, '发送确认', { type: 'warning' })
  await pushAnnouncement(row.id)
  ElMessage.success('已发送')
  load()
}

/**
 * 重置表单。
 */
function resetForm() {
  form.title = ''
  form.content = ''
  form.targetType = 'all'
  form.channels = ['inapp']
  form.scheduledAt = null
  userIdsText.value = ''
  estimated.value = null
}

/**
 * 加载公告列表。
 */
async function load() {
  loading.value = true
  try {
    const res = await listAnnouncements({ pageNum: pageNum.value, pageSize: pageSize.value })
    rows.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 渠道 JSON → 可读文本。
 *
 * @param {string} channels JSON 数组
 * @returns {string} 文本
 */
function prettyChannels(channels) {
  if (!channels) return '—'
  try {
    const list = JSON.parse(channels)
    const map = { inapp: '站内信', push: '推送', sms: '短信' }
    return list.map(c => map[c] || c).join(' / ')
  } catch (e) {
    return channels
  }
}

/**
 * 时间格式化。
 *
 * @param {string} time ISO 时间
 * @returns {string} 本地时间
 */
const formatTime = formatDateTime

onMounted(() => {
  load()
  recomputeEstimate()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}
.estimate {
  font-weight: 600;
  color: #409eff;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
