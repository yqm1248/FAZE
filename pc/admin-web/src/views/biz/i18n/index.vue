<!--
  A-17 多语言翻译管理（第 8 段 E-1）。

  职责：两件事合在一页——
    ① 左栏「译文队列」：跨文章聚合 article_translation，按 draft / reviewed / published
       推进译文阶段、编辑译标题/摘要/正文、登记译者与机翻引擎；
    ② 右栏「术语表」：维护 i18n_glossary 的 target_text（51 条法律术语，**只存人工翻译**）。

  调用方：RuoYi 菜单「内容管理 — 多语言翻译」（perms: biz:translation:list）。
  数据来源：/api/v1/admin/translations、/api/v1/admin/i18n-glossary（见 src/api/biz/i18n.js）。

  注意：
    1. **本页不提供任何自动/机器翻译入口**。设计稿画了「批量机翻」，但技术路线 §13.8
       与 i18n 规范都定了「术语翻译必须由母语法律译者确认、不许机翻」；
       译文表里的 translationEngine 字段只用于**记录**运营用过的引擎，不是给页面调用的能力。
       做假入口比不做更糟——运营会以为点一下就能出译文。
    2. 译文阶段（draft/reviewed/published）**不控制 App 可见性**：可见性看文章主表 status。
       页面提示条必须写这句，否则运营会以为推到 published 就上线了。
    3. 列表不带正文（几十 KB），编辑时单独拉详情。
    4. 写操作要权限 `biz:translation:edit`，按钮用 `v-hasPermi`；
       服务端所有 /admin/** 都要求管理端登录态，细粒度由 RuoYi 菜单权限控制。
    5. 视觉依据 design/admin/16-translation.html。**布局按它的"左宽右窄"**（左 1fr / 右 380px），
       与公共 .split 的默认列宽相反，所以在这里单独覆盖。
-->
<template>
  <div class="app-container trans-page">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">多语言翻译</h1>
        <p class="page-head__desc">
          文章译文按「草稿 / 已校对 / 已发布」三阶段推进；术语表只接受人工译者的译文。
          <strong>译文阶段不影响 App 可见性</strong>——文章上线与否看文章管理的状态。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadTranslations">刷新</el-button>
      </div>
    </div>

    <!-- ==================== 提示条 ==================== -->
    <el-alert
      class="block-gap"
      type="info"
      :closable="false"
      show-icon
      title="译文必须由人工确认后才算「已校对」；本页不提供机翻，机翻结果的引擎名请在编辑里登记，便于追溯。"
    />

    <!-- ==================== 分栏：左宽右窄 ==================== -->
    <div class="trans">
      <!-- ── 左栏：译文队列 ────────────────────────────── -->
      <el-card shadow="never" class="trans__left">
        <template #header>
          <div class="panel-head">
            <span class="panel-head__title">译文队列</span>
            <span class="panel-head__count">共 {{ translations.total }} 条</span>
          </div>
        </template>

        <div class="chips">
          <el-radio-group v-model="transQuery.lang" size="small" @change="handleTransFilter">
            <el-radio-button label="">全部语言</el-radio-button>
            <el-radio-button label="bo">藏文</el-radio-button>
            <el-radio-button label="ug-Arab-CN">维吾尔文</el-radio-button>
            <el-radio-button label="kk-Arab-CN">哈萨克文</el-radio-button>
          </el-radio-group>
          <el-radio-group v-model="transQuery.status" size="small" @change="handleTransFilter">
            <el-radio-button label="">全部阶段</el-radio-button>
            <el-radio-button label="draft">草稿</el-radio-button>
            <el-radio-button label="reviewed">已校对</el-radio-button>
            <el-radio-button label="published">已发布</el-radio-button>
          </el-radio-group>
        </div>

        <el-alert
          v-if="transError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          title="译文队列加载失败"
        >
          <div class="error-body">
            <span>{{ transError }}</span>
            <el-button type="primary" link @click="loadTranslations">重试</el-button>
          </div>
        </el-alert>

        <el-table v-loading="loading" :data="translations.rows" row-key="id" stripe style="width: 100%">
          <template #empty>
            <el-empty
              :description="transError ? '加载失败，请点上方「重试」' : '没有符合条件的译文'"
              :image-size="72"
            />
          </template>
          <el-table-column label="文章" min-width="200">
            <template #default="{ row }">
              <div class="cell-title">{{ row.articleTitle || '（文章已删除）' }}</div>
              <div class="cell-sub" v-if="row.title">{{ row.title }}</div>
            </template>
          </el-table-column>
          <el-table-column label="语言" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ langLabel(row.lang) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="阶段" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="留痕" width="140">
            <template #default="{ row }">
              <span class="cell-sub">{{ row.translator || row.translationEngine || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="150">
            <template #default="{ row }">
              <span class="cell-sub">{{ formatTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button
                v-hasPermi="['biz:translation:edit']"
                type="primary"
                link
                @click="openTranslation(row.id)"
              >
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="transQuery.pageNum"
            v-model:page-size="transQuery.pageSize"
            :total="translations.total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            small
            @current-change="loadTranslations"
            @size-change="handleTransFilter"
          />
        </div>
      </el-card>

      <!-- ── 右栏：术语表 ──────────────────────────────── -->
      <el-card shadow="never" class="trans__right">
        <template #header>
          <div class="panel-head">
            <span class="panel-head__title">术语表</span>
            <span class="panel-head__count">{{ glossary.total }} 条</span>
          </div>
        </template>

        <div class="glossary-tools">
          <el-input
            v-model="glossaryQuery.keyword"
            placeholder="搜索术语键 / 中文术语"
            clearable
            size="small"
            @keyup.enter="loadGlossary"
            @clear="loadGlossary"
          />
          <el-checkbox v-model="glossaryQuery.pendingOnly" size="small" @change="handleGlossaryFilter">
            只看待填
          </el-checkbox>
        </div>

        <el-alert
          v-if="glossaryError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          title="术语表加载失败"
        >
          <div class="error-body">
            <span>{{ glossaryError }}</span>
            <el-button type="primary" link @click="loadGlossary">重试</el-button>
          </div>
        </el-alert>

        <div v-loading="glossaryLoading" class="term-list">
          <el-empty
            v-if="!glossary.rows.length"
            :description="glossaryError ? '加载失败，请点上方「重试」' : '没有符合条件的术语'"
            :image-size="64"
          />
          <div v-for="row in glossary.rows" :key="row.id" class="term">
            <div class="term__head">
              <span class="term__source">{{ row.sourceText }}</span>
              <el-tag v-if="!row.targetText" size="small" type="warning" effect="plain">待填</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">已填</el-tag>
            </div>
            <div class="term__key mono">{{ row.termKey }}</div>
            <div class="term__target" :class="{ 'term__target--empty': !row.targetText }">
              {{ row.targetText || '（还没有译文）' }}
            </div>
            <el-button
              v-hasPermi="['biz:translation:edit']"
              type="primary"
              link
              size="small"
              @click="openGlossary(row)"
            >
              填写译文
            </el-button>
          </div>
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="glossaryQuery.pageNum"
            :page-size="glossaryQuery.pageSize"
            :total="glossary.total"
            layout="prev, pager, next"
            small
            @current-change="loadGlossary"
          />
        </div>
      </el-card>
    </div>

    <!-- ==================== 译文编辑弹窗 ==================== -->
    <el-dialog v-model="transDialogVisible" title="编辑译文" width="640px" append-to-body>
      <div v-loading="detailLoading">
        <el-form label-width="88px" label-position="left">
          <el-form-item label="所属文章">
            <span>{{ detail.articleTitle || '—' }}</span>
            <el-tag v-if="detail.outdated" size="small" type="warning" class="ml-8">
              源文已更新，译文可能过期
            </el-tag>
          </el-form-item>
          <el-form-item label="语言">
            <el-tag size="small" effect="plain">{{ langLabel(detail.lang) }}</el-tag>
          </el-form-item>
          <el-form-item label="译标题">
            <el-input v-model="editForm.title" maxlength="200" show-word-limit placeholder="译文标题" />
          </el-form-item>
          <el-form-item label="译摘要">
            <el-input
              v-model="editForm.summary"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="译文摘要（可空）"
            />
          </el-form-item>
          <el-form-item label="译正文">
            <el-input
              v-model="editForm.content"
              type="textarea"
              :rows="8"
              placeholder="JSON 块数组（结构必须与中文源文一致）；留空表示暂不录"
            />
          </el-form-item>
          <el-form-item label="阶段">
            <el-radio-group v-model="editForm.status">
              <el-radio label="draft">草稿</el-radio>
              <el-radio label="reviewed">已校对</el-radio>
              <el-radio label="published">已发布</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="译者">
            <el-input v-model="editForm.translator" maxlength="100" placeholder="人工译者（可空）" />
          </el-form-item>
          <el-form-item label="机翻引擎">
            <el-input
              v-model="editForm.translationEngine"
              maxlength="50"
              placeholder="用过的机翻引擎名（仅留痕，可空）"
            />
          </el-form-item>
        </el-form>
        <el-alert
          v-if="editError"
          type="error"
          :closable="false"
          show-icon
          :title="editError"
        />
      </div>
      <template #footer>
        <el-button @click="transDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTranslation">保存</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 术语编辑弹窗 ==================== -->
    <el-dialog v-model="glossaryDialogVisible" title="填写术语译文" width="520px" append-to-body>
      <el-form label-width="88px" label-position="left">
        <el-form-item label="中文术语">
          <span>{{ glossaryForm.sourceText }}</span>
        </el-form-item>
        <el-form-item label="术语键">
          <span class="mono">{{ glossaryForm.termKey }}</span>
        </el-form-item>
        <el-form-item label="译文">
          <el-input
            v-model="glossaryForm.targetText"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="母语法律译者的译文；留空表示退回待填"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="glossaryForm.note" maxlength="500" placeholder="词义辨析 / 适用范围（可空）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="glossaryForm.status">
            <el-radio label="pending">待确认</el-radio>
            <el-radio label="confirmed">已确认</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="glossaryEditError"
        type="error"
        :closable="false"
        show-icon
        :title="glossaryEditError"
      />
      <template #footer>
        <el-button @click="glossaryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="glossarySaving" @click="saveGlossary">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="BizI18nTranslation">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getTranslation,
  listGlossary,
  listTranslations,
  updateGlossary,
  updateTranslation
} from '@/api/biz/i18n'
import { formatDateTime } from '@/views/biz/shared/format'

/** 译文队列。 */
const translations = reactive({ rows: [], total: 0 })
/** 术语表。 */
const glossary = reactive({ rows: [], total: 0 })

