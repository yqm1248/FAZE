<!--
  职责：PC A-06 律师列表 —— 律师查询、分页、详情抽屉（资质、服务价格、服务开关、银行卡尾号、关联用户）。
  调用方：RuoYi 动态路由（sys_menu 2105，component = biz/lawyer/index，route name = BizLawyer）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET /api/v1/admin/lawyers?keyword&region&auditStatus&beginTime&endTime&pageNum&pageSize  列表（顶层 rows/total）
    GET /api/v1/admin/lawyers/{id}                                                            详情（R<LawyerDetailVO>）
  注意：
    1. 本段**不做**违规 / 限流 / 下架 / 清退（段 7），所以没有任何写接口：
       详情里的「服务开关」是**只读展示**（el-switch disabled + 说明），不做假的可点开关。
    2. 金额字段（consultPriceCents / phonePriceCents / documentPriceCents）是整数分，
       展示走 shared/format.js 的 formatCents。
    3. `withdrawBankAccountTail` 只有卡号后 4 位——页面不得声称能看完整卡号（服务端也不给）。
    4. `licenseImageUrl` 是 60 秒签名 URL，只在打开抽屉时取。
    5. `onlineStatus` 契约未冻结枚举，服务端原样返回库内值（online/busy/offline），
       页面按已知值映射，未知值原样显示——不自己造大写口径。
    6. 四态：加载中 / 空 / 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">律师列表</h1>
        <p class="page-head__desc">
          已完成入驻审核的律师档案。本页只做查询与详情；违规处置、接单开关等管理动作排在段 7。
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
      title="律师列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="姓名 / 律所（模糊）"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="地区">
          <el-input
            v-model="query.region"
            placeholder="如：伊宁市"
            clearable
            style="width: 150px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="审核状态">
          <el-select v-model="query.auditStatus" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="item in AUDIT_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="入驻时间">
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

    <!-- ==================== 表格 ==================== -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-toolbar__count">共 {{ total }} 条</span>
        <el-button :loading="loading" link @click="loadList">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" row-key="id" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="error ? '列表加载失败，请点上方「重试」' : '没有符合条件的律师'"
            :image-size="72"
          />
        </template>
        <el-table-column label="律师" min-width="130">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">{{ textOrDash(row.realName) }}</span>
              <span class="cell-stack__sub mono">律师 ID {{ row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="关联用户" min-width="90">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="mono">{{ row.userId }}</span>
              <span class="cell-stack__sub">用户 ID</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="律所 / 地区" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-stack">
              <span>{{ textOrDash(row.lawFirm) }}</span>
              <span class="cell-stack__sub">{{ textOrDash(row.region) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="执业证号" min-width="120">
          <template #default="{ row }">
            <span class="mono">{{ textOrDash(row.licenseNoMasked) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="执业年限" min-width="80" align="right">
          <template #default="{ row }">
            <span class="mono">{{ row.practiceYears === null || row.practiceYears === undefined ? '—' : `${row.practiceYears} 年` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="擅长领域" min-width="130">
          <template #default="{ row }">
            <template v-if="row.specialties && row.specialties.length">
              <el-tag
                v-for="item in row.specialties.slice(0, 2)"
                :key="item"
                class="tag-gap"
                effect="plain"
                size="small"
              >{{ item }}</el-tag>
              <span v-if="row.specialties.length > 2" class="cell-stack__sub">+{{ row.specialties.length - 2 }}</span>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="评分 / 评价" min-width="90" align="right">
          <template #default="{ row }">
            <div class="cell-stack cell-stack--end">
              <span class="mono">{{ ratingText(row.rating) }}</span>
              <span class="cell-stack__sub mono">{{ toNumber(row.reviewCount) }} 条</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="auditTagType(row.auditStatus)" effect="light" size="small">
              {{ auditStatusText(row.auditStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线状态" min-width="80">
          <template #default="{ row }">
            <el-tag :type="onlineTagType(row.onlineStatus)" effect="plain" size="small">
              {{ onlineStatusText(row.onlineStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入驻时间" min-width="105">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="70" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">详情</el-button>
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

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer
      v-model="drawerVisible"
      title="律师详情"
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
          title="律师详情加载失败"
        >
          <div class="error-body">
            <span>{{ detailError }}</span>
            <el-button type="primary" link @click="loadDetail">重试</el-button>
          </div>
        </el-alert>

        <template v-if="detail">
          <div class="status-line">
            <el-tag :type="auditTagType(detail.auditStatus)" effect="light" size="small">
              {{ auditStatusText(detail.auditStatus) }}
            </el-tag>
            <el-tag :type="onlineTagType(detail.onlineStatus)" effect="plain" size="small">
              {{ onlineStatusText(detail.onlineStatus) }}
            </el-tag>
            <span class="status-line__time">入驻于 {{ formatDateTime(detail.createdAt) }}</span>
          </div>

          <div class="section-title">基本信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="姓名">{{ textOrDash(detail.realName) }}</el-descriptions-item>
            <el-descriptions-item label="律师 ID">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="执业证号">
              <span class="mono">{{ textOrDash(detail.licenseNoMasked) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="执业年限">
              {{ detail.practiceYears === null || detail.practiceYears === undefined ? '—' : `${detail.practiceYears} 年` }}
            </el-descriptions-item>
            <el-descriptions-item label="律师事务所">{{ textOrDash(detail.lawFirm) }}</el-descriptions-item>
            <el-descriptions-item label="地区">{{ textOrDash(detail.region) }}</el-descriptions-item>
            <el-descriptions-item label="评分">
              <span class="mono">{{ ratingText(detail.rating) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="评价 / 订单">
              <span class="mono">{{ toNumber(detail.reviewCount) }} 条 / {{ toNumber(detail.orderCount) }} 单</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="section-title">资质与介绍</div>
          <div class="image-block">
            <el-image
              v-if="detail.licenseImageUrl"
              :src="detail.licenseImageUrl"
              :preview-src-list="[detail.licenseImageUrl]"
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
          <div v-if="detail.specialties && detail.specialties.length" class="tag-list">
            <el-tag v-for="item in detail.specialties" :key="item" effect="plain" size="small">{{ item }}</el-tag>
          </div>
          <div v-else class="muted block-gap">未填写擅长领域</div>
          <div v-if="detail.bio" class="text-block">{{ detail.bio }}</div>
          <div v-else class="text-block text-block--empty">未填写服务说明</div>

          <div class="section-title">服务价格</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="图文咨询">
              <span class="mono">¥ {{ formatCents(detail.consultPriceCents) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="电话咨询">
              <span class="mono">¥ {{ formatCents(detail.phonePriceCents) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="文书服务">
              <span class="mono">¥ {{ formatCents(detail.documentPriceCents) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="section-title">
            服务开关
            <span class="section-title__hint">本段只读：开关修改接口属于段 7</span>
          </div>
          <div class="switch-grid">
            <div class="switch-item">
              <el-switch :model-value="detail.serviceTextOn === true" disabled />
              <span>图文咨询</span>
            </div>
            <div class="switch-item">
              <el-switch :model-value="detail.servicePhoneOn === true" disabled />
              <span>电话咨询</span>
            </div>
            <div class="switch-item">
              <el-switch :model-value="detail.serviceDocOn === true" disabled />
              <span>文书服务</span>
            </div>
            <div class="switch-item">
              <el-switch :model-value="detail.autoAccept === true" disabled />
              <span>自动接单</span>
            </div>
          </div>

          <div class="section-title">结算与关联用户</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="银行卡尾号">
              <span class="mono">{{ detail.withdrawBankAccountTail ? `**** ${detail.withdrawBankAccountTail}` : '未绑定' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="关联用户 ID">
              <span class="mono">{{ detail.user ? detail.user.id : '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="用户昵称">{{ detail.user ? textOrDash(detail.user.nickname) : '—' }}</el-descriptions-item>
            <el-descriptions-item label="用户手机号">
              <span class="mono">{{ detail.user ? textOrDash(detail.user.phoneMasked) : '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="用户实名状态">
              <el-tag v-if="detail.user" :type="realnameTagType(detail.user.realnameStatus)" effect="light" size="small">
                {{ realnameText(detail.user.realnameStatus) }}
              </el-tag>
              <span v-else>—</span>
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="未取到律师详情" :image-size="64" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <span class="drawer-footer__tip">本段不提供违规处置 / 限流 / 下架 / 清退（段 7 实现）</span>
          <el-button @click="drawerVisible = false">关闭</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="BizLawyer">
import { onMounted, reactive, ref } from 'vue'
import { getLawyerDetail, listLawyers } from '@/api/biz/lawyer'
import { describeRequestError, formatCents, formatDateTime, textOrDash, toNumber } from '@/views/biz/shared/format'

/** 入驻审核状态文案（出参大写枚举）。 */
const AUDIT_STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** 审核状态标签色。 */
const AUDIT_STATUS_TAG = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
}

/** 审核状态下拉选项。 */
const AUDIT_STATUS_OPTIONS = Object.keys(AUDIT_STATUS_TEXT).map((value) => ({
  value,
  label: AUDIT_STATUS_TEXT[value]
}))

/**
 * 在线状态文案。
 *
 * 契约未冻结该枚举，服务端原样返回库内值（online / busy / offline），
 * 这里只做已知值映射，未知值原样回显。
 */
const ONLINE_STATUS_TEXT = {
  online: '在线',
  busy: '忙碌',
  offline: '离线'
}

/** 在线状态标签色。 */
const ONLINE_STATUS_TAG = {
  online: 'success',
  busy: 'warning',
  offline: 'info'
}

/** 实名状态文案（与 A-03 同口径）。 */
const REALNAME_STATUS_TEXT = {
  NONE: '未认证',
  PENDING: '审核中',
  APPROVED: '已认证',
  REJECTED: '已驳回'
}

/** 实名状态标签色。 */
const REALNAME_STATUS_TAG = {
  NONE: 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
}

/** el-date-picker 的当日时间边界。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** 列表查询条件。 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  region: '',
  auditStatus: ''
})

/** 入驻时间区间（ISO 字符串）。 */
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

/** 详情对象。 */
const detail = ref(null)

/** 当前律师 ID（字符串）。 */
const currentId = ref('')

/**
 * 组装列表查询参数。
 *
 * @returns {object} 查询参数
 */
function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.keyword.trim()) {
    params.keyword = query.keyword.trim()
  }
  if (query.region.trim()) {
    params.region = query.region.trim()
  }
  if (query.auditStatus) {
    params.auditStatus = query.auditStatus
  }
  if (Array.isArray(timeRange.value) && timeRange.value.length === 2) {
    params.beginTime = timeRange.value[0]
    params.endTime = timeRange.value[1]
  }
  return params
}

/**
 * 拉取律师列表。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listLawyers(buildParams())
    rows.value = Array.isArray(res.rows) ? res.rows : []
    total.value = toNumber(res.total)
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = describeRequestError(e, '律师列表加载失败')
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
  query.keyword = ''
  query.region = ''
  query.auditStatus = ''
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
 * 打开详情抽屉（此时才取签名 URL）。
 *
 * @param {object} row 列表行
 * @returns {Promise<void>} 无返回值
 */
async function openDetail(row) {
  currentId.value = String(row.id)
  detail.value = null
  detailError.value = ''
  drawerVisible.value = true
  await loadDetail()
}

/**
 * 拉取当前律师详情。
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
    const res = await getLawyerDetail(currentId.value)
    detail.value = res.data || null
  } catch (e) {
    detail.value = null
    detailError.value = describeRequestError(e, '律师详情加载失败')
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
}

/**
 * 审核状态文案。
 *
 * @param {string} status 枚举值
 * @returns {string} 中文文案
 */
function auditStatusText(status) {
  return AUDIT_STATUS_TEXT[status] || status || '—'
}

/**
 * 审核状态对应的 el-tag 类型。
 *
 * @param {string} status 枚举值
 * @returns {string} el-tag type
 */
function auditTagType(status) {
  return AUDIT_STATUS_TAG[status] || 'info'
}

/**
 * 在线状态文案。
 *
 * @param {string} status 库内值 online/busy/offline
 * @returns {string} 中文文案
 */
function onlineStatusText(status) {
  if (!status) {
    return '—'
  }
  return ONLINE_STATUS_TEXT[status] || status
}

/**
 * 在线状态对应的 el-tag 类型。
 *
 * @param {string} status 库内值
 * @returns {string} el-tag type
 */
function onlineTagType(status) {
  return ONLINE_STATUS_TAG[status] || 'info'
}

/**
 * 实名状态文案。
 *
 * @param {string} status 枚举值
 * @returns {string} 中文文案
 */
function realnameText(status) {
  return REALNAME_STATUS_TEXT[status] || status || '—'
}

/**
 * 实名状态对应的 el-tag 类型。
 *
 * @param {string} status 枚举值
 * @returns {string} el-tag type
 */
function realnameTagType(status) {
  return REALNAME_STATUS_TAG[status] || 'info'
}

/**
 * 评分展示：服务端给 BigDecimal（0.0~5.0），保留一位小数。
 *
 * @param {number|string|null} rating 评分
 * @returns {string} 形如 `4.8`，空值回 `—`
 */
function ratingText(rating) {
  if (rating === null || rating === undefined || rating === '') {
    return '—'
  }
  const value = Number(rating)
  return Number.isFinite(value) ? value.toFixed(1) : String(rating)
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

.cell-stack--end {
  align-items: flex-end;
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

.tag-gap {
  margin-right: 4px;
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

.image-block {
  margin-bottom: 12px;
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
  margin-top: 12px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--el-text-color-regular);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-fill-color-lighter);
}

.text-block--empty {
  color: var(--el-text-color-secondary);
}

.switch-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.switch-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
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
