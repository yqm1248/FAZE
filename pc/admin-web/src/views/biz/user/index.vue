<!--
  职责：PC A-03 用户列表 —— C 端用户的查询、分页、详情抽屉（设备 + 实名信息）、封禁 / 解封。
  调用方：RuoYi 动态路由（sys_menu 2102，component = biz/user/index，route name = BizUser）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET  /api/v1/admin/users             列表，TableDataInfo：**顶层 rows / total**
    GET  /api/v1/admin/users/{id}        详情，R<UserDetailVO>（含 devices / realname）
    POST /api/v1/admin/users/{id}/ban    封禁，body {reason 必填 ≤200, until 可空}
    POST /api/v1/admin/users/{id}/unban  解封，body {reason 必填 ≤200}
  注意：
    1. **ID 一律当字符串**：app_user.id 是 BIGINT，服务端 JacksonConfig 把包装类 Long 序列化成字符串。
       本页绝不做 Number()/parseInt()，否则超过 2^53 的 ID 会静默指向另一个用户。
    2. 金额是整数分：展示一律走 shared/format.js 的 formatCents（(cents/100).toFixed(2)），
       不做任何浮点累加。
    3. 敏感字段：接口只给 phoneMasked / idCardMasked，本页**不请求也不展示**完整手机号与身份证，
       没有「点击查看完整」——接口规范附录 A.2 明确列表页与详情页都不给完整号。
    4. 封禁会把 app_user.token_version + 1，C 端（App）旧 token 立即 401。这是第 1 段验收锚点 1-A3，
       所以封禁成功的提示必须写明「旧登录凭证已失效」，不能只说「操作成功」。
    5. 四态：加载中（v-loading）/ 空（el-empty）/ 错误（页面内常驻 el-alert + 重试按钮）/ 正常。
       错误**不能只弹一次 toast**：请求拦截器已经弹过一次，这里还要在页面里留一个可重试的提示。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">用户列表</h1>
        <p class="page-head__desc">
          平台注册用户，含实名状态、账号状态与消费汇总。封禁后该用户的 App 端旧登录凭证立即失效。
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
      title="用户列表加载失败"
    >
      <div class="error-body">
        <span>{{ error }}</span>
        <el-button type="primary" link @click="loadList">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form :model="query" inline label-width="72px" @submit.prevent>
        <el-form-item label="昵称">
          <el-input
            v-model="query.nickname"
            placeholder="昵称（模糊）"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input
            v-model="query.phone"
            placeholder="完整号或后 4 位"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="实名状态">
          <el-select v-model="query.realnameStatus" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="item in REALNAME_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号状态">
          <el-select v-model="query.accountStatus" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="item in ACCOUNT_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册时间">
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
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        stripe
        style="width: 100%"
      >
        <template #empty>
          <el-empty
            :description="error ? '数据加载失败，请点上方「重试」' : '没有符合条件的用户'"
            :image-size="72"
          />
        </template>
        <el-table-column label="用户" min-width="190">
          <template #default="{ row }">
            <div class="cell-stack">
              <span class="cell-stack__main">{{ row.nickname || '（未设置昵称）' }}</span>
              <span class="cell-stack__sub mono">ID {{ row.id }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" min-width="130">
          <template #default="{ row }">
            <span class="mono">{{ textOrDash(row.phoneMasked) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实名状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="realnameTagType(row.realnameStatus)" effect="light" size="small">
              {{ realnameText(row.realnameStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="accountTagType(row.accountStatus)" effect="light" size="small">
              {{ accountText(row.accountStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="累计消费 / 订单" min-width="130" align="right">
          <template #default="{ row }">
            <div class="cell-stack cell-stack--end">
              <span class="mono">¥ {{ formatCents(row.totalConsumeCents) }}</span>
              <span class="cell-stack__sub mono">{{ toNumber(row.orderCount) }} 单</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="140">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="140">
          <template #default="{ row }">
            <span class="mono muted">{{ formatDateTime(row.lastLoginAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.accountStatus === 'BANNED'"
              type="primary" link
              @click="handleUnban(row)"
            >解封</el-button>
            <el-button
              v-else-if="row.accountStatus !== 'CANCELLED'"
              type="danger" link
              @click="openBan(row)"
            >封禁</el-button>
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
      :title="detailTitle"
      size="640px"
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
          title="用户详情加载失败"
        >
          <div class="error-body">
            <span>{{ detailError }}</span>
            <el-button type="primary" link @click="loadDetail">重试</el-button>
          </div>
        </el-alert>

        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="昵称">{{ textOrDash(detail.nickname) }}</el-descriptions-item>
            <el-descriptions-item label="用户 ID">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">
              <span class="mono">{{ textOrDash(detail.phoneMasked) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="性别">{{ genderText(detail.gender) }}</el-descriptions-item>
            <el-descriptions-item label="出生日期">{{ formatDate(detail.birthDate) }}</el-descriptions-item>
            <el-descriptions-item label="是否未成年人">
              <el-tag v-if="detail.isMinor" type="warning" effect="light" size="small">是（受未成年人保护限制）</el-tag>
              <span v-else>否</span>
            </el-descriptions-item>
            <el-descriptions-item label="语言">{{ textOrDash(detail.lang) }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ roleText(detail.role) }}</el-descriptions-item>
            <el-descriptions-item label="实名状态">
              <el-tag :type="realnameTagType(detail.realnameStatus)" effect="light" size="small">
                {{ realnameText(detail.realnameStatus) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="账号状态">
              <el-tag :type="accountTagType(detail.accountStatus)" effect="light" size="small">
                {{ accountText(detail.accountStatus) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="注册时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近登录">{{ formatDateTime(detail.lastLoginAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近登录 IP">
              <span class="mono">{{ textOrDash(detail.lastLoginIp) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 实名信息 -->
          <div class="section-title">实名信息</div>
          <el-descriptions v-if="detail.realname" :column="2" border size="small">
            <el-descriptions-item label="姓名">
              {{ textOrDash(detail.realname.realnameMasked || detail.realname.realname) }}
            </el-descriptions-item>
            <el-descriptions-item label="身份证号">
              <span class="mono">{{ textOrDash(detail.realname.idCardMasked) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="认证状态">
              <el-tag :type="realnameTagType(detail.realname.status)" effect="light" size="small">
                {{ realnameText(detail.realname.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="驳回原因">
              {{ textOrDash(detail.realname.rejectReason) }}
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="该用户尚未提交实名认证" :image-size="64" />

          <!-- 设备 -->
          <div class="section-title">登录设备</div>
          <el-table
            v-if="detail.devices && detail.devices.length"
            :data="detail.devices"
            size="small"
            border
            style="width: 100%"
          >
            <el-table-column label="设备 ID" min-width="180">
              <template #default="{ row }">
                <span class="mono">{{ row.deviceId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="deviceName" label="设备名称" min-width="140" />
            <el-table-column label="最近活跃" min-width="150">
              <template #default="{ row }">
                <span class="mono muted">{{ formatDateTime(row.lastActiveAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="该用户暂无设备记录" :image-size="64" />
        </template>
        <el-empty v-else-if="!detailLoading && !detailError" description="未取到用户详情" :image-size="64" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button
            v-if="detail && detail.accountStatus === 'BANNED'"
            type="primary"
            @click="handleUnban(detail)"
          >解封该用户</el-button>
          <el-button
            v-else-if="detail && detail.accountStatus !== 'CANCELLED'"
            type="danger"
            @click="openBan(detail)"
          >封禁该用户</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- ==================== 封禁弹窗 ==================== -->
    <el-dialog
      v-model="banDialog.visible"
      title="封禁用户"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-alert
        class="block-gap"
        type="warning"
        :closable="false"
        show-icon
        title="封禁会让该用户的 App 端登录凭证立即失效（C 端再请求会 401），解封后才能恢复。"
      />
      <el-alert
        v-if="banDialog.error"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        :title="banDialog.error"
      />
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ banDialog.nickname || '—' }}</span>
          <span class="mono muted" style="margin-left: 8px">ID {{ banDialog.userId }}</span>
        </el-form-item>
        <el-form-item label="封禁原因" required>
          <el-input
            v-model="banDialog.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="必填：写清为什么封禁（会写入审计日志）"
          />
        </el-form-item>
        <el-form-item label="解封时间">
          <el-date-picker
            v-model="banDialog.until"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="可空：留空表示永久封禁"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="banDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="banDialog.submitting" @click="submitBan">确认封禁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="BizUser">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { banUser, getUserDetail, listUsers, unbanUser } from '@/api/biz/user'
import {
  describeRequestError,
  formatCents,
  formatDate,
  formatDateTime,
  textOrDash,
  toNumber
} from '@/views/biz/shared/format'

/** 实名状态文案（枚举出参是大写名字符串，见契约 §二）。 */
const REALNAME_STATUS_TEXT = {
  NONE: '未认证',
  PENDING: '审核中',
  APPROVED: '已认证',
  REJECTED: '已驳回'
}

/** 实名状态标签色：已认证=success、审核中=warning、驳回=danger、未认证=info。 */
const REALNAME_STATUS_TAG = {
  NONE: 'info',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger'
}

/** 实名状态下拉选项。 */
const REALNAME_STATUS_OPTIONS = Object.keys(REALNAME_STATUS_TEXT).map((value) => ({
  value,
  label: REALNAME_STATUS_TEXT[value]
}))

/** 账号状态文案。 */
const ACCOUNT_STATUS_TEXT = {
  NORMAL: '正常',
  BANNED: '已封禁',
  CANCELLED: '已注销'
}

/** 账号状态标签色：正常=success、封禁=danger、注销=info。 */
const ACCOUNT_STATUS_TAG = {
  NORMAL: 'success',
  BANNED: 'danger',
  CANCELLED: 'info'
}

/** 账号状态下拉选项。 */
const ACCOUNT_STATUS_OPTIONS = Object.keys(ACCOUNT_STATUS_TEXT).map((value) => ({
  value,
  label: ACCOUNT_STATUS_TEXT[value]
}))

/** 性别文案（服务端 AdminUserDetailVO.gender 是整数：0 未知 / 1 男 / 2 女，不是字符串枚举）。 */
const GENDER_TEXT = {
  0: '未设置',
  1: '男',
  2: '女'
}

/** 角色文案（本段只有 user / lawyer 两类；未知值原样回显）。 */
const ROLE_TEXT = {
  user: '普通用户',
  lawyer: '律师'
}

/** el-date-picker 的当日时间边界：起始 00:00:00、截止 23:59:59。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** 列表查询条件（分页 + 筛选）。 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  nickname: '',
  phone: '',
  realnameStatus: '',
  accountStatus: ''
})

/** 注册时间区间（[起始, 截止]，ISO 字符串）。 */
const timeRange = ref([])

/** 列表数据。 */
const rows = ref([])

/** 总条数（来自 res.total）。 */
const total = ref(0)

/** 列表加载中。 */
const loading = ref(false)

/** 列表错误信息（页面内常驻展示，配合重试按钮）。 */
const error = ref('')

/** 详情抽屉是否打开。 */
const drawerVisible = ref(false)

/** 详情加载中。 */
const detailLoading = ref(false)

/** 详情错误信息（抽屉内常驻展示）。 */
const detailError = ref('')

/** 当前详情（UserDetailVO）。 */
const detail = ref(null)

/** 当前详情对应的用户 ID（字符串；抽屉关闭后仍保留，供重试复用）。 */
const currentUserId = ref('')

/** 封禁弹窗状态。 */
const banDialog = reactive({
  visible: false,
  userId: '',
  nickname: '',
  reason: '',
  until: '',
  submitting: false,
  error: ''
})

/** 抽屉标题：有详情时带昵称，否则只显示通用标题。 */
const detailTitle = computed(() => {
  if (detail.value && detail.value.nickname) {
    return `用户详情 · ${detail.value.nickname}`
  }
  return '用户详情'
})

/**
 * 组装列表查询参数：空值一律不传，避免后端把空串当成「匹配空字符串」。
 *
 * @returns {object} 查询参数
 */
function buildParams() {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.nickname.trim()) {
    params.nickname = query.nickname.trim()
  }
  if (query.phone.trim()) {
    params.phone = query.phone.trim()
  }
  if (query.realnameStatus) {
    params.realnameStatus = query.realnameStatus
  }
  if (query.accountStatus) {
    params.accountStatus = query.accountStatus
  }
  if (Array.isArray(timeRange.value) && timeRange.value.length === 2) {
    params.beginTime = timeRange.value[0]
    params.endTime = timeRange.value[1]
  }
  return params
}

/**
 * 拉取用户列表。
 *
 * 副作用：一次 HTTP 请求；成功写 rows/total，失败把错误落到 error 供页面常驻展示。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const res = await listUsers(buildParams())
    rows.value = Array.isArray(res.rows) ? res.rows : []
    total.value = toNumber(res.total)
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = describeRequestError(e, '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 点「查询」：回到第 1 页再查。
 *
 * @returns {void}
 */
function handleSearch() {
  query.pageNum = 1
  loadList()
}

/**
 * 点「重置」：清空全部筛选条件并重查。
 *
 * @returns {void}
 */
function handleReset() {
  query.nickname = ''
  query.phone = ''
  query.realnameStatus = ''
  query.accountStatus = ''
  timeRange.value = []
  query.pageNum = 1
  loadList()
}

/**
 * 改每页条数：回到第 1 页。
 *
 * @returns {void}
 */
function handleSizeChange() {
  query.pageNum = 1
  loadList()
}

/**
 * 打开详情抽屉并拉详情。
 *
 * @param {{id:string, nickname?:string}} row 列表行（id 是字符串）
 * @returns {Promise<void>} 无返回值
 */
async function openDetail(row) {
  currentUserId.value = String(row.id)
  detail.value = null
  detailError.value = ''
  drawerVisible.value = true
  await loadDetail()
}

/**
 * 拉取当前用户详情（供抽屉与重试复用）。
 *
 * 副作用：一次 HTTP 请求；失败时 detail 置空并把错误写进 detailError。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadDetail() {
  if (!currentUserId.value) {
    return
  }
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await getUserDetail(currentUserId.value)
    detail.value = res.data || null
  } catch (e) {
    detail.value = null
    detailError.value = describeRequestError(e, '用户详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/**
 * 抽屉关闭回调：清掉详情，避免下次打开短暂显示上一个用户的数据。
 *
 * @returns {void}
 */
function handleDrawerClosed() {
  detail.value = null
  detailError.value = ''
}

/**
 * 打开封禁弹窗（列表行或详情对象都可用，二者都有 id / nickname）。
 *
 * @param {{id:string, nickname?:string}} row 用户行或详情
 * @returns {void}
 */
function openBan(row) {
  banDialog.userId = String(row.id)
  banDialog.nickname = row.nickname || ''
  banDialog.reason = ''
  banDialog.until = ''
  banDialog.error = ''
  banDialog.submitting = false
  banDialog.visible = true
}

/**
 * 提交封禁：前端校验原因 → 二次确认 → 调接口 → 刷新列表（必要时刷新抽屉）。
 *
 * 副作用：写操作（服务端会把 token_version +1，C 端旧 token 立即 401）。
 *
 * @returns {Promise<void>} 无返回值
 */
async function submitBan() {
  const reason = banDialog.reason.trim()
  if (!reason) {
    banDialog.error = '请填写封禁原因（后端同样会拦）'
    return
  }
  if (reason.length > 200) {
    banDialog.error = '封禁原因不能超过 200 字'
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认封禁用户「${banDialog.nickname || banDialog.userId}」？封禁后该用户 App 端旧登录凭证立即失效。`,
      '封禁确认',
      { type: 'warning', confirmButtonText: '确认封禁', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  banDialog.submitting = true
  banDialog.error = ''
  try {
    await banUser(banDialog.userId, {
      reason,
      until: banDialog.until || null
    })
    ElMessage.success('已封禁，该用户旧登录凭证已失效')
    banDialog.visible = false
    await loadList()
    if (drawerVisible.value && currentUserId.value === banDialog.userId) {
      await loadDetail()
    }
  } catch (e) {
    // 拦截器已弹过一次 toast，这里再落到弹窗里，方便用户改原因重试
    banDialog.error = describeRequestError(e, '封禁失败')
  } finally {
    banDialog.submitting = false
  }
}

/**
 * 解封用户：原因必填（用带校验的输入弹窗），成功后刷新列表与抽屉。
 *
 * 副作用：写操作；一次 HTTP 请求。
 *
 * @param {{id:string, nickname?:string}} row 用户行或详情
 * @returns {Promise<void>} 无返回值
 */
async function handleUnban(row) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('解封需要留下原因（≤200 字，会写入审计日志）', '解封确认', {
      confirmButtonText: '确认解封',
      cancelButtonText: '取消',
      inputPlaceholder: '如：误封，核实后恢复',
      inputValidator: (value) => {
        const text = (value || '').trim()
        if (!text) {
          return '请填写解封原因'
        }
        if (text.length > 200) {
          return '原因不能超过 200 字'
        }
        return true
      }
    })
    reason = result.value.trim()
  } catch (e) {
    return
  }
  try {
    await unbanUser(String(row.id), { reason })
    ElMessage.success('已解封，该用户可重新登录')
    await loadList()
    if (drawerVisible.value && currentUserId.value === String(row.id)) {
      await loadDetail()
    }
  } catch (e) {
    // 拦截器已提示；解封没有表单可改，保持页面数据不变即可
  }
}

/**
 * 实名状态文案。
 *
 * @param {string} status 枚举值（NONE|PENDING|APPROVED|REJECTED）
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
 * 账号状态文案。
 *
 * @param {string} status 枚举值（NORMAL|BANNED|CANCELLED）
 * @returns {string} 中文文案
 */
function accountText(status) {
  return ACCOUNT_STATUS_TEXT[status] || status || '—'
}

/**
 * 账号状态对应的 el-tag 类型。
 *
 * @param {string} status 枚举值
 * @returns {string} el-tag type
 */
function accountTagType(status) {
  return ACCOUNT_STATUS_TAG[status] || 'info'
}

/**
 * 性别文案。
 *
 * 服务端 `AdminUserDetailVO.gender` 是整数（0 未知 / 1 男 / 2 女），
 * 与「枚举出大写字符串」的约定**不同**——这是该 VO 已实现的出参形状，按实现对。
 *
 * @param {number|string} gender 接口原值
 * @returns {string} 中文文案（未知值原样回显）
 */
function genderText(gender) {
  if (gender === null || gender === undefined || gender === '') {
    return '未设置'
  }
  const text = GENDER_TEXT[gender] !== undefined ? GENDER_TEXT[gender] : GENDER_TEXT[String(gender)]
  return text !== undefined ? text : String(gender)
}

/**
 * 角色文案。
 *
 * @param {string} role 接口原值
 * @returns {string} 中文文案（未知值原样回显）
 */
function roleText(role) {
  if (!role) {
    return '—'
  }
  return ROLE_TEXT[role] || role
}

onMounted(loadList)
</script>

<style scoped>
/* 页头：标题 + 说明 + 右侧动作，间距只用 4/8/12/16 这一档 */
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

/* 错误提示条里放「错误信息 + 重试」，换行不挤压 */
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

/* 两行单元格：主文案 + 次文案（ID / 时间都用等宽数字，竖排能对齐） */
.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.cell-stack__main {
  color: var(--el-text-color-primary);
}

/* 右对齐列里的两行单元格：数字靠右排，竖着扫能对齐 */
.cell-stack--end {
  align-items: flex-end;
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

.section-title {
  margin: 20px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