/** 译文查询条件。 */
const transQuery = reactive({ lang: '', status: '', keyword: '', pageNum: 1, pageSize: 10 })
/** 术语查询条件（语言固定 zh-Hans：目前只有中文源术语）。 */
const glossaryQuery = reactive({ lang: 'zh-Hans', keyword: '', pendingOnly: false, pageNum: 1, pageSize: 20 })

const loading = ref(false)
const glossaryLoading = ref(false)
const transError = ref('')
const glossaryError = ref('')

/** 译文详情 + 编辑表单。 */
const transDialogVisible = ref(false)
const detailLoading = ref(false)
const detail = reactive({})
const editForm = reactive({
  title: '',
  summary: '',
  content: '',
  status: 'draft',
  translator: '',
  translationEngine: ''
})
const editingId = ref(null)
const saving = ref(false)
const editError = ref('')

/** 术语编辑表单。 */
const glossaryDialogVisible = ref(false)
const glossaryForm = reactive({
  id: null,
  termKey: '',
  sourceText: '',
  targetText: '',
  note: '',
  status: 'pending'
})
const glossarySaving = ref(false)
const glossaryEditError = ref('')

/** 语言码 → 中文名。 */
const LANG_NAMES = {
  bo: '藏文',
  'ug-Arab-CN': '维吾尔文',
  'kk-Arab-CN': '哈萨克文',
  'zh-Hans': '简体中文'
}

