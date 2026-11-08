<!--
  A-16 案例库（第 5 段 G1 + G4）
  职责：案例列表 / 新建 / 编辑（四语言 Tab + 三个内容 Tab）/ 上线门禁 / 下架 / 批量下架。
  调用方：RuoYi 菜单「法泽业务 → 案例库」（menu_id 2223）。
  数据来源：`/api/v1/admin/case-library/**` + `/api/v1/pub/law-items`（法条检索）。
  注意：
    1. **大类由细分推导**：表单里只选 %E7%BB%86%E5%88%86%E5%88%86%E7%B1%BB，大类由服务端算，页面不填也不显示可编辑项。
    2. **上线门禁**：源语言四块正文齐全 + 至少一条法条引用；不满足时服务端返回 409006 / 409005。
    3. **四语言 Tab**：切换时标题与四块正文一起切（zh-Hans 读主字段，其余读 translations）。
    4. 法条引用只在 zh-Hans Tab 下编辑（译文不重复维护引用）。
    5. **不显示法院案号 / 法院全名的对外约束**：详情页（APP）不展示；本页是后台，可以看到。
    6. 批量导出 V1 不做：按钮置灰并提示。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">案例库</h1>
        <p class="page-head__desc">
          案例为公开内容，上线前必须有源语言四块正文与至少一条法条引用；译文可以缺，但要登记为待译。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadList">刷新</el-button>
        <el-button :disabled="selectedIds.length === 0" @click="handleBatchOffline">
          批量下架（{{ selectedIds.length }}）
        </el-button>
        <el-button disabled title="V1 不做批量导出">导出</el-button>
        <el-button type="primary" @click="openCreate">新建案例</el-button>
      </div>
    </div>

    <el-alert v-if="error" class="block-gap" type="error" :closable="false" show-icon title="案例列表加载失败">
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="80px" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.kw"
            placeholder="标题 / 当事人 / 编号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="细分分类">
          <el-select v-model="query.category" clearable placeholder="全部" style="width: 150px" @change="handleSearch">
            <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="案例编号" width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.caseNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题 / 当事人" min-width="240">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-strong">{{ textOrDash(row.title) }}</span>
              <span class="cell-sub">{{ partyText(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="120">
          <template #default="{ row }">
            <div class="cell-stack">
              <span>{{ row.categoryLabel }}</span>
              <span class="cell-sub">{{ row.caseTypeLabel }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="适用法条" min-width="200">
          <template #default="{ row }">{{ textOrDash(row.lawRefText) }}</template>
        </el-table-column>
        <el-table-column label="语言" width="90" align="center">
          <template #default="{ row }">
            <span :class="row.langCount >= 4 ? 'lang-ok' : 'lang-warn'">{{ row.langCount }}/4</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status !== 'published'" type="success" link @click="handlePublish(row)">
              上线
            </el-button>
            <el-button v-if="row.status === 'published'" type="warning" link @click="handleOffline(row)">
              下架
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有案例" />
        </template>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadList"
          @size-change="handleSearch"
        />
      </div>
    </el-card>

    <!-- 录入 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="form.id ? '编辑案例' : '新建案例'" size="860px">
      <el-tabs v-model="langTab" type="border-card">
        <el-tab-pane v-for="lang in LANG_OPTIONS" :key="lang.value" :label="lang.label" :name="lang.value">
          <el-form :model="form" label-width="96px">
            <el-form-item label="标题" required>
              <el-input v-model="langModel(lang.value).title" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="案情简介">
              <el-input v-model="langModel(lang.value).summary" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="争议焦点">
              <el-input v-model="langModel(lang.value).disputeFocus" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="裁判结果">
              <el-input v-model="langModel(lang.value).judgment" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="案例启示">
              <el-input v-model="langModel(lang.value).insight" type="textarea" :rows="4" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <el-tabs v-model="contentTab" class="block-gap">
        <el-tab-pane label="基本信息" name="basic">
          <el-form :model="form" label-width="110px">
            <el-form-item label="细分分类" required>
              <el-select v-model="form.category" style="width: 220px">
                <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <span class="form-hint">大类由细分自动推导（{{ bigTypeText }}）</span>
            </el-form-item>
            <el-form-item label="原告（脱敏）">
              <el-input v-model="form.plaintiffName" maxlength="50" placeholder="如 张*" />
            </el-form-item>
            <el-form-item label="被告（脱敏）">
              <el-input v-model="form.defendantName" maxlength="50" placeholder="如 李*" />
            </el-form-item>
            <el-form-item label="关键词标签">
              <el-input v-model="form.keywords" maxlength="500" placeholder="逗号分隔" />
            </el-form-item>
            <el-form-item label="来源">
              <el-input v-model="form.source" maxlength="100" placeholder="默认「已脱敏改编」" />
            </el-form-item>
            <el-form-item label="裁判日期">
              <el-date-picker v-model="form.judgmentDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="案号（内部）">
              <el-input v-model="form.courtCaseNo" maxlength="64" />
              <span class="form-hint">仅后台可见，APP 不展示</span>
            </el-form-item>
            <el-form-item label="法院（内部）">
              <el-input v-model="form.courtName" maxlength="100" />
              <span class="form-hint">仅后台可见，APP 不展示全名</span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="法条引用" name="laws">
          <p class="form-hint block-gap">
            保存前服务端会逐条回查法条库，条号不存在会被拒绝。至少一条才能上线。
          </p>
          <el-select
            v-model="lawPickerValue"
            filterable
            remote
            clearable
            reserve-keyword
            placeholder="输入法律名或条号检索"
            :remote-method="searchLaws"
            :loading="lawSearching"
            style="width: 100%"
            @change="handleLawPick"
          >
            <el-option
              v-for="item in lawOptions"
              :key="item.id"
              :label="`${item.lawName} ${item.articleNo}`"
              :value="item.id"
            />
          </el-select>
          <el-table :data="form.lawRefs" class="block-gap" border size="small">
            <el-table-column label="法律" min-width="200">
              <template #default="{ row }">{{ row.lawName }}</template>
            </el-table-column>
            <el-table-column label="条号" width="160">
              <template #default="{ row }">{{ row.articleNo }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ $index }">
                <el-button type="danger" link @click="removeLawRef($index)">移除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有法条引用" />
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button :loading="saving" @click="submit(false)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="submit(true)">
          保存并上线
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
/*
 * A-16 案例库页逻辑。
 *
 * 副作用：列表查询、保存（新建/编辑）、上线、下架、批量下架、法条远程检索。
 * 注意：语言 Tab 与内容 Tab 是两组独立维度——语言切「标题+四块正文」，内容切「基本信息/法条」。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createCaseLibrary,
  getCaseLibraryDetail,
  listCaseLibrary,
  offlineCaseLibrary,
  offlineCaseLibraryBatch,
  publishCaseLibrary,
  searchLawItems,
  updateCaseLibrary
} from '@/api/biz/caseLibrary'
import { formatDateTime, textOrDash } from '@/views/biz/shared/format'

/** 细分分类（与后端 CaseCategory 一一对应；大类由服务端推导）。 */
const CATEGORY_OPTIONS = [
  { value: 'marriage', label: '婚姻家事', big: 'civil' },
  { value: 'labor', label: '劳动争议', big: 'civil' },
  { value: 'contract', label: '合同纠纷', big: 'civil' },
  { value: 'traffic', label: '交通事故', big: 'civil' },
  { value: 'criminal', label: '刑事', big: 'criminal' },
  { value: 'policy', label: '行政诉讼', big: 'administrative' }
]

/** 大类中文名（只用于表单提示）。 */
const BIG_TYPE_LABEL = { civil: '民事纠纷', administrative: '行政纠纷', criminal: '刑事纠纷' }

/** 状态选项。 */
const STATUS_OPTIONS = [
  { value: 'draft', label: '待审核' },
  { value: 'published', label: '已上线' },
  { value: 'offline', label: '已下架' }
]

/** 语言 Tab（zh-Hans 是源语言）。 */
const LANG_OPTIONS = [
  { value: 'zh-Hans', label: '简体中文' },
  { value: 'bo', label: '藏文' },
  { value: 'ug-Arab-CN', label: '维吾尔文' },
  { value: 'kk-Arab-CN', label: '哈萨克文' }
]

/** 列表状态。 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const selectedIds = ref([])

/** 查询条件。 */
const query = reactive({ kw: '', category: '', status: '', pageNum: 1, pageSize: 20 })

/** 抽屉状态。 */
const drawerVisible = ref(false)
const saving = ref(false)
const langTab = ref('zh-Hans')
const contentTab = ref('basic')
const lawPickerValue = ref('')
const lawOptions = ref([])
const lawSearching = ref(false)

/** 表单（源语言字段在顶层，译文在 translations）。 */
const form = reactive(blankForm())

/**
 * 空表单。
 *
 * @returns {object} 表单对象
 */
function blankForm() {
  return {
    id: null,
    title: '',
    category: '',
    plaintiffName: '',
    defendantName: '',
    keywords: '',
    source: '',
    courtCaseNo: '',
    courtName: '',
    judgmentDate: '',
    summary: '',
    disputeFocus: '',
    judgment: '',
    insight: '',
    lawRefs: [],
    translations: {
      bo: blankTranslation(),
      'ug-Arab-CN': blankTranslation(),
      'kk-Arab-CN': blankTranslation()
    }
  }
}

/**
 * 空译文。
 *
 * @returns {object} 译文对象
 */
function blankTranslation() {
  return { title: '', summary: '', disputeFocus: '', judgment: '', insight: '' }
}

/**
 * 取某个语言 Tab 对应的可编辑模型。
 *
 * @param {string} lang 语言
 * @returns {object} 该语言的可编辑对象（zh-Hans 返回 form 本身）
 */
function langModel(lang) {
  if (lang === 'zh-Hans') {
    return form
  }
  if (!form.translations[lang]) {
    form.translations[lang] = blankTranslation()
  }
  return form.translations[lang]
}

/** 大类提示文案。 */
const bigTypeText = computed(() => {
  const hit = CATEGORY_OPTIONS.find((item) => item.value === form.category)
  return hit ? BIG_TYPE_LABEL[hit.big] : '未选择分类'
})

/**
 * 状态中文名。
 *
 * @param {string} status 状态值
 * @returns {string} 中文名
 */
function statusLabel(status) {
  const hit = STATUS_OPTIONS.find((item) => item.value === status)
  return hit ? hit.label : status
}

/**
 * 状态 tag 颜色。
 *
 * @param {string} status 状态值
 * @returns {string} tag type
 */
function statusTagType(status) {
  if (status === 'published') return 'success'
  if (status === 'offline') return 'info'
  return 'warning'
}

/**
 * 当事人展示文案。
 *
 * @param {object} row 列表行
 * @returns {string} 形如「张* 诉 李*」
 */
function partyText(row) {
  if (!row.plaintiffName && !row.defendantName) {
    return '—'
  }
  return `${textOrDash(row.plaintiffName)} 诉 ${textOrDash(row.defendantName)}`
}

/** 加载列表。 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listCaseLibrary(query)
    rows.value = res.rows || []
    total.value = res.total || 0
  } catch (e) {
    error.value = e?.message || '请求失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询（回第 1 页）。 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/** 重置查询。 */
function handleReset() {
  query.kw = ''
  query.category = ''
  query.status = ''
  query.pageNum = 1
  loadList()
}

/**
 * 多选变化。
 *
 * @param {Array<object>} selection 选中行
 */
function handleSelectionChange(selection) {
  selectedIds.value = selection.map((item) => item.id)
}

/** 打开新建抽屉。 */
function openCreate() {
  Object.assign(form, blankForm())
  langTab.value = 'zh-Hans'
  contentTab.value = 'basic'
  drawerVisible.value = true
}

/**
 * 打开编辑抽屉。
 *
 * @param {object} row 列表行
 */
async function openEdit(row) {
  try {
    const res = await getCaseLibraryDetail(row.id)
    const data = res.data || {}
    Object.assign(form, blankForm(), {
      id: data.id,
      title: data.title || '',
      category: data.category || '',
      plaintiffName: data.plaintiffName || '',
      defendantName: data.defendantName || '',
      keywords: data.keywords || '',
      source: data.source || '',
      courtCaseNo: data.courtCaseNo || '',
      courtName: data.courtName || '',
      judgmentDate: data.judgmentDate || '',
      summary: data.summary || '',
      disputeFocus: data.disputeFocus || '',
      judgment: data.judgment || '',
      insight: data.insight || '',
      lawRefs: (data.lawRefs || []).map((item) => ({ ...item }))
    })
    for (const item of data.translations || []) {
      if (form.translations[item.lang]) {
        form.translations[item.lang] = {
          title: item.title || '',
          summary: item.summary || '',
          disputeFocus: item.disputeFocus || '',
          judgment: item.judgment || '',
          insight: item.insight || ''
        }
      }
    }
    langTab.value = 'zh-Hans'
    contentTab.value = 'basic'
    drawerVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '详情加载失败')
  }
}

/**
 * 远程检索法条。
 *
 * @param {string} kw 关键词
 */
async function searchLaws(kw) {
  if (!kw) {
    lawOptions.value = []
    return
  }
  lawSearching.value = true
  try {
    const res = await searchLawItems(kw)
    lawOptions.value = res.data?.list || []
  } catch (e) {
    lawOptions.value = []
  } finally {
    lawSearching.value = false
  }
}

/**
 * 选中一条法条加入引用。
 *
 * @param {string} lawItemId 法条主键
 */
function handleLawPick(lawItemId) {
  const hit = lawOptions.value.find((item) => item.id === lawItemId)
  if (!hit) {
    return
  }
  const exists = form.lawRefs.some((item) => item.lawItemId === hit.id)
  if (exists) {
    ElMessage.warning('该法条已添加')
    return
  }
  form.lawRefs.push({
    lawName: hit.lawName,
    articleNo: hit.articleNo,
    lawItemId: hit.id
  })
  lawPickerValue.value = ''
}

/**
 * 移除一条法条引用。
 *
 * @param {number} index 下标
 */
function removeLawRef(index) {
  form.lawRefs.splice(index, 1)
}

/**
 * 组装保存入参。
 *
 * @returns {object} 入参
 */
function buildPayload() {
  const translations = []
  for (const item of LANG_OPTIONS) {
    if (item.value === 'zh-Hans') {
      continue
    }
    const model = form.translations[item.value]
    const hasContent = model && (model.title || model.summary || model.disputeFocus
      || model.judgment || model.insight)
    if (hasContent) {
      translations.push({ lang: item.value, ...model })
    }
  }
  return {
    title: form.title,
    category: form.category,
    plaintiffName: form.plaintiffName || null,
    defendantName: form.defendantName || null,
    keywords: form.keywords || null,
    source: form.source || null,
    courtCaseNo: form.courtCaseNo || null,
    courtName: form.courtName || null,
    judgmentDate: form.judgmentDate || null,
    summary: form.summary || null,
    disputeFocus: form.disputeFocus || null,
    judgment: form.judgment || null,
    insight: form.insight || null,
    lawRefs: form.lawRefs,
    translations
  }
}

/**
 * 保存（可选保存后上线）。
 *
 * @param {boolean} publishNow 是否保存后立即上线
 */
async function submit(publishNow) {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!form.category) {
    ElMessage.warning('请选择细分分类')
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    let id = form.id
    if (id === null) {
      const created = await createCaseLibrary(payload)
      id = created.data?.id
      form.id = id
    } else {
      await updateCaseLibrary(id, payload)
    }
    if (publishNow) {
      await publishCaseLibrary(id)
      ElMessage.success('已保存并上线')
    } else {
      ElMessage.success('已保存')
    }
    drawerVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * 上线。
 *
 * @param {object} row 列表行
 */
async function handlePublish(row) {
  try {
    await publishCaseLibrary(row.id)
    ElMessage.success('已上线')
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '上线失败')
  }
}

/**
 * 下架。
 *
 * @param {object} row 列表行
 */
async function handleOffline(row) {
  try {
    await ElMessageBox.confirm('下架后 APP 端立即不可见，可重新编辑后再上线。', '下架案例',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await offlineCaseLibrary(row.id)
    ElMessage.success('已下架')
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '下架失败')
  }
}

/** 批量下架。 */
async function handleBatchOffline() {
  try {
    await ElMessageBox.confirm(`确认下架选中的 ${selectedIds.value.length} 条案例？`, '批量下架',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' })
  } catch (e) {
    return
  }
  try {
    const res = await offlineCaseLibraryBatch(selectedIds.value)
    ElMessage.success(`已下架 ${res.data?.rows ?? 0} 条`)
    loadList()
  } catch (e) {
    ElMessage.error(e?.message || '批量下架失败')
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
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.page-head__desc {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.page-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.block-gap {
  margin-bottom: 16px;
}

.cell-stack {
  display: flex;
  flex-direction: column;
  line-height: 1.5;
}

.cell-strong {
  font-weight: 500;
}

.cell-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mono {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}

.lang-ok {
  color: var(--el-color-success);
}

.lang-warn {
  color: var(--el-color-warning);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
