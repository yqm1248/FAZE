<!--
  职责：PC 后台 A-24 通知模板——列表 / 新建 / 编辑 / 软停用 / 测试渲染。
  调用方：RuoYi 动态路由（sys_menu component = biz/ops/NotifyTemplate）。
  数据来源：src/api/biz/ops.js（第 7 段 D2 的 5 个接口）。
  注意：
    1. **变量只认 `{{camelCase}}`**（禁位置参数）；标题与正文的变量集合必须一致，
       不一致保存会被拒（服务端校验），页面把错误提示原样显示。
    2. **删除是软停用**（status=disabled）：历史通知要能追溯模板，所以按钮文案叫「停用」。
    3. 「测试」只返回渲染预览文本，不写站内信（管理员不在 app_user 里，见接口注释）。
-->
<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>通知模板</span>
          <el-button type="primary" @click="openCreate">新建模板</el-button>
        </div>
      </template>

      <el-form :inline="true" :model="query">
        <el-form-item label="关键词">
          <el-input v-model="query.kw" placeholder="编码或标题" clearable style="width: 160px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="query.channel" clearable placeholder="全部" style="width: 120px">
            <el-option label="站内信" value="inapp" />
            <el-option label="推送" value="push" />
            <el-option label="短信" value="sms" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="templateCode" label="模板编码" width="180" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="contentPreview" label="内容预览" min-width="200" show-overflow-tooltip />
        <el-table-column label="变量" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="v in row.variables" :key="v" size="small" style="margin-right: 4px">
              {{ formatVariable(v) }}
            </el-tag>
            <span v-if="!row.variables || row.variables.length === 0" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="channel" label="渠道" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="handleTest(row)">测试</el-button>
            <el-button v-if="row.status === 'active'" link type="danger" @click="handleDisable(row)">停用</el-button>
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

    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑模板' : '新建模板'" size="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="模板编码" prop="templateCode">
          <el-input v-model="form.templateCode" placeholder="小写字母/数字/下划线，如 order_paid" />
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="可含 {{userName}}" />
        </el-form-item>
        <el-form-item label="正文" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="可含 {{orderNo}}" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel" style="width: 100%">
            <el-option label="站内信" value="inapp" />
            <el-option label="推送" value="push" />
            <el-option label="短信" value="sms" />
          </el-select>
        </el-form-item>
        <el-alert
          title="标题与正文的变量集合必须一致（如标题有 {{a}}、正文也要有 {{a}}），否则保存会被拒"
          type="info"
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button @click="drawerVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup name="OpsNotifyTemplate">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTemplates, createTemplate, updateTemplate, disableTemplate, testTemplate } from '@/api/biz/ops'
import { formatDateTime } from '@/views/biz/shared/format'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ kw: '', channel: '', status: '', pageNum: 1, pageSize: 10 })

const drawerVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = reactive({ templateCode: '', title: '', content: '', channel: 'inapp' })
const rules = {
  templateCode: [
    { required: true, message: '模板编码不能为空', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]*$/, message: '只能是小写字母、数字与下划线', trigger: 'blur' }
  ],
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' }],
  content: [{ required: true, message: '正文不能为空', trigger: 'blur' }]
}

/**
 * 加载列表。
 */
async function load() {
  loading.value = true
  try {
    const res = await listTemplates(query)
    rows.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 变量名 → 模板占位符文本（如 `{{orderNo}}`）。
 *
 * 为什么不在模板里直接写 `{{ '{{' + v + '}}' }}`：Vue 的插值解析器遇到字符串里的
 * `}}` 会提前闭合插值，报「Unterminated string constant」导致整页编译失败。
 * 把拼装放进脚本就能绕开模板解析（本处为第 7 段 D4 交付物的一处编译缺陷修复）。
 *
 * @param {string} variable 变量名
 * @returns {string} 形如 `{{variable}}`
 */
function formatVariable(variable) {
  return `{{${variable}}}`
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
  query.kw = ''
  query.channel = ''
  query.status = ''
  reload()
}

/**
 * 打开新建抽屉。
 */
function openCreate() {
  editingId.value = null
  Object.assign(form, { templateCode: '', title: '', content: '', channel: 'inapp' })
  drawerVisible.value = true
}

/**
 * 打开编辑抽屉。
 *
 * @param {object} row 模板行
 */
function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, { templateCode: row.templateCode, title: row.title, content: row.content, channel: row.channel })
  drawerVisible.value = true
}

/**
 * 保存。
 */
async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editingId.value) {
      await updateTemplate(editingId.value, form)
    } else {
      await createTemplate(form)
    }
    ElMessage.success('已保存')
    drawerVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

/**
 * 停用（软停用）。
 *
 * @param {object} row 模板行
 */
async function handleDisable(row) {
  await ElMessageBox.confirm(`停用模板「${row.templateCode}」？历史通知不受影响。`, '停用确认', { type: 'warning' })
  await disableTemplate(row.id)
  ElMessage.success('已停用')
  load()
}

/**
 * 测试渲染。
 *
 * @param {object} row 模板行
 */
async function handleTest(row) {
  const res = await testTemplate(row.id)
  await ElMessageBox.alert(res.data, '渲染预览（变量已替换为示例值）', { type: 'success' })
}

/**
 * 时间格式化。
 *
 * @param {string} time ISO 时间
 * @returns {string} 本地时间
 */
const formatTime = formatDateTime

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
