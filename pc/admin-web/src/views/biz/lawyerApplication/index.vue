<!--
  职责：PC A-05 律师入驻审核 —— 申请队列分页、详情抽屉（个人资质 / 执业信息 / 擅长领域 / 服务说明
        四块 + 审核流水时间线）、通过 / 驳回（驳回必填理由）。
  调用方：RuoYi 动态路由（sys_menu 2104，component = biz/lawyerApplication/index，route name = BizLawyerApplication）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET  /api/v1/admin/lawyer-applications?status&beginTime&endTime&pageNum&pageSize  列表（顶层 rows/total）
    GET  /api/v1/admin/lawyer-applications/{id}                                       详情（R，含 form / logs）
    POST /api/v1/admin/lawyer-applications/{id}/audit  body {action, reason}           审核（R<{id,status}>）
  注意：
    1. **契约里 status 传空 = 只看 PENDING**（draft / pending 都不进待审队列），
       所以本页不提供「全部状态」选项——传空不等于全部，这是契约的限制，不是页面漏做。
       已经实现的 `AdminLawyerApplicationQueryBO` 也是同样口径。
    2. `applyType` 查询参数本段无对应字段，**不传**。
    3. `practiceYears` 服务端恒返 null（lawyer_application 无该列，段 2 补），
       页面显示「段 2 补」而不是 0 或猜测值。
    4. `form.licenseImageUrl` 是 60 秒签名 URL，只在打开抽屉时取；null 表示未上传。
    5. `logs` 由服务端按时间**正序**返回，直接按顺序渲染时间线即可，页面不再排序。
    6. approve 的副作用（写 lawyer 表、改 app_user.role='lawyer'）在服务端完成；
       前端只发意图，不自己拼状态。
    7. 四态：加载中 / 空 / 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">律师入驻审核</h1>
        <p class="page-head__desc">
          律师提交四步资料后进入本队列。通过后服务端会写入律师档案并把用户角色改为律师；驳回必须填写理由。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </div>

    <!-- ==================== 错误态（常驻 + 可重试） ==================== -->
    <el-alert
      v-if="error"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="入驻申请队列加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="申请状态">
          <el-select v-model="query.status" style="width: 140px" @change="handleSearch">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="提交时间">
          <el-date-picker
            v-model="timeRange"
            type="daterange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :default-time="defaultRangeTime"
            range-separator="至"
            start-placeholder="起始"
            end-placeholder="截止"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="query-hint">
        说明：不选状态时默认只显示待审核的申请；查历史请选「已通过」或「已驳回」。
      </div>
    </el-card>

    <!-- ==================== 队列表格 ==================== -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__count">共 {{ total }} 条</span>
        <el-button :loading="loading" link @click="loadList">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '队列加载失败，请点上方「重试」' : '当前筛选条件下没有入驻申请'"
            :image-size="72"
          />
        </template>
        <el-table-column label="申请时间" min-width="120">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.submittedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="申请人" min-width="130">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">{{ textOrDash(row.applicantName) }}</span>
              <span class="cell-stack__sub mono">用户 ID {{ row.applicantUserId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" min-width="100">
          <template #default="{ row }">
            <span class="mono">{{ textOrDash(row.phoneMasked) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="执业证号" min-width="120">
          <template #default="{ row }">
            <span class="mono">{{ textOrDash(row.licenseNoMasked) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="律所" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ textOrDash(row.lawFirm) }}</template>
        </el-table-column>
        <el-table-column label="执业年限" min-width="80">
          <template #default="{ row }">
            <span>{{ row.practiceYears === null || row.practiceYears === undefined ? '段 2 补' : `${row.practiceYears} 年` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核时间" min-width="120">
          <template #default="{ row }">
            <span class="mono muted">{{ row.reviewedAt ? formatDateTime(row.reviewedAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">
              {{ row.status === 'PENDING' ? '审核' : '查看' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        class="pagination"
        background
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="loadList"
      />
    </el-card>

    <!-- ==================== 审核抽屉 ==================== -->
    <el-drawer
      v-model="drawerVisible"
      :title="drawerTitle"
      size="720px"
      :close-on-click-modal="false"
      @closed="handleDrawerClosed"
    >
      <div v-loading="detailLoading">
        <el-alert
          v-if="detailError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          title="入驻申请详情加载失败"
        >
          <div class="error-body">
            <span>{{ detailError }}</span>
            <el-button type="primary" link @click="loadDetail">重试</el-button>
          </div>
        </el-alert>
        <el-alert
          v-if="actionError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          :title="actionError"
        />

        <template v-if="detail">
          <div class="status-line">
            <el-tag :type="statusTagType(detail.status)" effect="light" size="small">{{ statusText(detail.status) }}</el-tag>
            <span class="status-line__time">提交于 {{ formatDateTime(detail.submittedAt) }}</span>
            <span v-if="detail.reviewedAt" class="status-line__time">· 审核于 {{ formatDateTime(detail.reviewedAt) }}</span>
          </div>

          <el-alert
            v-if="detail.status === 'REJECTED' && detail.rejectReason"
            class="block-gap"
            type="warning"
            :closable="false"
            show-icon
            :title="`驳回原因：${detail.rejectReason}`"
          />

          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="申请人">{{ textOrDash(detail.applicantName) }}</el-descriptions-item>
            <el-descriptions-item label="用户 ID">
              <span class="mono">{{ detail.applicantUserId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">
              <span class="mono">{{ textOrDash(detail.phoneMasked) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="申请单号">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- ① 个人资质 -->
          <div class="section-title">① 个人资质</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="真实姓名">{{ textOrDash(detail.form && detail.form.realName) }}</el-descriptions-item>
            <el-descriptions-item label="执业证号">
              <span class="mono">{{ textOrDash(detail.form && detail.form.licenseNumber) }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <div class="image-block">
            <el-image
              v-if="detail.form && detail.form.licenseImageUrl"
              :src="detail.form.licenseImageUrl"
              :preview-src-list="[detail.form.licenseImageUrl]"
              preview-teleported
              fit="contain"
              class="image-block__img"
            >
              <template #error>
                <div class="image-block__fallback">影像加载失败（链接已过期，请重新打开抽屉）</div>
              </template>
            </el-image>
            <div v-else class="image-block__fallback">执业证影像未上传</div>
            <div class="image-block__label">执业证影像 · 链接 60 秒有效 · 查看行为会被记录</div>
          </div>

          <!-- ② 执业信息 -->
          <div class="section-title">② 执业信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="律师事务所">{{ textOrDash(detail.form && detail.form.lawFirm) }}</el-descriptions-item>
            <el-descriptions-item label="执业年限">段 2 补（本段 law_application 无该字段）</el-descriptions-item>
          </el-descriptions>

          <!-- ③ 擅长领域 -->
          <div class="section-title">③ 擅长领域</div>
          <div v-if="detail.form && detail.form.specialties && detail.form.specialties.length" class="tag-list">
            <el-tag v-for="item in detail.form.specialties" :key="item" effect="plain" size="small">{{ item }}</el-tag>
          </div>
          <el-empty v-else description="未填写擅长领域" :image-size="48" />

          <!-- ④ 服务说明 -->
          <div class="section-title">④ 服务说明</div>
          <div v-if="detail.form && detail.form.bio" class="text-block">{{ detail.form.bio }}</div>
          <el-empty v-else description="未填写服务说明" :image-size="48" />

          <!-- 审核流水 -->
          <div class="section-title">审核流水</div>
          <el-timeline v-if="detail.logs && detail.logs.length">
            <el-timeline-item
              v-for="(log, index) in detail.logs"
              :key="index"
              :timestamp="formatDateTime(log.createdAt)"
              placement="top"
            >
              <div class="log-title">{{ actionText(log.action) }}</div>
              <div class="log-meta">
                {{ statusText(log.fromStatus) }} → {{ statusText(log.toStatus) }}
                · {{ operatorText(log) }}
              </div>
              <div v-if="log.reason" class="log-reason">理由：{{ log.reason }}</div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无审核流水" :image-size="48" />
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="未取到申请详情" :image-size="64" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <template v-if="detail && detail.status === 'PENDING'">
            <el-button type="danger" :loading="auditing" @click="handleReject">驳回</el-button>
            <el-button type="primary" :loading="auditing" @click="handleApprove">通过</el-button>
          </template>
          <span v-else-if="detail" class="drawer-footer__tip">该申请已审核，不可重复操作</span>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="BizLawyerApplication">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  auditLawyerApplication,
  getLawyerApplicationDetail,
  listLawyerApplications
} from '@/api/biz/lawyerApplication'
import { describeRequestError, formatDateTime, textOrDash, toNumber } from '@/views/biz/shared/format'

/** 申请状态文案（出参大写枚举）。 */
const STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** 状态标签色。 */
const STATUS_TAG = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
}

/**
 * 状态下拉选项：**不含「全部」**——契约里 status 传空 = 只看 PENDING。
 */
const STATUS_OPTIONS = Object.keys(STATUS_TEXT).map((value) => ({
  value,
  label: STATUS_TEXT[value]
}))

/** 审核流水 action 文案。 */
const ACTION_TEXT = {
  submit: '提交申请',
  draft: '保存草稿',
  approve: '审核通过',
  reject: '审核驳回'
}

/** 操作者类型文案。 */
const OPERATOR_TYPE_TEXT = {
  admin: '管理员',
  user: '申请人',
  system: '系统'
}

/** el-date-picker 的当日时间边界。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** 列表查询条件（status 默认待审）。 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: 'PENDING'
})

/** 提交时间区间（ISO 字符串）。 */
const timeRange = ref([])

/** 列表数据。 */
const rows = ref([])

/** 总条数。 */
const total = ref(0)

/** 列表加载中。 */
const loading = ref(false)

/** 列表错误信息。 */
const error = ref('')

/** 抽屉可见。 */
const drawerVisible = ref(false)

/** 详情加载中。 */
const detailLoading = ref(false)

/** 详情错误信息。 */
const detailError = ref('')

/** 审核动作失败信息。 */
const actionError = ref('')

/** 详情对象。 */
const detail = ref(null)

/** 当前申请 ID（字符串）。 */
const currentId = ref('')

/** 审核提交中。 */
const auditing = ref(false)

/** 抽屉标题。 */
const drawerTitle = ref('入驻审核')

/**
 * 组装列表查询参数。
 *
 * @returns {object} 查询参数
 */
function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.status) {
    params.status = query.status
  }
  if (Array.isArray(timeRange.value) && timeRange.value.length === 2) {
    params.beginTime = timeRange.value[0]
    params.endTime = timeRange.value[1]
  }
  return params
}

/**
 * 拉取入驻申请队列。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listLawyerApplications(buildParams())
    rows.value = Array.isArray(res.rows) ? res.rows : []
    total.value = toNumber(res.total)
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = describeRequestError(e, '入驻申请队列加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 查询（回到第 1 页）。
 *
 * @returns {void}
 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/**
 * 重置：状态回到默认「待审核」，清空时间区间。
 *
 * @returns {void}
 */
function handleReset() {
  query.status = 'PENDING'
  timeRange.value = []
  query.pageNum = 1
  loadList()
}

/**
 * 改每页条数。
 *
 * @returns {void}
 */
function handleSizeChange() {
  query.pageNum = 1
  loadList()
}

/**
 * 打开详情抽屉（**此时才取签名 URL**）。
 *
 * @param {object} row 队列行
 * @returns {Promise<void>} 无返回值
 */
async function openDetail(row) {
  currentId.value = String(row.id)
  drawerTitle.value = row.status === 'PENDING' ? '入驻审核' : '入驻申请详情'
  detail.value = null
  detailError.value = ''
  actionError.value = ''
  drawerVisible.value = true
  await loadDetail()
}

/**
 * 拉取当前申请详情（含四块表单、审核流水、签名影像 URL）。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadDetail() {
  if (!currentId.value) {
    return
  }
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await getLawyerApplicationDetail(currentId.value)
    detail.value = res.data || null
  } catch (e) {
    detail.value = null
    detailError.value = describeRequestError(e, '入驻申请详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/**
 * 抽屉关闭：清空详情。
 *
 * @returns {void}
 */
function handleDrawerClosed() {
  detail.value = null
  detailError.value = ''
  actionError.value = ''
}

/**
 * 通过审核：二次确认 → 调接口 → 刷新列表并关闭抽屉。
 *
 * 副作用（服务端）：写 lawyer 表、改 app_user.role='lawyer'、追加审核流水。
 *
 * @returns {Promise<void>} 无返回值
 */
async function handleApprove() {
  if (!detail.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认通过「${detail.value.applicantName || '该申请人'}」的入驻申请？` +
        '通过后服务端会写入律师档案并把用户角色改为律师，且不可撤销。',
      '通过审核',
      { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  auditing.value = true
  actionError.value = ''
  try {
    await auditLawyerApplication(currentId.value, { action: 'approve' })
    ElMessage.success('已通过入驻审核')
    drawerVisible.value = false
    await loadList()
  } catch (e) {
    actionError.value = describeRequestError(e, '审核失败')
  } finally {
    auditing.value = false
  }
}

/**
 * 驳回：理由必填（前端校验 ≤200 字）→ 调接口 → 刷新列表并关闭抽屉。
 *
 * @returns {Promise<void>} 无返回值
 */
async function handleReject() {
  if (!detail.value) {
    return
  }
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `驳回「${detail.value.applicantName || '该申请人'}」的入驻申请，请填写理由（≤200 字，会同步给申请人并写入审计日志）`,
      '驳回审核',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '如：执业证影像不清晰，请重新上传',
        inputValidator: (value) => {
          const text = (value || '').trim()
          if (!text) {
            return '请填写驳回理由'
          }
          if (text.length > 200) {
            return '理由不能超过 200 字'
          }
          return true
        }
      }
    )
    reason = result.value.trim()
  } catch (e) {
    return
  }
  auditing.value = true
  actionError.value = ''
  try {
    await auditLawyerApplication(currentId.value, { action: 'reject', reason })
    ElMessage.success('已驳回')
    drawerVisible.value = false
    await loadList()
  } catch (e) {
    actionError.value = describeRequestError(e, '驳回失败')
  } finally {
    auditing.value = false
  }
}

/**
 * 状态文案。
 *
 * @param {string} status 枚举值
 * @returns {string} 中文文案
 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/**
 * 状态对应的 el-tag 类型。
 *
 * @param {string} status 枚举值
 * @returns {string} el-tag type
 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/**
 * 审核流水动作文案。
 *
 * @param {string} action 流水 action
 * @returns {string} 中文文案（未知值原样回显）
 */
function actionText(action) {
  if (!action) {
    return '状态变更'
  }
  return ACTION_TEXT[action] || action
}

/**
 * 流水操作者展示（operatorType + operatorId，ID 保持字符串）。
 *
 * @param {{operatorType?:string, operatorId?:string|number}} log 流水项
 * @returns {string} 展示文案
 */
function operatorText(log) {
  const type = log.operatorType ? OPERATOR_TYPE_TEXT[log.operatorType] || log.operatorType : '未知操作者'
  if (log.operatorId === null || log.operatorId === undefined || log.operatorId === '') {
    return type
  }
  return `${type} #${log.operatorId}`
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
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-head__desc {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.page-head__actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.block-gap {
  margin-bottom: 16px;
}

.error-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.query-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.table-toolbar__count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-stack__main {
  color: var(--el-text-color-primary);
}

.cell-stack__sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.mono {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.status-line__time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-title {
  margin: 20px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

/* 影像块：固定高度，避免不同比例把抽屉撑跳 */
.image-block {
  margin-top: 12px;
}

.image-block__img {
  width: 100%;
  height: 160px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-fill-color-light);
}

.image-block__fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 160px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  background-color: var(--el-fill-color-lighter);
}

.image-block__label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.text-block {
  padding: 12px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--el-text-color-regular);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-fill-color-lighter);
}

.log-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.log-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.log-reason {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.drawer-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.drawer-footer__tip {
  margin-right: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
