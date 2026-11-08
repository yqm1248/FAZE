<!--
  职责：PC A-19 案件管理列表（段 5 B6）——筛选、状态 chips、8 列表格、分页、详情抽屉。
  调用方：RuoYi 动态路由（sys_menu 2220，component = biz/caseflow/case/index）。
  数据来源：faze-api `GET /api/v1/admin/cases`（RuoYi 口径 rows/total）。
  注意：
    1. **状态 chips 有 5 个**：设计稿只有 4 个（全部/待立案审核/立案中/审理中/已结案），
       本段补第 5 个「已驳回」——`5-A1` 要求能筛出被驳回的案件（B6 第 2 条）。
    2. **表格 8 列**：案件号 / 类型 / 当事人 / 承办律师 / 当前节点 / 立案时间 / 状态 / 操作。
       当事人第二行的称谓**按案件类型变**（被告 / 被申请人 / 被害人），不是写死「被告」。
    3. 「当前节点」不在 legal_case 上，服务端按 case_progress 取最新一条算好（B6「卡住怎么办」），
       本页不做二次查询。
    4. 提示条保留设计稿原文；**不出现**「电子送达范围待定」——本段已裁决只做平台文书送达。
    5. 四态：加载（表格 v-loading）/ 空（el-empty）/ 错误（常驻 el-alert + 重试）/ 正常。
    6. 详情走**抽屉**（el-drawer），不跳新路由：RuoYi 单页范式，且框架层布局不许动。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">案件管理</h1>
        <p class="page-head__desc">
          案件进度由承办律师在 App 端推进，后台只做立案材料审核与节点纠错。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="案件列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="80px" @submit.prevent>
        <el-form-item label="案件号/当事人">
          <el-input
            v-model="query.kw"
            placeholder="案件号 / 原告 / 被告"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="案件类型">
          <el-select v-model="query.type" style="width: 150px" clearable placeholder="全部" @change="handleSearch">
            <el-option v-for="item in TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="承办律师">
          <el-input v-model="query.lawyerId" placeholder="律师 ID，可空" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="status-chips">
          <el-radio-group v-model="query.status" @change="handleSearch">
            <el-radio-button :value="''">全部</el-radio-button>
            <el-radio-button v-for="item in STATUS_OPTIONS" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
        <span class="table-toolbar__count">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '案件加载失败，请点上方「重试」' : '当前筛选条件下没有案件'"
            :image-size="72"
          />
        </template>
        <el-table-column label="案件号" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.caseNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="90">
          <template #default="{ row }">{{ typeText(row.caseType) }}</template>
        </el-table-column>
        <el-table-column label="当事人" min-width="180">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">原告：{{ textOrDash(row.plaintiffName) }}</span>
              <span class="cell-stack__sub">{{ partyLabel(row.caseType) }}：{{ textOrDash(row.defendantName) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="承办律师" min-width="120">
          <template #default="{ row }">{{ textOrDash(row.lawyerName) }}</template>
        </el-table-column>
        <el-table-column label="当前节点" min-width="110">
          <template #default="{ row }">{{ textOrDash(row.currentNode) }}</template>
        </el-table-column>
        <el-table-column label="立案时间" min-width="130">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">详情</el-button>
            <el-button type="primary" link @click="openDetail(row, 'progress')">推进节点</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        class="table-pagination"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadList"
        @current-change="loadList"
      />
    </el-card>

    <case-detail-drawer
      v-model="drawerVisible"
      :case-id="drawerCaseId"
      :initial-tab="drawerTab"
      @changed="loadList"
    />
  </div>
</template>

<script setup>
/**
 * 案件列表页脚本。
 *
 * 职责：维护筛选条件与分页、拉列表、打开详情抽屉。
 * 副作用：挂载时拉一次列表；抽屉里发生审核/纠错后回调 `loadList` 刷新。
 */
import { onMounted, reactive, ref } from 'vue'
import { listCases } from '@/api/biz/caseflow/case'
import { formatDateTime, textOrDash } from '@/views/biz/shared/format'
import CaseDetailDrawer from './detail-drawer.vue'

/** 案件状态 → 中文（PC 后台直接显示中文，与其它管理页一致）。 */
const STATUS_TEXT = {
  filed: '待立案审核',
  accepted: '立案中',
  in_progress: '审理中',
  closed: '已结案',
  rejected: '已驳回'
}

/** 案件状态 → Element Plus tag 颜色。驳回用 danger（需要人去处理），终态用 info。 */
const STATUS_TAG = {
  filed: 'warning',
  accepted: 'primary',
  in_progress: 'success',
  closed: 'info',
  rejected: 'danger'
}

/** chips 的 5 个状态（顺序与业务流程一致）。 */
const STATUS_OPTIONS = [
  { value: 'filed', label: '待立案审核' },
  { value: 'accepted', label: '立案中' },
  { value: 'in_progress', label: '审理中' },
  { value: 'closed', label: '已结案' },
  { value: 'rejected', label: '已驳回' }
]

/** 案件类型下拉（5 类，与后端 CaseType 枚举一致）。 */
const TYPE_OPTIONS = [
  { value: 'civil', label: '民事' },
  { value: 'criminal', label: '刑事' },
  { value: 'administrative', label: '行政' },
  { value: 'labor', label: '劳动仲裁' },
  { value: 'family', label: '家事' }
]

const TYPE_TEXT = TYPE_OPTIONS.reduce((acc, item) => {
  acc[item.value] = item.label
  return acc
}, {})

const loading = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const query = reactive({
  kw: '',
  type: '',
  lawyerId: '',
  status: '',
  pageNum: 1,
  pageSize: 20
})

const drawerVisible = ref(false)
const drawerCaseId = ref('')
const drawerTab = ref('material')

/**
 * 状态文案。
 *
 * @param {string} status 状态 code
 * @returns {string} 中文；未知 code 原样返回
 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/**
 * 状态标签颜色。
 *
 * @param {string} status 状态 code
 * @returns {string} Element Plus tag type
 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/**
 * 类型文案。
 *
 * @param {string} type 类型 code
 * @returns {string} 中文；未知 code 原样返回
 */
function typeText(type) {
  return TYPE_TEXT[type] || type || '—'
}

/**
 * 对方当事人的称谓（按案件类型变，B6 第 3 条）。
 *
 * 刑事案件的对方是「被害人」，行政诉讼与劳动仲裁是「被申请人」，其余是「被告」。
 *
 * @param {string} type 类型 code
 * @returns {string} 称谓
 */
function partyLabel(type) {
  if (type === 'criminal') {
    return '被害人'
  }
  if (type === 'administrative' || type === 'labor') {
    return '被申请人'
  }
  return '被告'
}

/** 拉列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    // 列表接口是 RuoYi 口径（顶层 rows/total），拦截器已返回 body 本身
    const res = await listCases({
      kw: query.kw || undefined,
      type: query.type || undefined,
      lawyerId: query.lawyerId || undefined,
      status: query.status || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    })
    rows.value = res.rows || []
    total.value = res.total || 0
  } catch (e) {
    error.value = e && e.message ? e.message : '网络异常'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询（回到第一页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置筛选。 */
function handleReset() {
  query.kw = ''
  query.type = ''
  query.lawyerId = ''
  query.status = ''
  handleSearch()
}

/**
 * 打开详情抽屉。
 *
 * @param {object} row 列表行
 * @param {string} [tab] 初始 Tab（'material' / 'progress' / 'delivery'）
 */
function openDetail(row, tab = 'material') {
  drawerCaseId.value = String(row.id)
  drawerTab.value = tab
  drawerVisible.value = true
}

onMounted(loadList)
</script>

<style scoped>
.status-chips {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
