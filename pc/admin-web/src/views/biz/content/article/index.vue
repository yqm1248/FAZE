<!--
  职责：PC A-14 文章列表 —— 分类/状态/语言/关键词筛选、四语言完成度徽标、上架/下架（G9.3）。
  调用方：RuoYi 动态路由（sys_menu 菜单：文章管理，component = biz/content/article/index）。
  数据来源：GET /api/v1/admin/articles（RuoYi 口径 rows/total，行内含 languages）。
  注意：
    1. **语言完成度由服务端算好**（`languages` = 已有完整译文的语言，含 zh-Hans）：
       页面只做 `languages.length / 4` 与四个小徽标，**不逐行调接口**（工作流 G9.3 第 2 条）。
    2. 上架按钮直接调 publish：四语言不齐全时服务端会拒绝并在 msg 里列出缺哪种语言，
       页面原样提示（不自己再算一遍，避免两套规则）。
    3. 上架/下架都会写 operation_log（服务端 @OperationLog），页面不需要额外上报。
    4. 四态齐全：加载中 / 空 / 错误（常驻 alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">文章管理</h1>
        <p class="page-head__desc">
          「每日学法」内容源。四语言（简体 + 藏 / 维 / 哈）齐全才能上架；上架后 APP 首页与文章列表可见。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button type="primary" @click="createArticle">新建文章</el-button>
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" class="block-gap" type="error" :closable="false" show-icon title="文章列表加载失败">
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="query.status" style="width: 130px" clearable placeholder="全部" @change="handleSearch">
            <el-option label="草稿" value="draft" />
            <el-option label="已上架" value="published" />
            <el-option label="已下架" value="archived" />
          </el-select>
        </el-form-item>
        <el-form-item label="语言">
          <el-select v-model="query.lang" style="width: 150px" clearable placeholder="全部" @change="handleSearch">
            <el-option label="简体（源）" value="zh-Hans" />
            <el-option label="藏文" value="bo" />
            <el-option label="维吾尔文" value="ug-Arab-CN" />
            <el-option label="哈萨克文" value="kk-Arab-CN" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="标题" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__count">共 {{ total }} 条</span>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty :description="error ? '文章加载失败，请点上方「重试」' : '当前筛选条件下没有文章'" :image-size="72" />
        </template>
        <el-table-column label="标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title }}</template>
        </el-table-column>
        <el-table-column label="分类" min-width="100">
          <template #default="{ row }">{{ textOrDash(row.category) }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="语言完成度" min-width="210">
          <template #default="{ row }">
            <div class="lang-cell">
              <span class="lang-count mono">{{ row.languages.length }}/4</span>
              <el-tag
                v-for="lang in ALL_LANGS"
                :key="lang"
                :type="row.languages.includes(lang) ? 'success' : 'info'"
                effect="plain"
                size="small"
              >
                {{ LANG_LABEL[lang] }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="140">
          <template #default="{ row }"><span class="mono muted">{{ formatDateTime(row.updatedAt) }}</span></template>
        </el-table-column>
        <el-table-column label="上架时间" min-width="140">
          <template #default="{ row }">
            <span class="mono muted">{{ row.publishedAt ? formatDateTime(row.publishedAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="170" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="edit(row)">编辑</el-button>
            <el-button
              v-if="row.status !== 'published'"
              type="success"
              link
              :loading="actingId === row.id"
              @click="publish(row)"
            >
              上架
            </el-button>
            <el-button
              v-else
              type="warning"
              link
              :loading="actingId === row.id"
              @click="offline(row)"
            >
              下架
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        class="table-pagination"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="loadList"
        @current-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup>
/**
 * 文章列表页脚本。
 *
 * 副作用：挂载拉第一页；上架/下架成功后重拉当前页。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listArticles, offlineArticle, publishArticle } from '@/api/biz/article'
import { formatDateTime, textOrDash } from '@/views/biz/shared/format'

/** 四种语言（顺序固定：源语言在前）。 */
const ALL_LANGS = ['zh-Hans', 'bo', 'ug-Arab-CN', 'kk-Arab-CN']

/** 语言 → 短标签。 */
const LANG_LABEL = { 'zh-Hans': '简', bo: '藏', 'ug-Arab-CN': '维', 'kk-Arab-CN': '哈' }

/** 状态文案。 */
const STATUS_TEXT = { draft: '草稿', published: '已上架', archived: '已下架' }

/** 状态标签颜色。 */
const STATUS_TAG = { draft: 'info', published: 'success', archived: 'warning' }

const router = useRouter()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const actingId = ref('')
const query = reactive({ status: '', lang: '', keyword: '', pageNum: 1, pageSize: 10 })

/** 状态文案。 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/** 状态标签颜色。 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/** 拉列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.status) {
      params.status = query.status
    }
    if (query.lang) {
      params.lang = query.lang
    }
    if (query.keyword) {
      params.keyword = query.keyword
    }
    const data = await listArticles(params)
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    error.value = e.message || '文章列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询（回第一页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置筛选。 */
function handleReset() {
  query.status = ''
  query.lang = ''
  query.keyword = ''
  query.pageNum = 1
  loadList()
}

/** 新建。 */
function createArticle() {
  router.push('/biz/content/article/edit')
}

/** 编辑。 */
function edit(row) {
  router.push({ path: '/biz/content/article/edit', query: { id: row.id } })
}

/** 上架（失败时服务端会说明缺哪种语言）。 */
async function publish(row) {
  // 本地先预判四语言完整度：服务端会拒绝，但它的响应 msg 是通用文案（R.fail 只取 ErrorCode.msg），
  // 运营看不到「缺哪种语言」，所以这里用列表行已返回的 languages 给出可读提示。
  const missing = ALL_LANGS.filter((lang) => !row.languages.includes(lang))
  if (missing.length > 0) {
    ElMessage.error(`四语言不齐全，缺少：${missing.map((lang) => LANG_LABEL[lang]).join('、')}（标题与正文都要有）`)
    return
  }
  actingId.value = row.id
  try {
    await publishArticle(row.id)
    ElMessage.success('已上架')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '上架失败')
  } finally {
    actingId.value = ''
  }
}

/** 下架。 */
async function offline(row) {
  try {
    await ElMessageBox.confirm(
      '下架后 APP 首页与文章列表不再展示这篇文章，已读过的链接也会 404。确定下架？',
      '下架文章',
      { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actingId.value = row.id
  try {
    await offlineArticle(row.id)
    ElMessage.success('已下架')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message || '下架失败')
  } finally {
    actingId.value = ''
  }
}

onMounted(loadList)
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
  line-height: 1.6;
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
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.table-toolbar__count {
  color: #6b7280;
  font-size: 13px;
}
.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.lang-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.lang-count {
  font-weight: 600;
  color: #111827;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: #6b7280;
}
</style>
