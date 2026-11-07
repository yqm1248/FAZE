<!--
  职责：PC A-18 文书模板的新建/编辑页 —— 填元数据、编辑 HTML 与字段定义、保存（生成新版本行）、预览。
  调用方：RuoYi 动态路由（sys_menu 隐藏菜单，component = biz/document/template/edit，path = template/edit/:id）。
  数据来源：GET /api/v1/admin/doc-templates/{id}（回填）
            POST /api/v1/admin/doc-templates（新建）
            PUT /api/v1/admin/doc-templates/{id}（保存 = 生成新版本行）
            POST /api/v1/admin/doc-templates/{id}/preview（渲染预览 PNG）
  注意：
    1. **编辑器用 textarea 不引 monaco**（本段裁决）：monaco 在 Vite 下要配 worker，
       为本段一个页面引构建配置不划算；而 HTML 的合法性**由服务端校验**（well-formed、
       标签白名单、占位符与字段一一对应），前端不做解析，用哪套编辑器都不影响正确性。
       代价是没有语法高亮与自动补全——已记入段 4 待改进项。
    2. **保存后要用返回的新行 id 替换地址栏**：后端每次保存都 INSERT 一条 version+1 的新行，
       继续用旧 id 编辑会再生成一版，版本号越滚越大。
    3. `code` 创建后不可改：改 code 等于换了另一个模板，历史按 code 聚合的统计会断。
       编辑态下输入框禁用。
    4. 保存失败时**把服务端的 detail 原样显示**（它带行号/缺失的 key），不要只说「保存失败」。
-->
<template>
  <div class="app-container">
    <div class="page-head">
      <div>
        <h1 class="page-head__title">{{ isNew ? '新建文书模板' : '编辑文书模板' }}</h1>
        <p class="page-head__desc">
          保存会生成新版本，旧版本自动停用但保留——历史文书仍按生成时的版本渲染。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      :title="errorTitle"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="error = ''">关闭</el-button>
      </div>
    </el-alert>

    <el-row :gutter="16">
      <!-- ==================== 左：表单 + 编辑器 ==================== -->
      <el-col :span="14">
        <el-card shadow="never" class="block-gap">
          <template #header>基本信息</template>
          <el-form :model="form" label-width="96px">
            <el-form-item label="编码" required>
              <el-input v-model="form.code" :disabled="!isNew" placeholder="如 LAWSUIT-001" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="form.name" placeholder="如 民事起诉状" />
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="form.category" clearable placeholder="选择分类" style="width: 100%">
                <el-option v-for="c in CATEGORY_OPTIONS" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option label="草稿" value="draft" />
                <el-option label="已启用" value="active" />
                <el-option label="已停用" value="inactive" />
              </el-select>
              <div class="form-hint">只有「已启用」的模板会出现在 App 端</div>
            </el-form-item>
            <el-form-item label="排序">
              <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="block-gap">
          <template #header>
            <div class="card-header">
              <span>模板 HTML</span>
              <span class="card-header__hint">必须是 well-formed XHTML；禁用 flex/grid/script</span>
            </div>
          </template>
          <el-input
            v-model="form.templateHtml"
            type="textarea"
            :rows="22"
            class="code-input"
            placeholder="&lt;html&gt;&lt;head&gt;...&lt;/head&gt;&lt;body&gt;{{字段}}...&lt;/body&gt;&lt;/html&gt;"
          />
        </el-card>

        <el-card shadow="never" class="block-gap">
          <template #header>
            <div class="card-header">
              <span>字段定义（field_schema JSON）</span>
              <span class="card-header__hint">type 只允许 text/textarea/number/date/select/array</span>
            </div>
          </template>
          <el-input
            v-model="form.fieldSchema"
            type="textarea"
            :rows="12"
            class="code-input"
            placeholder='{"version":1,"fields":[{"key":"title","label":"标题","type":"text","required":true}]}'
          />
        </el-card>
      </el-col>

      <!-- ==================== 右：预览 ==================== -->
      <el-col :span="10">
        <el-card shadow="never" class="sticky-card">
          <template #header>
            <div class="card-header">
              <span>预览（真 PDF 转图）</span>
              <el-button
                type="primary"
                link
                :loading="previewing"
                :disabled="isNew"
                @click="handlePreview"
              >
                渲染预览
              </el-button>
            </div>
          </template>
          <div v-loading="previewing" class="preview-body">
            <img v-if="previewUrl" :src="previewUrl" class="preview-img" alt="模板预览" />
            <el-empty
              v-else
              :description="isNew ? '先保存，再用「渲染预览」看效果' : '点「渲染预览」生成预览图'"
            />
          </div>
          <p class="preview-hint">
            预览用的是示例数据（每个字段填「示例XX」），真实效果以用户填写的内容为准。
          </p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createDocTemplate,
  getDocTemplate,
  previewDocTemplate,
  updateDocTemplate
} from '@/api/biz/document/template'

