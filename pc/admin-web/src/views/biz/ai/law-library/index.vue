<!--
  职责：PC A-27 法条库 —— 列表查询、逐条查看、修正（写新版本）、批量导入、标记失效（第 3 段 H1）。
  调用方：RuoYi 动态路由（sys_menu 菜单：法条库，component = biz/ai/law-library/index）。
  数据来源：GET /api/v1/admin/law-items（RuoYi 口径 rows/total，行内含 aiRefCount）
            GET /api/v1/admin/law-items/{id}（详情，含全文 content）
            PUT /api/v1/admin/law-items/{id}（修正 = 写新版本，不改旧行）
            POST /api/v1/admin/law-items/import（批量导入，见 import-dialog.vue）
            POST /api/v1/admin/law-items/{id}/disable（标记失效，不做物理删除）
  注意：
    1. **设计稿三处删除（本段硬要求，别加回来）**：
       - 「新增法条」按钮（不做 POST 单条新增：负责人从后台导入，不手录单条）；
       - 「导出条文」「导出筛选结果」两个导出按钮（不做导出接口）；
       - 「复核状态」查询项与表格列（§零.3 第 9 项：不做复核）。
       表格保留列：法律名称 / 条号 / 生效版本 / 条文摘要 / AI 引用次数 / 更新时间 / 操作。
    2. **「复核状态」删除后，验收仍要求「标记失效后能加筛选可看」**：查询栏放一个
       「显示已失效」开关，对应后端扩展参数 `isEnabled`（默认不传 = 只看 is_enabled=1，
       开 = 传 0 只看已停用的行）。这不是复核状态，是启用状态。
    3. **AI 引用次数的口径由 B4 服务端算好**（assistant 消息 refs 中被引用的次数，含已软删会话）：
       页面直接显示 `aiRefCount`，**不自己再算**，避免两套口径。
    4. 列表行只有 `contentSummary`（≤200 字）；全文只在「查看」弹窗里按 id 拉详情。
       列表里把摘要当全文展示会导致运营以为法条被截断。
    5. 修正的语义是「旧行 expire_date=修正生效日 / status=amended / is_enabled=0，同时
       INSERT 一条新的 active 行」。**旧行不物理删除**，因为 AI 历史回答的 refs 快照按
       law_item 记录，删了历史引用就悬空。页面只提交修正内容，不拼两条记录。
    6. 四态齐全：加载中（表格 v-loading）/ 空（el-empty）/ 错误（常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">法条库</h1>
        <p class="page-head__desc">
          本地法条库，AI 只引用这里存在的条文。修正条文后，新的回答按新版引用；历史记录的引用快照不回溯改写。
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
      title="法条列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="84px" @submit.prevent>
        <el-form-item label="法律">
          <el-select
            v-model="query.lawCode"
            class="query-law"
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="全部（可输入 lawCode）"
          >
            <el-option v-for="item in LAW_CODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="条号">
          <el-input v-model="query.articleNo" placeholder="如 577 或 第五百七十七条" clearable style="width: 200px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="条文关键词">
          <el-input v-model="query.kw" placeholder="如 违约金 / 经济补偿" clearable style="width: 200px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="显示已失效">
          <el-switch v-model="query.showDisabled" @change="handleSearch" />
          <span class="query-hint">默认只看法条库启用中的条文</span>
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
          <!-- 设计稿的「新增法条」「批量导入」「导出筛选结果」三个按钮中，只保留「批量导入」 -->
          <el-button v-hasPermi="['biz:law:import']" type="primary" @click="openImport">批量导入</el-button>
        </div>
        <div class="table-toolbar__right">
          <span class="table-toolbar__count">共 {{ total }} 条</span>
          <el-button :loading="loading" @click="loadList">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '法条加载失败，请点上方「重试」' : emptyDescription"
            :image-size="72"
          />
        </template>

        <el-table-column label="法律名称" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ textOrDash(row.lawName) }}</template>
        </el-table-column>

        <el-table-column label="条号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ articleNoText(row) }}</template>
        </el-table-column>

        <el-table-column label="生效版本" width="145">
          <template #default="{ row }">
            <div>{{ formatDate(row.effectiveDate) }} 施行</div>
            <!-- 已失效/已修正的旧行：用失效日期说明，不另开「复核状态」列 -->
            <div v-if="validityNote(row)" class="cell-note">{{ validityNote(row) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="条文摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ textOrDash(row.contentSummary) }}</template>
        </el-table-column>

        <el-table-column width="120" align="right">
          <template #header>
            <el-tooltip
              content="口径：AI 回答（assistant）的 refs 快照中引用过该条文的次数，含已软删会话；未被引用为 0"
              placement="top"
            >
              <span class="th-help">AI 引用次数</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <span class="cell-num">{{ refCountText(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="150">
          <template #default="{ row }"><span class="mono muted">{{ formatDateTime(row.updatedAt) }}</span></template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-hasPermi="['biz:law:query']" type="primary" link @click="openView(row)">查看</el-button>
            <el-button v-if="canModify(row)" v-hasPermi="['biz:law:edit']" type="primary" link @click="openAmend(row)">
              修正
            </el-button>
            <el-button
              v-if="canModify(row)"
              v-hasPermi="['biz:law:disable']"
              type="warning"
              link
              :loading="actingId === String(row.id)"
              @click="disable(row)"
            >
              标记失效
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

    <!-- ==================== 查看详情 ==================== -->
    <el-dialog v-model="viewVisible" title="法条详情" width="760px" append-to-body>
      <div v-loading="viewLoading">
        <el-alert v-if="viewError" type="error" :closable="false" show-icon :title="viewError" />
        <template v-else-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="法律名称">{{ textOrDash(detail.lawName) }}</el-descriptions-item>
            <el-descriptions-item label="法律代码"><span class="mono">{{ textOrDash(detail.lawCode) }}</span></el-descriptions-item>
            <el-descriptions-item label="条号">{{ articleNoText(detail) }}</el-descriptions-item>
            <el-descriptions-item label="效力状态">{{ statusText(detail) }}</el-descriptions-item>
            <el-descriptions-item label="生效日期">{{ formatDate(detail.effectiveDate) }}</el-descriptions-item>
            <el-descriptions-item label="失效日期">{{ detail.expireDate ? formatDate(detail.expireDate) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="AI 引用次数">{{ refCountText(detail) }}</el-descriptions-item>
            <el-descriptions-item label="录入人类型">{{ textOrDash(detail.createdByType) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(detail.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item label="关键词" :span="2">{{ textOrDash(detail.keywords) }}</el-descriptions-item>
          </el-descriptions>
          <div class="detail-section">
            <div class="detail-section__title">条文全文</div>
            <div class="detail-content">{{ detail.content || '—' }}</div>
          </div>
          <p v-if="detail.status === 'amended'" class="dialog-hint">
            这条是被修正过的旧版本：失效日期之后已由新版本替代。历史 AI 回答仍引用本条快照，不会回改。
          </p>
        </template>
      </div>
      <template #footer>
        <el-button @click="viewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 修正（写新版本） ==================== -->
    <el-dialog v-model="amendVisible" title="修正法条（写新版本）" width="720px" append-to-body @closed="resetAmend">
      <div v-loading="amendLoading">
        <el-alert
          class="block-gap"
          type="warning"
          :closable="false"
          show-icon
          title="修正不会覆盖旧条文：旧版本会标记为「已修正」并失效，同时新增一条新版本。AI 历史回答的快照不回溯改写。"
        />
        <el-form ref="amendFormRef" :model="amendForm" :rules="amendRules" label-width="100px">
          <el-form-item label="法律">
            <span>{{ amendSource ? textOrDash(amendSource.lawName) : '—' }}</span>
            <span v-if="amendSource" class="mono muted">（{{ amendSource.lawCode }} · {{ articleNoText(amendSource) }}）</span>
          </el-form-item>
          <el-form-item label="修正生效日" prop="effectiveDate">
            <el-date-picker
              v-model="amendForm.effectiveDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="新版本的生效日期"
              :disabled-date="disabledAmendDate"
              style="width: 220px"
            />
            <span class="hint">必须晚于旧版本生效日期（{{ amendSource ? formatDate(amendSource.effectiveDate) : '—' }}）</span>
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="amendForm.keywords" maxlength="500" show-word-limit placeholder="检索用关键词，可选" />
          </el-form-item>
          <el-form-item label="条文全文" prop="content">
            <el-input
              v-model="amendForm.content"
              type="textarea"
              :rows="12"
              placeholder="修正后的条文原文（必填，写新版本用）"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="amendVisible = false">取消</el-button>
        <el-button type="primary" :loading="amendSaving" @click="submitAmend">提交修正</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 批量导入 ==================== -->
    <import-dialog v-model="importVisible" @success="handleImportSuccess" />
  </div>
</template>

<script setup>
/**
 * A-27 法条库页脚本。
 *
 * 副作用：挂载拉第一页；导入成功 / 修正成功 / 标记失效成功后重拉当前页。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { amendLawItem, disableLawItem, getLawItem, listLawItems } from '@/api/biz/ai/law-library'
import { formatDate, formatDateTime, textOrDash, describeRequestError } from '@/views/biz/shared/format'
import ImportDialog from './import-dialog.vue'

/**
 * 法律代码选项。
 *
 * 来源：`V302__import_law_items.sql` / `V304__import_law_items_batch2.sql` 里已声明的 law_code。
 * 为什么写死：契约里没有「列出所有法律」的接口（列表只按 lawCode 过滤），而设计稿的「法律」
 * 是下拉框。`allow-create` 让以后新增法律（如婚姻家庭编解释）可以直接输入代码查询，不用改代码。
 */
const LAW_CODE_OPTIONS = [
  { value: 'civil_code', label: '中华人民共和国民法典' },
  { value: 'civil_procedure', label: '中华人民共和国民事诉讼法' },
  { value: 'labor_contract', label: '中华人民共和国劳动合同法' },
  { value: 'labor_dispute', label: '中华人民共和国劳动争议调解仲裁法' },
  { value: 'consumer_protection', label: '中华人民共和国消费者权益保护法' },
  { value: 'marriage_judicial_1', label: '最高人民法院关于适用《民法典》婚姻家庭编的解释（一）' }
]

/** 效力状态文案。 */
const STATUS_TEXT = { active: '现行有效', amended: '已被修正', repealed: '已废止' }

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const actingId = ref('')

const query = reactive({
  lawCode: '',
  articleNo: '',
  kw: '',
  showDisabled: false,
  pageNum: 1,
  pageSize: 10
})

const viewVisible = ref(false)
const viewLoading = ref(false)
const viewError = ref('')
const detail = ref(null)

const amendVisible = ref(false)
const amendLoading = ref(false)
const amendSaving = ref(false)
const amendSource = ref(null)
const amendFormRef = ref(null)
const amendForm = reactive({ content: '', effectiveDate: '', keywords: '' })

const amendRules = {
  effectiveDate: [{ required: true, message: '请选择修正生效日', trigger: 'change' }],
  content: [{ required: true, message: '条文全文不能为空', trigger: 'blur' }]
}

const importVisible = ref(false)

/** 空表提示：区分「首次无数据」「筛选无结果」「已停用无结果」。 */
const emptyDescription = computed(() => {
  if (query.showDisabled) {
    return '没有已停用的法条'
  }
  const hasFilter = query.lawCode || query.articleNo || query.kw
  return hasFilter ? '当前筛选条件下没有法条' : '法条库还没有数据，可以先批量导入'
})

/** 条号展示：有数字序号时用「第 N 条」，否则用库里的条号文本。 */
function articleNoText(row) {
  if (!row) {
    return '—'
  }
  if (row.articleSeq !== null && row.articleSeq !== undefined && row.articleSeq !== '') {
    return `第 ${row.articleSeq} 条`
  }
  return textOrDash(row.articleNo)
}

/** AI 引用次数：后端未返回时按 0 展示（不前端重算）。 */
function refCountText(row) {
  if (!row) {
    return '0'
  }
  if (row.aiRefCount === null || row.aiRefCount === undefined || row.aiRefCount === '') {
    return '0'
  }
  return String(row.aiRefCount)
}

/** 效力状态文案（详情弹窗用；列表不设「复核状态」列）。 */
function statusText(row) {
  if (!row) {
    return '—'
  }
  if (row.isEnabled === 0 || row.isEnabled === false) {
    return '已停用'
  }
  return STATUS_TEXT[row.status] || textOrDash(row.status)
}

/** 生效版本列第二行：失效日期 / 停用提示。 */
function validityNote(row) {
  if (!row) {
    return ''
  }
  if (row.expireDate) {
    return `已于 ${formatDate(row.expireDate)} 失效`
  }
  if (row.isEnabled === 0 || row.isEnabled === false) {
    return '已停用'
  }
  return ''
}

/** 只有「现行有效且启用」的行能修正/标记失效（后端也会拒绝其他状态）。 */
function canModify(row) {
  return row && row.status === 'active' && (row.isEnabled === 1 || row.isEnabled === true)
}

/** 拉列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.lawCode) {
      params.lawCode = query.lawCode
    }
    if (query.articleNo) {
      params.articleNo = query.articleNo.trim()
    }
    if (query.kw) {
      params.kw = query.kw.trim()
    }
    // 显式传 0 才查停用行；不传 = 后端默认只看 is_enabled=1
    if (query.showDisabled) {
      params.isEnabled = 0
    }
    const data = await listLawItems(params)
    rows.value = data.rows || []
    total.value = data.total || 0
  } catch (e) {
    error.value = describeRequestError(e, '法条列表加载失败')
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
  query.lawCode = ''
  query.articleNo = ''
  query.kw = ''
  query.showDisabled = false
  query.pageNum = 1
  loadList()
}

/** 打开查看详情（拉详情拿全文）。 */
async function openView(row) {
  viewVisible.value = true
  viewLoading.value = true
  viewError.value = ''
  detail.value = null
  try {
    const res = await getLawItem(row.id)
    detail.value = res.data
  } catch (e) {
    viewError.value = describeRequestError(e, '法条详情加载失败')
  } finally {
    viewLoading.value = false
  }
}

/** 打开修正弹窗（先拉详情拿全文，避免拿列表摘要去覆盖全文）。 */
async function openAmend(row) {
  amendSource.value = row
  amendVisible.value = true
  amendLoading.value = true
  amendForm.content = ''
  amendForm.effectiveDate = ''
  amendForm.keywords = ''
  try {
    const res = await getLawItem(row.id)
    amendForm.content = (res.data && res.data.content) || ''
    amendForm.keywords = (res.data && res.data.keywords) || ''
  } catch (e) {
    ElMessage.error(describeRequestError(e, '法条详情加载失败，无法修正'))
    amendVisible.value = false
  } finally {
    amendLoading.value = false
  }
}

/**
 * Date 对象 → 本地 `YYYY-MM-DD`。
 *
 * 不能直接用 shared/format.js 的 formatDate：那个函数按「服务端字符串」解析，
 * 传 Date 会把 `Sat Nov 07 2026 ...` 原样吐回来，比较会失效。
 *
 * @param {Date} date 日期
 * @returns {string} 形如 `2026-12-01`
 */
function dateToText(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 修正生效日不能早于或等于旧版本生效日（后端同样校验，这里先拦一次少跑往返）。 */
function disabledAmendDate(date) {
  const oldDate = amendSource.value && amendSource.value.effectiveDate
  if (!oldDate || !date) {
    return false
  }
  return dateToText(date) <= oldDate
}

/** 提交修正。 */
async function submitAmend() {
  if (!amendFormRef.value) {
    return
  }
  try {
    await amendFormRef.value.validate()
  } catch {
    return
  }
  amendSaving.value = true
  try {
    const payload = {
      content: amendForm.content.trim(),
      effectiveDate: amendForm.effectiveDate
    }
    if (amendForm.keywords && amendForm.keywords.trim()) {
      payload.keywords = amendForm.keywords.trim()
    }
    await amendLawItem(amendSource.value.id, payload)
    ElMessage.success('已生成新版本：旧版本标记为「已修正」，新版本已生效')
    amendVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(describeRequestError(e, '修正失败'))
  } finally {
    amendSaving.value = false
  }
}

/** 关闭修正弹窗后清表单。 */
function resetAmend() {
  amendForm.content = ''
  amendForm.effectiveDate = ''
  amendForm.keywords = ''
  amendSource.value = null
}

/** 标记失效（不物理删除）。 */
function disable(row) {
  ElMessageBox.confirm(
    `标记失效后 AI 不再引用「${articleNoText(row)}」，列表默认不再显示该条。` +
      '法条记录本身保留、不物理删除，历史 AI 回答的引用快照也不会改写。确定标记失效？',
    '标记法条失效',
    { type: 'warning', confirmButtonText: '标记失效', cancelButtonText: '取消' }
  )
    .then(async () => {
      actingId.value = String(row.id)
      try {
        await disableLawItem(row.id)
        ElMessage.success('已标记失效')
        await loadList()
      } catch (e) {
        ElMessage.error(describeRequestError(e, '标记失效失败'))
      } finally {
        actingId.value = ''
      }
    })
    .catch(() => {})
}

/** 打开批量导入弹窗。 */
function openImport() {
  importVisible.value = true
}

/** 导入成功：刷新列表（导入后立刻可查，不做缓存/异步）。 */
function handleImportSuccess() {
  query.pageNum = 1
  loadList()
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
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.table-toolbar__left,
.table-toolbar__right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.table-toolbar__count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.table-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
.query-law {
  width: 260px;
}
.query-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cell-note {
  margin-top: 2px;
  color: var(--el-color-warning);
  font-size: 12px;
}
.cell-num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.th-help {
  border-bottom: 1px dashed var(--el-border-color);
  cursor: help;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.muted {
  color: var(--el-text-color-secondary);
}
.hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.detail-section {
  margin-top: 16px;
}
.detail-section__title {
  margin-bottom: 8px;
  font-weight: 600;
}
.detail-content {
  max-height: 320px;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
.dialog-hint {
  margin: 12px 0 0;
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
}
</style>