/**
 * 语言码转显示名。
 *
 * @param {string} lang 语言码
 * @returns {string} 中文名（未知码原样返回）
 */
function langLabel(lang) {
  return LANG_NAMES[lang] || lang || '—'
}

/**
 * 译文阶段转显示名。
 *
 * @param {string} status draft / reviewed / published
 * @returns {string} 中文名
 */
function statusLabel(status) {
  return { draft: '草稿', reviewed: '已校对', published: '已发布' }[status] || status || '—'
}

/**
 * 译文阶段转标签色。
 *
 * @param {string} status 阶段
 * @returns {string} Element Plus 的 tag type
 */
function statusTagType(status) {
  return { draft: 'info', reviewed: 'warning', published: 'success' }[status] || 'info'
}

/**
 * 时间显示（服务端给 UTC 裸串，这里只做可读化，不换算时区——PC 与服务器同区）。
 *
 * @param {string} value 时间串
 * @returns {string} 形如 2026-11-13 10:20
 */
const formatTime = formatDateTime

/** 加载译文队列。 */
async function loadTranslations() {
  loading.value = true
  transError.value = ''
  try {
    const res = await listTranslations({
      lang: transQuery.lang || undefined,
      status: transQuery.status || undefined,
      keyword: transQuery.keyword || undefined,
      pageNum: transQuery.pageNum,
      pageSize: transQuery.pageSize
    })
    translations.rows = res.rows || []
    translations.total = res.total || 0
  } catch (e) {
    transError.value = e?.message || '请求失败'
    translations.rows = []
    translations.total = 0
  } finally {
    loading.value = false
  }
}