/** 分类建议值（与导航树 L303 一致）。 */
const CATEGORY_OPTIONS = ['诉讼文书', '合同文书', '劳动文书', '跨境电商']

const route = useRoute()
const router = useRouter()

const saving = ref(false)
const loading = ref(false)
const error = ref('')
const errorTitle = ref('操作失败')
const previewing = ref(false)
const previewUrl = ref('')

const form = reactive({
  code: '',
  name: '',
  category: '',
  status: 'draft',
  sortOrder: 0,
  templateHtml: '',
  fieldSchema: '{\n  "version": 1,\n  "fields": []\n}'
})

/** 路由参数 id 为 `new` 时是新建态。 */
const isNew = computed(() => route.params.id === 'new')

const templateId = computed(() => (isNew.value ? null : route.params.id))

/** 回填详情。 */
async function loadDetail() {
  if (isNew.value) {
    return
  }
  loading.value = true
  try {
    const res = await getDocTemplate(templateId.value)
    const d = res.data || {}
    form.code = d.code || ''
    form.name = d.name || ''
    form.category = d.category || ''
    form.status = d.status || 'draft'
    form.sortOrder = d.sortOrder || 0
    form.templateHtml = d.templateHtml || ''
    form.fieldSchema = d.fieldSchema || form.fieldSchema
    previewUrl.value = d.previewUrl || ''
  } catch (e) {
    errorTitle.value = '加载模板失败'
    error.value = e?.message || '请求失败'
  } finally {
    loading.value = false
  }
}

/**
 * 保存。
 *
 * 新建走 POST、已有走 PUT（PUT 会生成新版本行）。保存成功后**用返回的新 id 替换地址栏**，
 * 否则下一次保存会基于旧版本再生成一版。
 */
async function handleSave() {
  error.value = ''
  const payload = {
    code: form.code.trim(),
    name: form.name.trim(),
    category: form.category || null,
    status: form.status,
    sortOrder: form.sortOrder ?? 0,
    templateHtml: form.templateHtml,
    fieldSchema: form.fieldSchema
  }
  saving.value = true
  try {
    if (isNew.value) {
      const res = await createDocTemplate(payload)
      ElMessage.success('已创建')
      router.replace(`/content/template/edit/${res.data}`)
    } else {
      const res = await updateDocTemplate(templateId.value, payload)
      ElMessage.success('已保存为新版本')
      router.replace(`/content/template/edit/${res.data}`)
      await loadDetail()
    }
  } catch (e) {
    errorTitle.value = '保存失败'
    // 服务端的 detail 带行号 / 缺失的 key，原样显示，别吞掉
    error.value = e?.message || '请求失败'
  } finally {
    saving.value = false
  }
}

/** 渲染并展示预览图。 */
async function handlePreview() {
  previewing.value = true
  previewUrl.value = ''
  try {
    const res = await previewDocTemplate(templateId.value)
    previewUrl.value = res.data?.url || ''
  } catch (e) {
    errorTitle.value = '预览失败'
    error.value = e?.message || '渲染失败'
  } finally {
    previewing.value = false
  }
}

/** 返回列表。 */
function goBack() {
  router.push('/content/template')
}

onMounted(loadDetail)
</script>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
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
}
.page-head__actions {
  display: flex;
  gap: 8px;
}
.block-gap {
  margin-bottom: 16px;
}
.error-body {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-header__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: normal;
}
.code-input :deep(textarea) {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}
.form-hint {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}
.sticky-card {
  position: sticky;
  top: 16px;
}
.preview-body {
  min-height: 240px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}
.preview-img {
  max-width: 100%;
  border: 1px solid var(--el-border-color);
}
.preview-hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}
</style>
