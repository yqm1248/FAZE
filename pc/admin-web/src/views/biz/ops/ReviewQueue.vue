<!--
  职责：PC 后台 A-20 内容审核队列——左栏命中记录，右栏命中详情与三种处置。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/ReviewQueue）。
  数据来源：src/api/biz/ops.js（第 7 段 B3 的 3 个接口）。
  注意：
    1. **原文完整保留，此处只做标注，平台不改写、不替换**——这是本页常驻说明，
       也是 7-A2 的自证（页面上必须看得见这句话）。
    2. 上下文是**降级态**（`contextAvailable=false`）：本段不接 conversation 域，
       页面显示服务端给的 `contextNote`，不是显示空列表。
    3. 处置三选（忽略/警告/封禁）都会立即从待处理队列消失（CAS 置终态），
       重复处置返回 410008。
-->
<template>
  <div class="app-container">
    <el-alert
      title="原文完整保留，此处只做标注，平台不改写、不替换"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-row :gutter="16">
      <!-- 左栏：列表 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>命中记录</span>
              <el-radio-group v-model="query.status" size="small" @change="reload">
                <el-radio-button label="pending">待处置</el-radio-button>
                <el-radio-button label="">全部</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <el-form :inline="true" :model="query">
            <el-form-item label="类型">
              <el-select v-model="query.type" clearable placeholder="全部" style="width: 130px">
                <el-option label="投诉" value="complaint" />
                <el-option label="反馈" value="feedback" />
                <el-option label="私信" value="im_message" />
                <el-option label="评价" value="review" />
                <el-option label="文章评论" value="article_comment" />
                <el-option label="AI 回答" value="ai_message" />
              </el-select>
            </el-form-item>
            <el-form-item label="级别">
              <el-select v-model="query.level" clearable placeholder="全部" style="width: 110px">
                <el-option label="拦截" value="block" />
                <el-option label="送审" value="review" />
                <el-option label="仅记录" value="warn" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reload">查询</el-button>
              <el-button @click="resetQuery">重置</el-button>
            </el-form-item>
          </el-form>

          <el-table
            v-loading="loading"
            :data="rows"
            border
            stripe
            highlight-current-row
            @current-change="handleSelect"
          >
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="bizTypeLabel" label="类型" width="100" />
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" size="small">{{ row.level }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="hitSnippet" label="命中片段" min-width="220" show-overflow-tooltip />
            <el-table-column label="处置" width="90">
              <template #default="{ row }">
                <el-tag :type="row.reviewResult === 'pending' ? 'warning' : 'info'" size="small">
                  {{ resultLabel(row.reviewResult) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
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
      </el-col>

      <!-- 右栏：详情 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <span>命中详情</span>
          </template>
          <el-empty v-if="!detail" description="从左侧选择一条记录" />
          <div v-else>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="类型">
                {{ detail.review.bizTypeLabel }}（bizId={{ detail.review.bizId }}）
              </el-descriptions-item>
              <el-descriptions-item label="级别">
                <el-tag :type="levelTagType(detail.review.level)" size="small">{{ detail.review.level }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="处置状态">
                {{ resultLabel(detail.review.reviewResult) }}
              </el-descriptions-item>
              <el-descriptions-item label="判定引擎">{{ detail.review.engine || '—' }}</el-descriptions-item>
            </el-descriptions>

            <el-divider content-position="left">命中片段（原文完整保留，只标注）</el-divider>
            <div class="snippet">{{ detail.review.hitSnippet || '（无片段）' }}</div>

            <el-divider content-position="left">上下文</el-divider>
            <el-alert v-if="!detail.contextAvailable" :title="detail.contextNote" type="warning" :closable="false" />

            <el-divider content-position="left">命中词</el-divider>
            <div class="hit-words">
              <el-tag v-for="(w, i) in parsedHitWords" :key="i" size="small" style="margin: 0 6px 6px 0">
                {{ w.word }}（{{ w.level }}）
              </el-tag>
              <span v-if="parsedHitWords.length === 0" class="muted">—</span>
            </div>

            <div v-if="detail.review.reviewResult === 'pending'" class="actions">
              <el-button type="primary" :loading="auditing" @click="handleAudit('ignore')">忽略（误判）</el-button>
              <el-button type="warning" :loading="auditing" @click="handleAudit('warn')">警告用户</el-button>
              <el-button type="danger" :loading="auditing" @click="handleAudit('ban')">封禁</el-button>
            </div>
            <el-alert v-else title="该记录已处置，不可再改" type="info" :closable="false" style="margin-top: 12px" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="OpsReviewQueue">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listContentReviews, getContentReview, auditContentReview } from '@/api/biz/ops'

const loading = ref(false)
const auditing = ref(false)
const rows = ref([])
const total = ref(0)
const detail = ref(null)
const query = reactive({ status: 'pending', type: '', level: '', pageNum: 1, pageSize: 10 })

/** 解析后的命中词列表（服务端给的是 JSON 字符串）。 */
const parsedHitWords = computed(() => {
  if (!detail.value || !detail.value.review || !detail.value.review.hitWords) return []
  try {
    return JSON.parse(detail.value.review.hitWords)
  } catch (e) {
    return []
  }
})

/**
 * 加载列表。
 */
async function load() {
  loading.value = true
  try {
    const res = await listContentReviews(query)
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
  query.status = 'pending'
  query.type = ''
  query.level = ''
  reload()
}

/**
 * 选中一行 → 拉详情。
 *
 * @param {object} row 行数据
 */
async function handleSelect(row) {
  if (!row) {
    detail.value = null
    return
  }
  const res = await getContentReview(row.id)
  detail.value = res.data
}

/**
 * 处置。
 *
 * @param {string} action ignore|warn|ban
 */
async function handleAudit(action) {
  const labels = { ignore: '忽略（判定为误判）', warn: '警告用户', ban: '封禁用户' }
  const { value } = await ElMessageBox.prompt(`确认${labels[action]}？请填写理由（会通知用户）`, '处置确认', {
    inputPlaceholder: '处置理由',
    inputValidator: v => !!v || '处置理由不能为空'
  })
  auditing.value = true
  try {
    await auditContentReview(detail.value.review.id, { action, reason: value })
    ElMessage.success('已处置')
    detail.value = null
    load()
  } finally {
    auditing.value = false
  }
}

/**
 * 级别 → tag 类型。
 *
 * @param {string} level 级别
 * @returns {string} tag type
 */
function levelTagType(level) {
  if (level === 'block') return 'danger'
  if (level === 'review') return 'warning'
  return 'info'
}

/**
 * 处置结果中文名。
 *
 * @param {string} result 结果 code
 * @returns {string} 中文名
 */
function resultLabel(result) {
  const map = { pending: '待处置', ignored: '已忽略', warned: '已警告', banned: '已封禁' }
  return map[result] || result
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
.snippet {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}
.hit-words {
  min-height: 24px;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
</style>