/** 筛选变化后回到第一页。 */
function handleTransFilter() {
  transQuery.pageNum = 1
  loadTranslations()
}

/** 加载术语表。 */
async function loadGlossary() {
  glossaryLoading.value = true
  glossaryError.value = ''
  try {
    const res = await listGlossary({
      lang: glossaryQuery.lang,
      keyword: glossaryQuery.keyword || undefined,
      pendingOnly: glossaryQuery.pendingOnly || undefined,
      pageNum: glossaryQuery.pageNum,
      pageSize: glossaryQuery.pageSize
    })
    glossary.rows = res.rows || []
    glossary.total = res.total || 0
  } catch (e) {
    glossaryError.value = e?.message || '请求失败'
    glossary.rows = []
    glossary.total = 0
  } finally {
    glossaryLoading.value = false
  }
}

/** 术语筛选变化后回到第一页。 */
function handleGlossaryFilter() {
  glossaryQuery.pageNum = 1
  loadGlossary()
}

/**
 * 打开译文编辑（先拉详情拿正文）。
 *
 * @param {string|number} id 译文主键
 */
async function openTranslation(id) {
  editingId.value = id
  editError.value = ''
  transDialogVisible.value = true
  detailLoading.value = true
  Object.keys(detail).forEach((k) => delete detail[k])
  try {
    const res = await getTranslation(id)
    const data = res.data || {}
    Object.assign(detail, data)
    editForm.title = data.title || ''
    editForm.summary = data.summary || ''
    editForm.content = data.content || ''
    editForm.status = data.status || 'draft'
    editForm.translator = data.translator || ''
    editForm.translationEngine = data.translationEngine || ''
  } catch (e) {
    editError.value = e?.message || '详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

/** 保存译文。 */
async function saveTranslation() {
  if (editingId.value == null) {
    return
  }
  saving.value = true
  editError.value = ''
  try {
    await updateTranslation(editingId.value, {
      title: editForm.title,
      summary: editForm.summary,
      content: editForm.content,
      status: editForm.status,
      translator: editForm.translator || null,
      translationEngine: editForm.translationEngine || null
    })
    ElMessage.success('译文已保存')
    transDialogVisible.value = false
    loadTranslations()
  } catch (e) {
    editError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

/**
 * 打开术语编辑。
 *
 * @param {object} row 术语行
 */
function openGlossary(row) {
  glossaryEditError.value = ''
  glossaryForm.id = row.id
  glossaryForm.termKey = row.termKey
  glossaryForm.sourceText = row.sourceText
  glossaryForm.targetText = row.targetText || ''
  glossaryForm.note = row.note || ''
  glossaryForm.status = row.status || 'pending'
  glossaryDialogVisible.value = true
}

/** 保存术语译文。 */
async function saveGlossary() {
  if (glossaryForm.id == null) {
    return
  }
  glossarySaving.value = true
  glossaryEditError.value = ''
  try {
    await updateGlossary(glossaryForm.id, {
      // 空串 = 清空译文（合法操作）；这里不做 trim，译者可能刻意留空格分隔
      targetText: glossaryForm.targetText,
      note: glossaryForm.note || null,
      status: glossaryForm.status
    })
    ElMessage.success('术语已保存')
    glossaryDialogVisible.value = false
    loadGlossary()
  } catch (e) {
    glossaryEditError.value = e?.message || '保存失败'
  } finally {
    glossarySaving.value = false
  }
}

onMounted(() => {
  loadTranslations()
  loadGlossary()
})
</script>

<style scoped>
.trans-page {
  display: flex;
  flex-direction: column;
}

.page-head__title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.page-head__desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.block-gap {
  margin-bottom: 12px;
}

/* 左宽右窄：与设计稿 16-translation.html 一致，覆盖公共分栏默认值 */
.trans {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 12px;
  align-items: start;
}

.trans__left :deep(.el-card__body),
.trans__right :deep(.el-card__body) {
  padding: 12px;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.panel-head__title {
  font-weight: 600;
}

.panel-head__count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.cell-title {
  font-size: 13px;
  line-height: 1.5;
}

.cell-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.ml-8 {
  margin-left: 8px;
}

.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.glossary-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.term-list {
  max-height: 520px;
  overflow-y: auto;
}

.term {
  padding: 10px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.term:last-child {
  border-bottom: 0;
}

.term__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.term__source {
  font-weight: 600;
  font-size: 14px;
}

.term__key {
  color: var(--el-text-color-placeholder);
  margin: 2px 0 4px;
}

.term__target {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.term__target--empty {
  color: var(--el-text-color-placeholder);
}
</style>
