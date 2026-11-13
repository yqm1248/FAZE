<!--
  职责：PC 后台 A-25 敏感词管理页——左栏词库（增删改查/导入/导出），右栏申诉复核。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/SensitiveWord）。
  数据来源：src/api/biz/ops.js（第 7 段 B1/B2 的 9 个接口）。
  注意：
    1. 本段列表接口返回 `R<PageResult>`，分页字段是 `list/total`，不是 RuoYi 的 `rows/total`
       （ops.js 文件头已说明），所以 `:data` 取 `res.data.list`。
    2. **删除是物理删、停用走编辑**（enabled=0）：两个动作语义不同，不要合并成一个按钮。
    3. 导入 CSV 表头固定 `word,category,level,lang`；**全对才写**，有错误会返回行号列表。
    4. 保存后服务端会重载词库（热更新），本页不需要刷新整个页面。
-->
<template>
  <div class="app-container">
    <el-row :gutter="16">
      <!-- 左栏：词库 -->
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>敏感词库</span>
              <div>
                <el-button type="primary" @click="openCreate">新增词条</el-button>
                <el-upload
                  :show-file-list="false"
                  :before-upload="handleImport"
                  accept=".csv"
                  style="display: inline-block; margin-left: 8px"
                >
                  <el-button>批量导入</el-button>
                </el-upload>
                <el-button style="margin-left: 8px" :loading="exporting" @click="handleExport">导出</el-button>
              </div>
            </div>
          </template>

          <el-form :inline="true" :model="query" class="filter-form">
            <el-form-item label="关键词">
              <el-input v-model="query.kw" placeholder="词条" clearable style="width: 140px" @keyup.enter="reload" />
            </el-form-item>
            <el-form-item label="级别">
              <el-select v-model="query.level" clearable placeholder="全部" style="width: 110px">
                <el-option label="拦截" value="block" />
                <el-option label="送审" value="review" />
                <el-option label="仅记录" value="warn" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="query.category" clearable placeholder="全部" style="width: 120px">
                <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="语言">
              <el-select v-model="query.lang" clearable placeholder="全部" style="width: 120px">
                <el-option label="简体中文" value="zh-Hans" />
                <el-option label="藏文" value="bo" />
                <el-option label="维吾尔文" value="ug-Arab-CN" />
                <el-option label="哈萨克文" value="kk-Arab-CN" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reload">查询</el-button>
              <el-button @click="resetQuery">重置</el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="loading" :data="rows" border stripe>
            <el-table-column prop="word" label="词条" min-width="160" show-overflow-tooltip />
            <el-table-column prop="categoryLabel" label="分类" width="100" />
            <el-table-column label="级别" width="100">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" size="small">{{ row.levelLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="lang" label="语言" width="110" />
            <el-table-column prop="hitCount" label="命中" width="80" align="right" />
            <el-table-column label="最近命中" width="170">
              <template #default="{ row }">{{ formatTime(row.lastHitAt) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
                  {{ row.enabled === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="query.pageNum"
            v-model:page-size="query.pageSize"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="load"
            @size-change="reload"
          />
        </el-card>
      </el-col>

      <!-- 右栏：申诉复核 -->
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>申诉复核</span>
              <el-radio-group v-model="appealQuery.status" size="small" @change="loadAppeals">
                <el-radio-button label="pending">待处理</el-radio-button>
                <el-radio-button label="">全部</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <el-empty v-if="!appealLoading && appeals.length === 0" description="暂无申诉" />
          <div v-loading="appealLoading">
            <el-card v-for="item in appeals" :key="item.id" shadow="hover" class="appeal-card">
              <div class="appeal-title">
                <el-tag size="small" :type="levelTagType(item.level)">{{ item.level }}</el-tag>
                <span class="appeal-id">#{{ item.contentReviewId }}</span>
              </div>
              <div class="appeal-snippet">{{ item.hitSnippet || '（无片段）' }}</div>
              <div class="appeal-reason">理由：{{ item.appealReason }}</div>
              <div class="appeal-actions">
                <el-button size="small" type="success" @click="handleAppeal(item, 'accepted')">通过申诉</el-button>
                <el-button size="small" type="warning" @click="handleAppeal(item, 'rejected')">维持原判</el-button>
              </div>
            </el-card>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 新增/编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑词条' : '新增词条'" size="420px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="词条" prop="word">
          <el-input v-model="form.word" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" placeholder="请选择" style="width: 100%">
            <el-option v-for="c in categories" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别" prop="level">
          <el-select v-model="form.level" placeholder="请选择" style="width: 100%">
            <el-option label="拦截（block）" value="block" />
            <el-option label="送审（review）" value="review" />
            <el-option label="仅记录（warn）" value="warn" />
          </el-select>
        </el-form-item>
        <el-form-item label="语言" prop="lang">
          <el-select v-model="form.lang" placeholder="请选择" style="width: 100%">
            <el-option label="简体中文" value="zh-Hans" />
            <el-option label="藏文" value="bo" />
            <el-option label="维吾尔文" value="ug-Arab-CN" />
            <el-option label="哈萨克文" value="kk-Arab-CN" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button @click="drawerVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup name="OpsSensitiveWord">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listSensitiveWords,
  createSensitiveWord,
  updateSensitiveWord,
  deleteSensitiveWord,
  importSensitiveWords,
  exportSensitiveWords,
  listAppeals,
  auditAppeal
} from '@/api/biz/ops'
import { formatDateTime } from '@/views/biz/shared/format';

const categories = [
  { value: 'POLITICS', label: '政治' },
  { value: 'VIOLENCE', label: '暴恐' },
  { value: 'PORN', label: '色情低俗' },
  { value: 'FRAUD', label: '欺诈' },
  { value: 'ILLEGAL', label: '违法' }
]

const loading = ref(false)
const exporting = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ kw: '', level: '', category: '', lang: '', enabled: null, pageNum: 1, pageSize: 10 })

const drawerVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = reactive({ word: '', category: 'FRAUD', level: 'review', lang: 'zh-Hans', enabled: 1 })
const rules = {
  word: [{ required: true, message: '词条不能为空', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  level: [{ required: true, message: '请选择级别', trigger: 'change' }],
  lang: [{ required: true, message: '请选择语言', trigger: 'change' }]
}

const appealLoading = ref(false)
const appeals = ref([])
const appealQuery = reactive({ status: 'pending' })

/**
 * 加载词库列表。
 */
async function load() {
  loading.value = true
  try {
    const res = await listSensitiveWords(query)
    rows.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 重置到第一页后加载。
 */
function reload() {
  query.pageNum = 1
  load()
}

/**
 * 重置筛选条件。
 */
function resetQuery() {
  query.kw = ''
  query.level = ''
  query.category = ''
  query.lang = ''
  query.enabled = null
  reload()
}

/**
 * 打开发新增抽屉。
 */
function openCreate() {
  editingId.value = null
  Object.assign(form, { word: '', category: 'FRAUD', level: 'review', lang: 'zh-Hans', enabled: 1 })
  drawerVisible.value = true
}

/**
 * 打开编辑抽屉。
 *
 * @param {object} row 行数据
 */
function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    word: row.word,
    category: row.category,
    level: row.level,
    lang: row.lang,
    enabled: row.enabled
  })
  drawerVisible.value = true
}

/**
 * 保存（新增或修改）。
 */
async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editingId.value) {
      await updateSensitiveWord(editingId.value, form)
    } else {
      await createSensitiveWord(form)
    }
    ElMessage.success('已保存，词库已热更新')
    drawerVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

/**
 * 删除词条（物理删）。
 *
 * @param {object} row 行数据
 */
async function handleDelete(row) {
  await ElMessageBox.confirm(`确定删除「${row.word}」？删除后判定立即生效。`, '删除确认', { type: 'warning' })
  await deleteSensitiveWord(row.id)
  ElMessage.success('已删除')
  load()
}

/**
 * 导入 CSV。
 *
 * @param {File} file 文件
 * @returns {boolean} 始终 false（阻止 el-upload 自动上传）
 */
async function handleImport(file) {
  const formData = new FormData()
  formData.append('file', file)
  const res = await importSensitiveWords(formData)
  const info = res.data || {}
  if (info.failed > 0) {
    const lines = (info.errors || []).map(e => `第 ${e.line} 行：${e.reason}`).join('\n')
    ElMessageBox.alert(`${lines}${info.truncated ? '\n（错误过多，只显示前 200 条）' : ''}`, '导入失败（整批未写入）', {
      type: 'error'
    })
  } else {
    ElMessage.success(`导入成功 ${info.success} 条`)
    load()
  }
  return false
}

/**
 * 导出词库。
 */
async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportSensitiveWords({ level: query.level, lang: query.lang, category: query.category })
    downloadBlob(blob, 'sensitive-words.csv')
  } finally {
    exporting.value = false
  }
}

/**
 * 加载申诉列表。
 */
async function loadAppeals() {
  appealLoading.value = true
  try {
    const res = await listAppeals(appealQuery)
    appeals.value = res.data.list || []
  } finally {
    appealLoading.value = false
  }
}

/**
 * 处置申诉。
 *
 * @param {object} item 申诉行
 * @param {string} action accepted|rejected
 */
async function handleAppeal(item, action) {
  const { value } = await ElMessageBox.prompt(
    action === 'accepted' ? '通过申诉（该条命中从审核队列移除，词条本身不变）' : '维持原判',
    '复核说明',
    { inputPlaceholder: '请填写复核说明，将通知用户', inputValidator: v => !!v || '复核说明不能为空' }
  )
  await auditAppeal(item.id, { action, reason: value })
  ElMessage.success('已处置')
  loadAppeals()
  load()
}

/**
 * 级别 → el-tag 类型。
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
 * 时间格式化（UTC → 本地）。
 *
 * @param {string} time ISO 时间
 * @returns {string} 本地时间
 */
const formatTime = formatDateTime

/**
 * 触发浏览器下载。
 *
 * @param {Blob} blob 文件
 * @param {string} filename 文件名
 */
function downloadBlob(blob, filename) {
  const url = window.URL.createObjectURL(new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  window.URL.revokeObjectURL(url)
}

onMounted(() => {
  load()
  loadAppeals()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filter-form {
  margin-bottom: 4px;
}
.appeal-card {
  margin-bottom: 10px;
}
.appeal-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.appeal-id {
  color: #909399;
  font-size: 12px;
}
.appeal-snippet {
  font-size: 13px;
  color: #303133;
  background: #f5f7fa;
  padding: 6px 8px;
  border-radius: 4px;
  margin-bottom: 6px;
  word-break: break-all;
}
.appeal-reason {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}
.appeal-actions {
  display: flex;
  gap: 8px;
}
</style>
