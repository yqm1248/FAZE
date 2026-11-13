<!--
  职责：PC A-04 实名认证审核 —— 实名（realname）与企业（enterprise）两类认证的**同一队列**分页、
        详情抽屉（证件正反面 / 营业执照影像）、通过 / 驳回（驳回必填理由）。
  调用方：RuoYi 动态路由（sys_menu 2103，component = biz/verification/index，route name = BizVerification）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET  /api/v1/admin/verifications?type&status&beginTime&endTime&pageNum&pageSize  列表（顶层 rows/total）
    GET  /api/v1/admin/verifications/{id}?type=realname|enterprise                   详情（R<VerificationDetailVO>）
    POST /api/v1/admin/verifications/{id}/audit  body {type, action, reason}         审核（R<{id,status}>）
  注意：
    1. **查询参数的 type 是小写**（realname|enterprise，空=全部），**出参里的 type 是大写**
       （REALNAME|ENTERPRISE）。两者必须显式映射（见 TYPE_TO_QUERY / typeText），直接透传会 401004。
    2. 详情接口必须带 type：同一个 id 在 realname_auth 与 enterprise_auth 两张表里都可能存在。
    3. 影像是 **60 秒有效的签名 URL**，所以只在**打开抽屉时**取一次，列表里绝不预取；
       打开太久再点预览会 403，这属于预期行为，页面上给了「重新打开抽屉」的提示。
    4. 驳回理由必填（前端先校验 ≤200 字，后端也会拦 401001）；通过/驳回成功后**刷新列表并关闭抽屉**。
    5. 已审过的记录服务端会回 401006：所以只有 status === 'PENDING' 才显示通过/驳回按钮。
    6. 敏感展示：身份证号只用服务端给的 idCardMasked；页面不展示也不允许查看完整号段。
    7. 四态：加载中（v-loading）/ 空（el-empty）/ 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">实名认证审核</h1>
        <p class="page-head__desc">
          个人实名与企业认证共用一条审核队列。驳回必须填写理由，理由会写入审计日志并同步给用户。
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
      title="认证队列加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="认证类型">
          <el-radio-group v-model="query.type" @change="handleSearch">
            <!-- Element Plus 2.6+：值用 `value`，展示文案放插槽；`label` 当值已标记弃用（控制台会告警） -->
            <el-radio-button value="">全部类型</el-radio-button>
            <el-radio-button value="realname">实名认证</el-radio-button>
            <el-radio-button value="enterprise">企业认证</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="审核状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
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
            :description="error ? '队列加载失败，请点上方「重试」' : '当前筛选条件下没有认证记录'"
            :image-size="72"
          />
        </template>
        <el-table-column label="提交时间" min-width="120">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.submittedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="90">
          <template #default="{ row }">
            <el-tag :type="row.type === 'ENTERPRISE' ? 'primary' : 'info'" effect="plain" size="small">
              {{ typeText(row.type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">{{ row.userNickname || '（未设置昵称）' }}</span>
              <span class="cell-stack__sub mono">ID {{ row.userId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="姓名 / 企业名称" min-width="130">
          <template #default="{ row }">{{ textOrDash(row.realnameMasked) }}</template>
        </el-table-column>
        <el-table-column label="证件号 / 信用代码" min-width="140">
          <template #default="{ row }">
            <span class="mono">{{ textOrDash(row.idCardMasked) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核人" min-width="100">
          <template #default="{ row }">
            <span class="mono">{{ reviewerText(row.reviewedBy) }}</span>
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
      size="700px"
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
          title="认证详情加载失败"
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
            <el-tag :type="detail.type === 'ENTERPRISE' ? 'primary' : 'info'" effect="plain" size="small">
              {{ typeText(detail.type) }}
            </el-tag>
            <el-tag :type="statusTagType(detail.status)" effect="light" size="small">
              {{ statusText(detail.status) }}
            </el-tag>
            <span class="status-line__time">提交于 {{ formatDateTime(detail.submittedAt) }}</span>
          </div>

          <el-alert
            v-if="detail.status === 'REJECTED' && detail.rejectReason"
            class="block-gap"
            type="warning"
            :closable="false"
            show-icon
            :title="`驳回原因：${detail.rejectReason}`"
          />

          <!-- 实名：个人 -->
          <template v-if="detail.type !== 'ENTERPRISE'">
            <div class="section-title">实名信息</div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="用户昵称">{{ textOrDash(detail.userNickname) }}</el-descriptions-item>
              <el-descriptions-item label="用户 ID">
                <span class="mono">{{ detail.userId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="真实姓名">{{ textOrDash(detail.realname) }}</el-descriptions-item>
              <el-descriptions-item label="身份证号">
                <span class="mono">{{ textOrDash(detail.idCardMasked) }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <div class="section-title">
              证件影像
              <span class="section-title__hint">链接 60 秒有效 · 查看行为会被记录</span>
            </div>
            <div class="image-grid">
              <div class="image-cell">
                <el-image
                  v-if="detail.frontUrl"
                  :src="detail.frontUrl"
                  :preview-src-list="[detail.frontUrl]"
                  preview-teleported
                  fit="contain"
                  class="image-cell__img"
                >
                  <template #error>
                    <div class="image-cell__fallback">
                      影像加载失败（链接已过期）
                      <el-button type="primary" link size="small" @click="reloadImages">重新加载</el-button>
                    </div>
                  </template>
                </el-image>
                <div v-else class="image-cell__fallback">未上传</div>
                <div class="image-cell__label">身份证正面</div>
              </div>
              <div class="image-cell">
                <el-image
                  v-if="detail.backUrl"
                  :src="detail.backUrl"
                  :preview-src-list="[detail.backUrl]"
                  preview-teleported
                  fit="contain"
                  class="image-cell__img"
                >
                  <template #error>
                    <div class="image-cell__fallback">
                      影像加载失败（链接已过期）
                      <el-button type="primary" link size="small" @click="reloadImages">重新加载</el-button>
                    </div>
                  </template>
                </el-image>
                <div v-else class="image-cell__fallback">未上传</div>
                <div class="image-cell__label">身份证反面</div>
              </div>
            </div>
          </template>

          <!-- 企业 -->
          <template v-else>
            <div class="section-title">企业信息</div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="企业名称">{{ textOrDash(detail.companyName) }}</el-descriptions-item>
              <el-descriptions-item label="统一社会信用代码">
                <span class="mono">{{ textOrDash(detail.creditCode) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="法定代表人">{{ textOrDash(detail.legalPerson) }}</el-descriptions-item>
              <el-descriptions-item label="联系电话">
                <span class="mono">{{ textOrDash(detail.contactPhone) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="用户昵称">{{ textOrDash(detail.userNickname) }}</el-descriptions-item>
              <el-descriptions-item label="用户 ID">
                <span class="mono">{{ detail.userId }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <div class="section-title">
              营业执照
              <span class="section-title__hint">链接 60 秒有效 · 查看行为会被记录</span>
            </div>
            <div class="image-grid">
              <div class="image-cell">
                <el-image
                  v-if="detail.licenseUrl"
                  :src="detail.licenseUrl"
                  :preview-src-list="[detail.licenseUrl]"
                  preview-teleported
                  fit="contain"
                  class="image-cell__img"
                >
                  <template #error>
                    <div class="image-cell__fallback">
                      影像加载失败（链接已过期）
                      <el-button type="primary" link size="small" @click="reloadImages">重新加载</el-button>
                    </div>
                  </template>
                </el-image>
                <div v-else class="image-cell__fallback">未上传</div>
                <div class="image-cell__label">营业执照</div>
              </div>
            </div>
          </template>
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="未取到认证详情" :image-size="64" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <template v-if="detail && detail.status === 'PENDING'">
            <el-button type="danger" :loading="auditing" @click="handleReject">驳回</el-button>
            <el-button type="primary" :loading="auditing" @click="handleApprove">通过</el-button>
          </template>
          <span v-else-if="detail" class="drawer-footer__tip">该记录已审核，不可重复操作</span>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="BizVerification">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditVerification, getVerificationDetail, listVerifications } from '@/api/biz/verification'
import { describeRequestError, formatDateTime, textOrDash, toNumber } from '@/views/biz/shared/format'

/**
 * 当前路由。
 *
 * A-02「待我处理」的实名认证待办会跳到本页并带 `type=realname&status=PENDING`，
 * 本页负责把这两个参数落进筛选条件——否则跳转只是换了个 URL，列表还是默认全量，
 * 运营看到的条数与看板卡片对不上。
 */
const route = useRoute()

/** 审核状态文案（出参为大写枚举）。 */
const STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** 审核状态标签色：待审核=warning、通过=success、驳回=danger。 */
const STATUS_TAG = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
}

/** 审核状态下拉选项。 */
const STATUS_OPTIONS = Object.keys(STATUS_TEXT).map((value) => ({
  value,
  label: STATUS_TEXT[value]
}))

/**
 * 出参类型（大写）→ 查询参数类型（小写）。
 *
 * 契约里查询用 `realname|enterprise`，出参用 `REALNAME|ENTERPRISE`，
 * 详情与审核接口又要求小写 type，所以必须在这里显式映射。
 */
const TYPE_TO_QUERY = {
  REALNAME: 'realname',
  ENTERPRISE: 'enterprise'
}

/** 类型文案。 */
const TYPE_TEXT = {
  REALNAME: '实名认证',
  ENTERPRISE: '企业认证'
}

/** el-date-picker 的当日时间边界。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** 列表查询条件（type 为空 = 全部类型）。 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  type: '',
  status: ''
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

/** 审核动作失败信息（留在抽屉里，方便重试）。 */
const actionError = ref('')

/** 详情对象。 */
const detail = ref(null)

/**
 * 影像签名 URL 的静默续签定时器（工单 W07 / 盲测 B51）。
 *
 * 后端给的影像是**60 秒有效期的预签名地址**（`application.yml` 的
 * `download-ttl-seconds: 60`），地址一过期，放大预览、浏览器重新请求都会 403，
 * 表现为「抽屉开久了图片就打不开，只能关掉重开」。
 *
 * 这里在抽屉打开期间每 50 秒静默重取一次详情，只替换三个 URL 字段、不动 loading 态，
 * 所以画面不闪。**不写审计**：详情 GET 没有 `@OperationLog`，`downloadUrlForAdmin`
 * 本身也只签名不记录，续签不会把 operation_log 刷脏。
 */
const RESIGN_INTERVAL_MS = 50_000
let resignTimer = null

/** 当前认证记录 ID（字符串）。 */
const currentId = ref('')

/** 当前认证类型（小写，详情与审核接口都要传）。 */
const currentType = ref('')

/** 审核提交中（通过/驳回共用，防止连点）。 */
const auditing = ref(false)

/** 抽屉标题。 */
const drawerTitle = ref('认证审核')

/**
 * 组装列表查询参数。
 *
 * @returns {object} 查询参数
 */
function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.type) {
    params.type = query.type
  }
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
 * 拉取认证审核队列。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listVerifications(buildParams())
    rows.value = Array.isArray(res.rows) ? res.rows : []
    total.value = toNumber(res.total)
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = describeRequestError(e, '认证队列加载失败')
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
 * 重置筛选条件。
 *
 * @returns {void}
 */
function handleReset() {
  query.type = ''
  query.status = ''
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
 * 打开详情抽屉（**此时才取签名 URL**，列表里不预取）。
 *
 * @param {object} row 队列行（含 id 与大写 type）
 * @returns {Promise<void>} 无返回值
 */
async function openDetail(row) {
  currentId.value = String(row.id)
  currentType.value = TYPE_TO_QUERY[row.type] || 'realname'
  drawerTitle.value = row.status === 'PENDING' ? '认证审核' : '认证详情'
  detail.value = null
  detailError.value = ''
  actionError.value = ''
  drawerVisible.value = true
  await loadDetail()
  startResign()
}

/**
 * 启动影像 URL 静默续签（见 `RESIGN_INTERVAL_MS` 的说明）。
 *
 * @returns {void}
 */
function startResign() {
  stopResign()
  resignTimer = setInterval(() => {
    void resignImages()
  }, RESIGN_INTERVAL_MS)
}

/**
 * 停止静默续签（抽屉关闭时必须调，否则定时器会在组件之外继续跑）。
 *
 * @returns {void}
 */
function stopResign() {
  if (resignTimer !== null) {
    clearInterval(resignTimer)
    resignTimer = null
  }
}

/**
 * 静默重签影像地址：只替换 URL 字段，不改 loading / error 态。
 *
 * 失败**故意不提示**：旧地址在浏览器缓存里通常还能显示，弹错只会干扰审核。
 * 真失败时用户点图片会走 `#error` 插槽里的「重新加载」。
 *
 * @returns {Promise<void>} 无返回值
 */
async function resignImages() {
  if (!currentId.value || !drawerVisible.value || detail.value === null) {
    return
  }
  try {
    const res = await getVerificationDetail(currentId.value, currentType.value)
    const fresh = res.data
    if (!fresh || detail.value === null) {
      return
    }
    detail.value.frontUrl = fresh.frontUrl
    detail.value.backUrl = fresh.backUrl
    detail.value.licenseUrl = fresh.licenseUrl
  } catch (e) {
    // 静默：续签失败不打扰审核员，旧 URL 仍可能命中浏览器缓存
  }
}

/** 组件卸载（切路由/刷新）时停掉续签定时器，避免定时器泄漏到组件之外。 */
onUnmounted(stopResign)

/**
 * 影像加载失败时的兜底：手动重新拉详情换新签名地址。
 *
 * 定时续签万一漏了（例如浏览器休眠后 timer 被节流），用户还有这条出路，
 * 不用关掉抽屉重开。
 *
 * @returns {Promise<void>} 无返回值
 */
async function reloadImages() {
  await loadDetail()
}

/**
 * 拉取当前认证详情。
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
    const res = await getVerificationDetail(currentId.value, currentType.value)
    detail.value = res.data || null
  } catch (e) {
    detail.value = null
    detailError.value = describeRequestError(e, '认证详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/**
 * 抽屉关闭：清空详情，避免下次打开闪一下上一个记录。
 *
 * @returns {void}
 */
function handleDrawerClosed() {
  stopResign()
  detail.value = null
  detailError.value = ''
  actionError.value = ''
}

/**
 * 通过审核：二次确认 → 调接口 → 刷新列表并关闭抽屉。
 *
 * @returns {Promise<void>} 无返回值
 */
async function handleApprove() {
  if (!detail.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认通过该${typeText(detail.value.type)}申请？通过后用户状态会同步更新，且不可撤销。`,
      '通过审核',
      { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  auditing.value = true
  actionError.value = ''
  try {
    await auditVerification(currentId.value, { type: currentType.value, action: 'approve' })
    ElMessage.success('已通过审核')
    drawerVisible.value = false
    await loadList()
  } catch (e) {
    actionError.value = describeRequestError(e, '审核失败')
  } finally {
    auditing.value = false
  }
}

/**
 * 驳回审核：理由必填（前端校验 ≤200 字）→ 调接口 → 刷新列表并关闭抽屉。
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
      `驳回该${typeText(detail.value.type)}申请，请填写理由（≤200 字，会同步给用户并写入审计日志）`,
      '驳回审核',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '如：身份证影像模糊，请重新上传',
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
    await auditVerification(currentId.value, {
      type: currentType.value,
      action: 'reject',
      reason
    })
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
 * 类型文案（入参可能是大写出参值，也可能是小写查询值）。
 *
 * @param {string} type REALNAME|ENTERPRISE|realname|enterprise
 * @returns {string} 中文类型名
 */
function typeText(type) {
  if (!type) {
    return '—'
  }
  return TYPE_TEXT[type] || TYPE_TEXT[String(type).toUpperCase()] || type
}

/**
 * 审核状态文案。
 *
 * @param {string} status 枚举值
 * @returns {string} 中文文案
 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

/**
 * 审核状态对应的 el-tag 类型。
 *
 * @param {string} status 枚举值
 * @returns {string} el-tag type
 */
function statusTagType(status) {
  return STATUS_TAG[status] || 'info'
}

/**
 * 审核人展示。
 *
 * 服务端 `AdminVerificationRowVO.reviewedBy` 是**管理员用户 ID（Long，序列化为字符串）**，
 * 不是用户名——契约只写了字段名没写语义，按已实现的口径展示成「管理员 #ID」，
 * 并保持 ID 为字符串，不做数值化。
 *
 * @param {string|number|null} reviewedBy 审核管理员 ID
 * @returns {string} 展示文案
 */
function reviewerText(reviewedBy) {
  if (reviewedBy === null || reviewedBy === undefined || reviewedBy === '') {
    return '—'
  }
  return `管理员 #${reviewedBy}`
}

/**
 * 从路由 query 承接筛选条件（A-02「待我处理」跳转带参）。
 *
 * 只接受字符串且非空的值；数组/非法值一律忽略，避免 URL 手改把列表查成空。
 * 副作用：写 query.type / query.status（不触发请求，由调用方随后 loadList）。
 */
function applyRouteQuery() {
  const { type, status } = route.query
  if (typeof type === 'string' && type) {
    query.type = type
  }
  if (typeof status === 'string' && status) {
    query.status = status
  }
}

onMounted(() => {
  applyRouteQuery()
  loadList()
})
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

/* 抽屉里的一行状态标签 */
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
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 20px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.section-title__hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

/* 证件影像：等宽两列，高度固定，避免不同比例把抽屉撑跳 */
.image-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.image-cell {
  display: flex;
  flex-direction: column;
}

.image-cell__img {
  width: 100%;
  height: 150px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-fill-color-light);
}

.image-cell__fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 150px;
  padding: 0 16px;
  font-size: 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  background-color: var(--el-fill-color-lighter);
}

.image-cell__label {
  margin-top: 6px;
  font-size: 12px;
  text-align: center;
  color: var(--el-text-color-secondary);
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
