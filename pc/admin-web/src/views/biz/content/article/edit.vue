<!--
  职责：PC A-15 文章编辑 —— 源内容 + 藏/维/哈三语译文 tab、正文 JSON 块编辑、保存草稿、上架（G9.4）。
  调用方：RuoYi 动态路由（sys_menu 隐藏菜单，component = biz/content/article/edit；从 A-14 跳入）。
  数据来源：
    GET /api/v1/admin/articles/{id}   详情（源内容 + 已存在的译文）
    POST /api/v1/admin/articles       新建（保存为草稿）
    PUT  /api/v1/admin/articles/{id}  保存
    POST /api/v1/admin/articles/{id}/publish  上架（四语言齐全才通过）
  注意：
    1. **正文编辑器用 JSON 文本框（textarea）而不是富文本**：正文是「结构化块数组」，
       富文本会丢掉 `law_ref` 块的法条绑定（lawItemId）；工作流 G9.4「卡住怎么办」明确允许
       textarea 兜底、富文本增强挂账。字段旁给出块格式示例。
    2. **上架校验在服务端**：四语言（简体源 + 三译文）各自的标题与正文都齐全才让上架；
       不齐全时服务端返回缺失语言列表，页面原样弹出。页面不重复算一遍（避免两套规则漂移）。
    3. **已上架的文章不能直接保存**：服务端的保存接口只接受 draft / archived
       （上架状态必须走 publish 的校验）。页面据此禁用保存按钮并提示「先下架」。
    4. 语言 tab 固定三个（藏 / 维 / 哈）；每条译文可只填标题（草稿），上架时才要求齐全。
    5. 保存成功后：新建 → 跳到编辑态（带 id）；编辑 → 留在本页并刷新。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">{{ isEdit ? '编辑文章' : '新建文章' }}</h1>
        <p class="page-head__desc">
          保存为草稿不校验语言完整性；<b>上架</b>会校验简体 + 藏 / 维 / 哈的标题与正文都齐全。
          <span v-if="isPublished" class="warn">当前文章已上架，请先下架再编辑保存。</span>
        </p>
      </div>
      <div class="page-head__actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button :loading="saving" :disabled="isPublished" @click="handleSave">保存草稿</el-button>
        <el-button v-if="isEdit" type="success" :loading="publishing" :disabled="isPublished" @click="handlePublish">
          保存并上架
        </el-button>
      </div>
    </div>

    <el-alert v-if="error" class="block-gap" type="error" :closable="false" show-icon :title="error">
      <el-button type="primary" link @click="loadDetail">重试</el-button>
    </el-alert>

    <div v-loading="loading">
      <el-card class="block-gap" shadow="never">
        <template #header><span class="card-title">基本信息（简体源内容）</span></template>
        <el-form :model="form" label-width="96px">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="源语言标题" />
          </el-form-item>
          <el-form-item label="摘要">
            <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="列表展示用" />
          </el-form-item>
          <el-form-item label="分类">
            <el-input v-model="form.category" maxlength="30" placeholder="如：借款纠纷" style="width: 240px" />
          </el-form-item>
          <el-form-item label="封面 OSS key">
            <el-input v-model="form.coverOssKey" maxlength="500" placeholder="可选；私有桶 key" />
          </el-form-item>
          <el-form-item label="排序权重">
            <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
            <span class="hint">数值大的在前</span>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="block-gap" shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">正文（JSON 块数组）</span>
            <span class="hint">
              块类型：paragraph / heading / law_ref / list / image。
              法条引用块必须带 <span class="mono">lawItemId</span>（法条库主键），前端才能跳原文。
            </span>
          </div>
        </template>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="14"
          class="json-input"
          placeholder='例如：[{"type":"paragraph","text":"正文第一段"},{"type":"law_ref","lawName":"民法典","articleNo":"第六百八十条","lawItemId":4}]'
        />
        <div class="json-actions">
          <el-button link type="primary" @click="formatJson">校验并格式化 JSON</el-button>
          <span v-if="jsonError" class="json-error">{{ jsonError }}</span>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">多语言译文</span></template>
        <el-tabs v-model="activeLang">
          <el-tab-pane v-for="lang in TRANSLATION_LANGS" :key="lang.code" :label="lang.label" :name="lang.code">
            <el-form :model="translationOf(lang.code)" label-width="96px">
              <el-form-item label="译标题">
                <el-input v-model="translationOf(lang.code).title" maxlength="200" show-word-limit />
              </el-form-item>
              <el-form-item label="译摘要">
                <el-input v-model="translationOf(lang.code).summary" type="textarea" :rows="2" maxlength="500" show-word-limit />
              </el-form-item>
              <el-form-item label="译正文">
                <el-input
                  v-model="translationOf(lang.code).content"
                  type="textarea"
                  :rows="10"
                  class="json-input"
                  placeholder="与源正文结构一致的 JSON 块数组；可先留空（草稿）"
                />
              </el-form-item>
              <el-form-item label="译文状态">
                <el-select v-model="translationOf(lang.code).status" style="width: 160px">
                  <el-option label="草稿" value="draft" />
                  <el-option label="已校对" value="reviewed" />
                  <el-option label="已发布" value="published" />
                </el-select>
              </el-form-item>
              <el-form-item label="译者">
                <el-input v-model="translationOf(lang.code).translator" maxlength="100" placeholder="人工译者（留痕）" style="width: 240px" />
              </el-form-item>
              <el-form-item label="机翻引擎">
                <el-input v-model="translationOf(lang.code).translationEngine" maxlength="50" placeholder="机翻时填写引擎名" style="width: 240px" />
              </el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </div>
