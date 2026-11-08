<!--
  职责：PC A-18 文书模板 —— 列表、筛选、新建入口、预览、跳转编辑（第 4 段 B1）。
  调用方：RuoYi 动态路由（sys_menu 菜单：文书模板，component = biz/document/template/index）。
  数据来源：GET /api/v1/admin/doc-templates（RuoYi 口径 rows/total）
            POST /api/v1/admin/doc-templates/{id}/preview（渲染预览 PNG，60 秒地址）
  注意：
    1. **设计稿四处不实现**（工作流 §三 B1 第 5 条，别加回来）：
       - 「适用法条」列（本段不做文书法条引用）；
       - 「导入模板」「批量导出模板」两个按钮（非本段出口，禁用 + 提示）；
       - 「上传 .docx」（模板是库里的 HTML，不是文件）。
    2. **启停不在本页做**：后端只有 5 个接口，没有独立的「改状态」端点；
       启停是编辑页里 status 字段的一部分，保存即生效。列表页只提供 编辑 / 预览。
    3. 预览图是真 PDF 转的 PNG（不是 HTML 截图），所以能看出边距、分页、字体。
    4. 四态齐全：加载中（v-loading）/ 空（el-table empty-text）/ 错误（常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">文书模板</h1>
        <p class="page-head__desc">
          运营维护 HTML 模板。保存会生成新版本，历史文书仍按生成时的版本渲染，不受影响。
        </p>
      </div>
    </div>

    <!-- ==================== 错误态（常驻 + 可重试） ==================== -->
    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="模板列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="分类">
          <el-select v-model="query.type" clearable placeholder="全部" style="width: 160px">
            <el-option v-for="c in CATEGORY_OPTIONS" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px">
            <el-option label="草稿" value="draft" />
            <el-option label="已启用" value="active" />
            <el-option label="已停用" value="inactive" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="query.kw"
            placeholder="模板名或编码"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ==================== 表格 ==================== -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="table-toolbar__left">
          <el-button type="primary" @click="goCreate">新建模板</el-button>
          <el-tooltip content="暂未开放" placement="top">
            <el-button disabled>导入模板</el-button>
          </el-tooltip>
          <el-tooltip content="暂未开放" placement="top">
            <el-button disabled>批量导出</el-button>
          </el-tooltip>
        </div>
        <div class="table-toolbar__right">
          <el-button :loading="loading" @click="loadList">刷新</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" empty-text="暂无模板，点「新建模板」开始配置">
        <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="95">
          <template #default="{ row }">{{ row.category || '—' }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="60" align="center">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="fieldCount" label="字段数" width="70" align="center" />
        <el-table-column prop="status" label="状态" width="85" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goEdit(row)">编辑</el-button>
            <el-button type="primary" link :loading="previewingId === row.id" @click="handlePreview(row)">
              预览
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- ==================== 预览弹窗 ==================== -->
    <el-dialog v-model="previewVisible" title="模板预览（真 PDF 转图）" width="720px">
      <div v-loading="previewLoading" class="preview-body">
        <img v-if="previewUrl" :src="previewUrl" class="preview-img" alt="模板预览" />
        <el-empty v-else description="暂无预览图" />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listDocTemplates, previewDocTemplate } from '@/api/biz/document/template'
import { parseTime } from '@/utils/ruoyi'

/** 分类建议值（与导航树 L303 一致）；后端不强制。 */
const CATEGORY_OPTIONS = ['诉讼文书', '合同文书', '劳动文书', '跨境电商']

const router = useRouter()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const query = reactive({ type: '', status: '', kw: '', pageNum: 1, pageSize: 20 })

const previewVisible = ref(false)
const previewLoading = ref(false)
const previewUrl = ref('')
const previewingId = ref(null)

/** 状态中文名（后端只存英文码，展示在前端）。 */
function statusLabel(status) {
  return { draft: '草稿', active: '已启用', inactive: '已停用' }[status] || status
}

/** 状态标签配色。 */
function statusTagType(status) {
  return { draft: 'info', active: 'success', inactive: 'warning' }[status] || 'info'
}

/**
 * 更新时间展示。
 *
 * 后端返回 LocalDateTime，Jackson 序列化成 ISO 的 `2026-11-07T11:54:16`（带 T），
 * 直接展示对运营不友好且 170px 也会被固定列压掉右半，这里统一成 `YYYY-MM-DD HH:mm:ss`。
 */
function formatTime(t) {
  return t ? parseTime(t) : '—'
}

/**
 * 拉列表。
 *
 * 错误统一进 error 常驻条，不弹 message——列表页反复失败时弹窗会刷屏。
 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listDocTemplates(query)
    rows.value = res.rows || []
    total.value = res.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e?.message || '请求失败'
  } finally {
    loading.value = false
  }
}

/** 查询：关键字/筛选变化要回到第 1 页，否则会停在不存在的页上。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置筛选条件。 */
function handleReset() {
  query.type = ''
  query.status = ''
  query.kw = ''
  query.pageNum = 1
  loadList()
}

/** 新建：用固定字符串 `new` 表示新建态，避免用 0 与真实 id 混淆。 */
function goCreate() {
  router.push('/content/template/edit/new')
}

/** 编辑：带 id 进入编辑页。 */
function goEdit(row) {
  router.push(`/content/template/edit/${row.id}`)
}

/**
 * 渲染并展示预览图。
 *
 * 每次点预览都重新渲染：模板可能刚改过，缓存图会误导运营。
 */
async function handlePreview(row) {
  previewingId.value = row.id
  previewVisible.value = true
  previewLoading.value = true
  previewUrl.value = ''
  try {
    const res = await previewDocTemplate(row.id)
    previewUrl.value = res.data?.url || ''
  } catch (e) {
    error.value = e?.message || '预览渲染失败'
  } finally {
    previewLoading.value = false
    previewingId.value = null
  }
}

onMounted(loadList)
</script>

<style scoped>
.page-head__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.page-head__desc {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.block-gap {
  margin-bottom: 16px;
}
.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.table-toolbar__left {
  display: flex;
  gap: 8px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.preview-body {
  min-height: 200px;
  display: flex;
  justify-content: center;
}
.preview-img {
  max-width: 100%;
  border: 1px solid var(--el-border-color);
}
</style>