</template>

<script setup>
/**
 * 文章编辑页脚本。
 *
 * 副作用：有 id 时挂载拉详情；保存/上架成功后跳转或刷新。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createArticle, getArticle, publishArticle, updateArticle } from '@/api/biz/article'

/** 三种译文语言（顺序固定）。 */
const TRANSLATION_LANGS = [
  { code: 'bo', label: '藏文' },
  { code: 'ug-Arab-CN', label: '维吾尔文' },
  { code: 'kk-Arab-CN', label: '哈萨克文' }
]

const route = useRoute()
const router = useRouter()
const articleId = ref(String(route.query.id || ''))
const isEdit = computed(() => articleId.value !== '')
const isPublished = ref(false)

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const error = ref('')
const jsonError = ref('')
const activeLang = ref('bo')

const form = reactive({
  title: '',
  summary: '',
  content: '',
  category: '',
  coverOssKey: '',
  sortOrder: 0
})

/** 译文草稿：lang → 表单对象。 */
const translations = reactive({
  bo: emptyTranslation(),
  'ug-Arab-CN': emptyTranslation(),
  'kk-Arab-CN': emptyTranslation()
})

/** 空译文对象。 */
function emptyTranslation() {
  return { lang: '', title: '', summary: '', content: '', status: 'draft', translator: '', translationEngine: '' }
}

/**
 * 取某语言的译文表单（确保对象存在）。
 *
 * @param {string} code 语言码
 * @returns {object} 译文表单
 */
function translationOf(code) {
  if (!translations[code]) {
    translations[code] = emptyTranslation()
  }
  return translations[code]
}

/** 拉详情并回填。 */
async function loadDetail() {
  if (!isEdit.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const res = await getArticle(articleId.value)
    const data = res.data
    form.title = data.title || ''
    form.summary = data.summary || ''
    form.content = data.content || ''
    form.category = data.category || ''
    form.coverOssKey = data.coverOssKey || ''
    form.sortOrder = data.sortOrder || 0
    isPublished.value = data.status === 'published'
    ;(data.translations || []).forEach((item) => {
      if (TRANSLATION_LANGS.some((lang) => lang.code === item.lang)) {
        translations[item.lang] = {
          lang: item.lang,
          title: item.title || '',
          summary: item.summary || '',
          content: item.content || '',
          status: item.status || 'draft',
          translator: item.translator || '',
          translationEngine: item.translationEngine || ''
        }
      }
    })
  } catch (e) {
    error.value = e.message || '文章加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 组装提交体（只带填了内容的译文）。
 *
 * @returns {object} 保存入参
 */
function buildPayload() {
  const list = []
  TRANSLATION_LANGS.forEach((lang) => {
    const item = translations[lang.code]
    if (!item) {
      return
    }
    const hasContent = (item.title && item.title.trim()) || (item.content && item.content.trim())
    if (!hasContent) {
      return
    }
    list.push({
      lang: lang.code,
      title: item.title || undefined,
      summary: item.summary || undefined,
      content: item.content || undefined,
      status: item.status || 'draft',
      translator: item.translator || undefined,
      translationEngine: item.translationEngine || undefined
    })
  })
  return {
    title: form.title.trim(),
    summary: form.summary.trim() || undefined,
    content: form.content.trim(),
    category: form.category.trim() || undefined,
    coverOssKey: form.coverOssKey.trim() || undefined,
    sortOrder: form.sortOrder || 0,
    status: 'draft',
    translations: list
  }
}

/** 校验源正文是 JSON 数组。 */
function validateContent() {
  try {
    const parsed = JSON.parse(form.content)
    if (!Array.isArray(parsed)) {
      jsonError.value = '正文必须是 JSON 数组'
      return false
    }
    jsonError.value = ''
    return true
  } catch (e) {
    jsonError.value = '正文不是合法 JSON：' + e.message
    return false
  }
}

/** 校验并格式化正文 JSON。 */
function formatJson() {
  if (!validateContent()) {
    return
  }
  form.content = JSON.stringify(JSON.parse(form.content), null, 2)
  ElMessage.success('JSON 合法，已格式化')
}

/** 保存草稿。 */
async function handleSave() {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!validateContent()) {
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value) {
      await updateArticle(articleId.value, payload)
      ElMessage.success('已保存')
      await loadDetail()
    } else {
      const res = await createArticle(payload)
      articleId.value = String(res.data)
      isPublished.value = false
      ElMessage.success('已保存为草稿')
      router.replace({ path: '/content/article/edit', query: { id: articleId.value } })
      await loadDetail()
    }
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 保存并上架（服务端会校验四语言齐全）。 */
async function handlePublish() {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!validateContent()) {
    return
  }
  publishing.value = true
  try {
    await updateArticle(articleId.value, buildPayload())
    await publishArticle(articleId.value)
    ElMessage.success('已上架')
    await loadDetail()
  } catch (e) {
    // 上架失败最常见的原因是语言不齐全：服务端 msg 会列出缺哪种语言，原样提示
    ElMessage.error(e.message || '上架失败')
  } finally {
    publishing.value = false
  }
}

/** 返回列表。 */
function goBack() {
  router.push('/content/article')
}

onMounted(loadDetail)
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
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.card-title {
  font-weight: 600;
}
.hint {
  color: #6b7280;
  font-size: 12px;
  margin-left: 8px;
  line-height: 1.6;
}
.warn {
  color: #b45309;
}
.json-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}
.json-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}
.json-error {
  color: #dc2626;
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
