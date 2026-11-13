<!--
  职责：PC 后台 A-02 数据看板——页头数据时点 / 核心指标 4 卡 / 近 14 天订单趋势 /
        用户地区分布 / 待我处理 / 最近订单 / 导出报表，外加一条可折叠的链路自检。
  调用方：RuoYi 动态路由（sys_menu 2101「业务总览」component = biz/ops/Dashboard，
          route name = OpsDashboard；A-02 复用 2101，不新增菜单）。
  数据来源：src/api/biz/ops.js（第 7 段 A5 的 4 个看板接口 + 导出）、
            src/api/biz/order.js（段 5 订单列表，最近订单 4 条）、
            src/api/biz/dashboard.js（段 1 的 ping 链路自检）。
  注意：
    1. **所有数字都来自接口，页面不造数**：接口里没有的指标不显示估算值；
       某指标 `value` 为 null 且带 `pendingReason` 时显示「口径待定」，
       绝不显示 0 或空白（工作流 F3 第 4 条）。
    2. **涨跌配色：涨红跌绿**（国内财务习惯）。设计稿 `stat__delta--up` = `--state-success`（青绿）、
       `stat__delta--down` = `--state-danger`（红），即稿面是**涨绿跌红**；工作流 F1 正文写的是
       「涨用红、跌用绿」。两者矛盾，**负责人 2026-11-09 明确拍板：按「涨红跌绿」实现**，
       所以本页**故意与设计稿相反**（不是写错）。改回去只需交换下面两个 class 的颜色。
    3. **趋势图用 echarts 按需引入**（echarts/core + LineChart + 必要组件），
       不引全量包。稿面图表注释逐条落地：4 条水平网格线（0 起、3 段）、
       面积=品牌色 14% 透明、峰值只标一个点、横轴隔天标一个日期。
    4. **趋势固定近 14 天**：接口 `trends` 没有 range 参数（§五 5.2 第 2 号），
       所以切「今日/近 7 天/近 30 天」只改 4 张卡与页头，趋势图恒为近 14 天。
       工作流「趋势图同时变」与接口现状冲突，降级处理并记录，未在前端伪造联动。
    5. **待我处理跳转**：按 `key` 走本地路由映射（DashboardTodoVO 注释本身也要求
       前端按 key 跳转）；本地未登记的 key 才回退到服务端 link。
       ⚠️ 2026-11-13 菜单重整后前端路由不再是 `/biz/**`（见 database/seed/admin_menu_ia.sql），
       服务端 AdminDashboardService 里那 5 条 link 已同步改成新前缀；
       本地映射优先，所以即使后端没重新部署也不会跳错。
    6. **每 10 分钟自动刷新**：`setInterval` + `document.hidden` 时暂停，
       页面重新可见时立即刷新并重启计时；卸载时清定时器与 resize 监听、销毁图表。
    7. 链路自检条保留段 1 的 ping 自检能力（证明 RuoYi token → faze-api 鉴权桥通），
       折叠态不占版面，排障时展开即可看到服务端时间与管理员 id/名。
-->
<template>
  <div class="app-container dash">
    <!-- ==================== 页头 ==================== -->
    <div class="dash-head">
      <div>
        <h2 class="dash-title">数据看板</h2>
        <p class="dash-desc">
          数据统计截至 {{ dataAsOfText }}，每 10 分钟刷新一次
          <span class="dash-desc__muted">（页面加载后自动刷新，切到后台时暂停）</span>
        </p>
      </div>
      <div class="dash-actions">
        <div class="chips" role="group" aria-label="时间范围">
          <button
            v-for="option in RANGE_OPTIONS"
            :key="option.value"
            type="button"
            class="chip"
            :class="{ 'chip--active': range === option.value }"
            @click="handleRangeChange(option.value)"
          >{{ option.label }}</button>
        </div>
        <el-button :loading="exporting" @click="handleExport">
          <el-icon><Download /></el-icon>
          <span>导出报表</span>
        </el-button>
      </div>
    </div>

    <!-- ==================== 链路自检（可折叠，段 1 能力保留） ==================== -->
    <el-collapse v-model="selfCheckOpen" class="self-check">
      <el-collapse-item name="ping">
        <template #title>
          <span class="self-check__title">
            链路自检
            <el-tag v-if="pingOk" type="success" size="small" effect="plain">已连通</el-tag>
            <el-tag v-else-if="pingError" type="danger" size="small" effect="plain">未连通</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">自检中</el-tag>
            <span class="self-check__hint">业务服务 → 管理端鉴权</span>
          </span>
        </template>
        <el-alert
          v-if="pingError"
          :title="'业务接口链路未通：' + pingError"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="服务端时间">{{ ping.serverTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="管理员">{{ ping.adminName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="管理员 ID">{{ ping.adminId || '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-collapse-item>
    </el-collapse>

    <!-- ==================== 核心指标 4 卡 ==================== -->
    <el-alert
      v-if="summaryError"
      :title="'核心指标加载失败：' + summaryError"
      type="error"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <div v-loading="summaryLoading" class="stat-grid">
      <el-card v-for="card in cards" :key="card.key" shadow="never" class="stat-card">
        <div class="stat-label">
          <span>{{ card.label }}</span>
          <el-icon class="stat-icon"><component :is="card.icon" /></el-icon>
        </div>
        <el-tooltip
          v-if="card.pending"
          placement="top"
          :content="card.pendingReason || '该指标口径待定，暂不展示数值'"
        >
          <div class="stat-value stat-value--pending">口径待定</div>
        </el-tooltip>
        <div v-else class="stat-value">
          <template v-if="card.money">
            <small>¥</small><span>{{ formatMoney(card.value) }}</span>
          </template>
          <template v-else>{{ formatCount(card.value) }}</template>
        </div>
        <div class="stat-foot">
          <template v-if="card.pending">
            <span class="stat-foot__muted">口径待定，暂不计算环比</span>
          </template>
          <template v-else>
            <span class="stat-delta" :class="deltaClass(card.delta)">
              <span v-if="card.delta !== null && card.delta !== undefined" class="stat-delta__arrow">{{ card.delta > 0 ? '▲' : (card.delta < 0 ? '▼' : '') }}</span>
              {{ deltaText(card.delta) }}
            </span>
            <template v-if="card.money">
              <el-tooltip content="= 当日订单服务费合计，不含充值金额" placement="top">
                <span class="stat-foot__note">已扣除平台服务费</span>
              </el-tooltip>
            </template>
            <span v-else>{{ compareLabel }}</span>
          </template>
        </div>
      </el-card>
    </div>

    <!-- ==================== 趋势 + 地区分布 ==================== -->
    <div class="dash-row">
      <el-card shadow="never" class="dash-chart">
        <template #header>
          <div class="card-head">
            <span class="card-title">订单量趋势</span>
            <span class="card-extra">近 14 天 · 单位：单</span>
          </div>
        </template>
        <div class="chart-body">
          <el-alert
            v-if="trendError"
            :title="'趋势加载失败：' + trendError"
            type="error"
            :closable="false"
            show-icon
            style="margin-bottom: 8px"
          />
          <el-empty
            v-if="!trendLoading && !trendError && trend.length === 0"
            description="暂无订单数据"
            :image-size="80"
          />
          <div v-show="trend.length > 0" ref="chartRef" class="chart" />
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">用户地区分布</span>
            <span class="card-extra">按注册地</span>
          </div>
        </template>
        <div v-loading="regionLoading" class="region-body">
          <el-alert
            v-if="regionError"
            :title="'地区分布加载失败：' + regionError"
            type="error"
            :closable="false"
            show-icon
          />
          <el-empty
            v-else-if="!regionLoading && regions.length === 0"
            description="暂无地区数据"
            :image-size="80"
          />
          <div v-else class="bar-list">
            <div v-for="item in regions" :key="item.name" class="bar-row">
              <span class="bar-row__label" :title="item.name">{{ item.name }}</span>
              <span class="bar-row__track">
                <i class="bar-row__fill" :style="{ width: regionBarWidth(item.count) }" />
              </span>
              <span class="bar-row__value">{{ formatCount(item.count) }}</span>
            </div>
          </div>
          <p v-if="regionNote && regions.length > 0" class="region-note">{{ regionNote }}</p>
        </div>
      </el-card>
    </div>

    <!-- ==================== 待处理 + 最近订单 ==================== -->
    <div class="dash-row dash-row--bottom">
      <el-card shadow="never" class="todo-card">
        <template #header>
          <div class="card-head">
            <span class="card-title">待我处理</span>
            <span class="card-extra">共 {{ formatCount(todoTotal) }} 条</span>
          </div>
        </template>
        <div v-loading="todoLoading" class="todo-body">
          <el-alert
            v-if="todoError"
            :title="'待办加载失败：' + todoError"
            type="error"
            :closable="false"
            show-icon
          />
          <el-empty
            v-else-if="!todoLoading && todoItems.length === 0"
            description="暂无待处理事项"
            :image-size="80"
          />
          <button
            v-for="item in todoItems"
            v-else
            :key="item.key"
            type="button"
            class="todo-item"
            @click="goTodo(item)"
          >
            <span class="todo-icon">
              <el-icon><component :is="todoIcon(item.key)" /></el-icon>
            </span>
            <span class="todo-text">
              {{ item.label }}
              <span v-if="item.extra" class="todo-text__sub">{{ item.extra }}</span>
            </span>
            <span class="todo-count" :class="{ 'todo-count--hot': toNumber(item.count) >= 10 }">
              {{ formatCount(item.count) }}
            </span>
            <el-icon class="todo-go"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="todo-foot">
          <span>另有 {{ formatCount(contentReviewCount) }} 条内容审核命中待处理</span>
          <el-button link type="primary" @click="goContentReview">去审核队列</el-button>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">最近订单</span>
            <el-button link type="primary" @click="goOrderList">查看全部</el-button>
          </div>
        </template>
        <div v-loading="orderLoading" class="order-body">
          <el-alert
            v-if="orderError"
            :title="'最近订单加载失败：' + orderError"
            type="error"
            :closable="false"
            show-icon
          />
          <el-empty
            v-else-if="!orderLoading && orders.length === 0"
            description="暂无订单"
            :image-size="80"
          />
          <table v-else class="mini-order">
            <thead>
              <tr>
                <th style="width: 34%">订单号</th>
                <th style="width: 24%">用户</th>
                <th style="width: 22%">金额</th>
                <th style="width: 20%">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="order in orders" :key="order.id">
                <td class="mono">{{ order.orderNo }}</td>
                <td>{{ textOrDash(order.userName) }}</td>
                <td class="mono">¥ {{ formatCents(order.amountCents) }}</td>
                <td>
                  <el-tag :type="orderStatusTag(order.status)" size="small" effect="light">
                    {{ orderStatusText(order.status) }}
                  </el-tag>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup name="OpsDashboard">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, ChatDotRound, DataLine, Document, Download, Postcard, User, UserFilled, Wallet } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkPointComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  exportDashboard,
  getDashboardRegions,
  getDashboardSummary,
  getDashboardTodos,
  getDashboardTrends
} from '@/api/biz/ops'
import { getDashboardPing } from '@/api/biz/dashboard'
import { listOrders } from '@/api/biz/order'
import { describeRequestError, formatCents, formatDateTime, textOrDash, toNumber } from '@/views/biz/shared/format'
import { buildExportFileName, classifyExportPayload, downloadBlob } from '@/views/biz/shared/exportDownload'

// echarts 按需注册：折线图 + 网格/提示框/标记点 + Canvas 渲染器。
// **不要** `import * as echarts from 'echarts'`（全量包 ~1MB，后台不需要为它买单）。
echarts.use([LineChart, GridComponent, TooltipComponent, MarkPointComponent, CanvasRenderer])

/** 自动刷新间隔：10 分钟（稿面文案「每 10 分钟刷新一次」）。 */
const REFRESH_INTERVAL_MS = 10 * 60 * 1000

/** 时间范围选项（稿面 chips）。 */
const RANGE_OPTIONS = [
  { value: 'today', label: '今日' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' }
]

/** 趋势图颜色：与设计稿 tokens.css 的 --chart-line / --chart-line-soft / --chart-grid 对齐。 */
const CHART_LINE_COLOR = '#4a66ee'
const CHART_GRID_COLOR = '#eef0f5'

/** 待我处理：按 key 的本地跳转映射（服务端 link 的工单路径有笔误，见文件头注释第 5 条）。 */
const TODO_TARGETS = {
  realname: { path: '/members/verification', query: { type: 'realname', status: 'PENDING' } },
  lawyerApplication: { path: '/lawyer/audit', query: { status: 'PENDING' } },
  withdraw: { path: '/finance/withdraw', query: { status: 'applying' } },
  ticket: { path: '/ticket/list', query: { tab: 'processing' } },
  contentReview: { path: '/caseflow/review', query: { status: 'pending' } }
}

/** 待我处理图标（按 key，不按 label——label 会因文案调整而变化）。 */
const TODO_ICONS = {
  realname: Postcard,
  lawyerApplication: UserFilled,
  withdraw: Wallet,
  ticket: ChatDotRound,
  contentReview: DataLine
}

/** 订单状态文案（与 A-11 订单列表同一套，段 2 八态 + 段 5 追加态）。 */
const ORDER_STATUS_TEXT = {
  draft: '草稿',
  pending: '待支付',
  paid: '等待接单',
  in_service: '进行中',
  completed: '已完成',
  cancelled: '已取消',
  refunding: '退款中',
  refunded: '已退款',
  refund_rejected: '退款被驳回'
}

/** 订单状态标签色。 */
const ORDER_STATUS_TAG = {
  draft: 'info',
  pending: 'warning',
  paid: 'warning',
  in_service: 'primary',
  completed: 'success',
  cancelled: 'info',
  refunding: 'warning',
  refunded: 'danger',
  refund_rejected: 'danger'
}

const router = useRouter()

const range = ref('today')
const summary = ref({})
const summaryLoading = ref(false)
const summaryError = ref('')
const exporting = ref(false)
const selfCheckOpen = ref([])
const ping = ref({})
const pingOk = ref(false)
const pingError = ref('')

const trend = ref([])
const trendLoading = ref(false)
const trendError = ref('')
const chartRef = ref(null)

const regions = ref([])
const regionNote = ref('')
const regionLoading = ref(false)
const regionError = ref('')

const todos = ref([])
const todoLoading = ref(false)
const todoError = ref('')

const orders = ref([])
const orderLoading = ref(false)
const orderError = ref('')

/** echarts 实例（onBeforeUnmount 时销毁）。 */
let chart = null
/** 自动刷新定时器。 */
let refreshTimer = null

/** 时间范围中文名。 */
const rangeLabel = computed(() => {
  const hit = RANGE_OPTIONS.find((item) => item.value === range.value)
  return hit ? hit.label : '今日'
})

/** 卡片口径前缀：今日 / 近 7 天 / 近 30 天。 */
const scopeText = computed(() => (range.value === 'today' ? '今日' : rangeLabel.value))

/** 环比文案：今日→较昨日；7 天/30 天→较上 7 天 / 较上 30 天。 */
const compareLabel = computed(() => {
  if (range.value === 'today') {
    return '较昨日'
  }
  return `较上 ${rangeLabel.value.replace('近 ', '')}`
})

/** 数据统计截止时间（接口给 UTC，页面按 +8 展示）。 */
const dataAsOfText = computed(() => formatDateTime(summary.value.dataAsOf))

/**
 * 取一个指标的数值与「口径待定」说明。
 *
 * 服务端实际形状（be-api 2026-11-09 交付）：`pendingReason` 是整包第一个待定原因，
 * `pendingReasons` 是「字段名 → 原因」的映射（如 `{onlineCount: "..."}`）。
 * 前端优先按字段名取；**只有后端完全没给 `pendingReasons` 时才回退全局 `pendingReason`**
 * （旧形状兼容），否则「onlineCount 待定」会被错误地挂到别的 null 卡片上。
 * 还兼容 `summary[key] = {value, pendingReason}` 的对象形状与 `summary[key+'PendingReason']`。
 * 只有「值为 null 且确有 pendingReason」才显示口径待定；**不把 null 当 0**。
 *
 * @param {object} source summary 响应 data
 * @param {string} key 指标键，如 dailyActive
 * @returns {{value: (number|string|null), pendingReason: (string|null)}} 指标值与待定原因
 */
function metricOf(source, key) {
  const raw = source ? source[key] : null
  if (raw !== null && raw !== undefined && typeof raw === 'object' && 'value' in raw) {
    return {
      value: raw.value === undefined ? null : raw.value,
      pendingReason: raw.pendingReason || raw.reason || null
    }
  }
  if (raw === null || raw === undefined) {
    const fieldReasons = source && source.pendingReasons
    const fieldReason = (fieldReasons && fieldReasons[key])
      || (source && source[key + 'PendingReason'])
      || null
    if (fieldReason) {
      return { value: null, pendingReason: fieldReason }
    }
    // 旧后端只给一个全局 pendingReason：没有字段级映射时才用它兜底
    const globalReason = (!fieldReasons && source && source.pendingReason) || null
    return { value: null, pendingReason: globalReason }
  }
  return { value: raw, pendingReason: null }
}

/**
 * 组装 4 张核心指标卡。
 *
 * @returns {object[]} 卡片数组（label / value / delta / pending / money / icon）
 */
const cards = computed(() => {
  const source = summary.value || {}
  const active = metricOf(source, 'dailyActive')
  const newUsers = metricOf(source, 'newUsers')
  const orderCount = metricOf(source, 'orderCount')
  const revenue = metricOf(source, 'revenueCents')
  return [
    {
      key: 'dailyActive',
      label: `${scopeText.value}活跃用户`,
      value: active.value,
      pending: !!active.pendingReason,
      pendingReason: active.pendingReason,
      delta: source.dailyActiveDelta,
      money: false,
      icon: User
    },
    {
      key: 'newUsers',
      label: `${scopeText.value}新增注册`,
      value: newUsers.value,
      pending: !!newUsers.pendingReason,
      pendingReason: newUsers.pendingReason,
      delta: source.newUsersDelta,
      money: false,
      icon: UserFilled
    },
    {
      key: 'orderCount',
      label: `${scopeText.value}订单`,
      value: orderCount.value,
      pending: !!orderCount.pendingReason,
      pendingReason: orderCount.pendingReason,
      delta: source.orderCountDelta,
      money: false,
      icon: Document
    },
    {
      key: 'revenueCents',
      label: `平台${scopeText.value}收入`,
      value: revenue.value,
      pending: !!revenue.pendingReason,
      pendingReason: revenue.pendingReason,
      delta: source.revenueDelta,
      money: true,
      icon: Wallet
    }
  ]
})

/** 待办列表：服务端 5 类里的前 4 类走列表，内容审核按稿面放卡片底部汇总行。 */
const todoItems = computed(() => todos.value.filter((item) => item.key !== 'contentReview'))

/** 内容审核待处理数（稿面「另有 N 条内容审核命中待处理」）。 */
const contentReviewCount = computed(() => {
  const hit = todos.value.find((item) => item.key === 'contentReview')
  return hit ? toNumber(hit.count) : 0
})

/** 待办合计（与稿面「共 N 条」一致：只算上面列出的条目，内容审核在底部单列）。 */
const todoTotal = computed(() => todoItems.value.reduce((sum, item) => sum + toNumber(item.count), 0))

/** 地区条形图的最大值（用于算条宽）。 */
const regionMax = computed(() => {
  return regions.value.reduce((max, item) => Math.max(max, toNumber(item.count)), 0)
})

/**
 * 数值格式化（整数千分位）。
 *
 * @param {number|string|null|undefined} value 数值
 * @returns {string} 千分位文本；空值回 `—`
 */
function formatCount(value) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return String(toNumber(value)).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * 金额格式化：整数分 → 带千分位的元（**整数运算，不浮点累加**）。
 *
 * 换算本体在 `shared/format.js` 的 `formatCents`，这里只多一条「空值回破折号」——
 * KPI 卡片拿不到数时显示 `—` 比显示 `0.00` 诚实。
 *
 * @param {number|string|null|undefined} cents 金额（分）
 * @returns {string} 形如 `12,860.00`；空值回 `—`
 */
function formatMoney(cents) {
  if (cents === null || cents === undefined || cents === '') {
    return '—'
  }
  return formatCents(cents)
}

/**
 * 环比文案。
 *
 * @param {number|null|undefined} delta 环比百分比
 * @returns {string} 形如 `+8.2%`；null（昨日为 0）回 `—`
 */
function deltaText(delta) {
  if (delta === null || delta === undefined || delta === '') {
    return '—'
  }
  const value = Number(delta)
  if (!Number.isFinite(value)) {
    return '—'
  }
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(1)}%`
}

/**
 * 环比配色 class（**以稿为准：涨绿跌红**）。
 *
 * @param {number|null|undefined} delta 环比
 * @returns {string} class 名
 */
function deltaClass(delta) {
  if (delta === null || delta === undefined || delta === '') {
    return 'stat-delta--none'
  }
  const value = Number(delta)
  if (!Number.isFinite(value) || value === 0) {
    return 'stat-delta--none'
  }
  return value > 0 ? 'stat-delta--up' : 'stat-delta--down'
}

/**
 * 地区条宽（相对最大值，最小 4% 保证可见——稿面「其他 24 人」也画了 4%）。
 *
 * @param {number|string} count 该地区人数
 * @returns {string} 百分比宽度
 */
function regionBarWidth(count) {
  const max = regionMax.value
  if (max <= 0) {
    return '0%'
  }
  return `${Math.max(4, Math.round((toNumber(count) / max) * 100))}%`
}

/**
 * 待办图标。
 *
 * @param {string} key 待办 key
 * @returns {object} Element Plus 图标组件
 */
function todoIcon(key) {
  return TODO_ICONS[key] || DataLine
}

/**
 * 解析服务端 link（`/path?x=y`）。
 *
 * @param {string} link 跳转地址
 * @returns {{path:string, query:object}|null} 路由目标
 */
function parseServerLink(link) {
  if (!link || typeof link !== 'string') {
    return null
  }
  const [path, queryText] = link.split('?')
  if (!path) {
    return null
  }
  const query = {}
  if (queryText) {
    for (const pair of queryText.split('&')) {
      if (!pair) continue
      const [key, value = ''] = pair.split('=')
      query[decodeURIComponent(key)] = decodeURIComponent(value)
    }
  }
  return { path, query }
}

/**
 * 待办跳转目标：本地映射优先（修正服务端工单路径笔误），未登记的 key 用服务端 link。
 *
 * @param {object} item 待办项
 * @returns {{path:string, query:object}|null} 路由目标
 */
function todoTarget(item) {
  return TODO_TARGETS[item.key] || parseServerLink(item.link)
}

/**
 * 点待办跳转。
 *
 * @param {object} item 待办项
 */
function goTodo(item) {
  const target = todoTarget(item)
  if (!target) {
    ElMessage.warning('该待办暂未配置跳转目标')
    return
  }
  router.push(target)
}

/** 跳内容审核队列（稿面底部「去审核队列」）。 */
function goContentReview() {
  router.push(TODO_TARGETS.contentReview)
}

/** 跳订单列表（稿面「查看全部」）。 */
function goOrderList() {
  router.push('/order/list')
}

/**
 * 订单状态文案。
 *
 * @param {string} status 状态码
 * @returns {string} 中文文案
 */
function orderStatusText(status) {
  return ORDER_STATUS_TEXT[status] || status || '—'
}

/**
 * 订单状态标签色。
 *
 * @param {string} status 状态码
 * @returns {string} tag type
 */
function orderStatusTag(status) {
  return ORDER_STATUS_TAG[status] || 'info'
}

/**
 * 拉取核心指标。
 *
 * 副作用：一次 HTTP 请求；写 summary / summaryError。
 */
async function loadSummary() {
  summaryLoading.value = true
  summaryError.value = ''
  try {
    const res = await getDashboardSummary(range.value)
    summary.value = res.data || {}
  } catch (error) {
    summaryError.value = describeRequestError(error, '核心指标加载失败')
  } finally {
    summaryLoading.value = false
  }
}

/**
 * 拉取近 14 天订单趋势并重绘图表。
 *
 * 副作用：一次 HTTP 请求；写 trend / trendError；重绘 echarts。
 */
async function loadTrends() {
  trendLoading.value = true
  trendError.value = ''
  try {
    const res = await getDashboardTrends('orders')
    trend.value = (res.data && res.data.points) || []
    await nextTick()
    renderChart()
  } catch (error) {
    trendError.value = describeRequestError(error, '趋势加载失败')
  } finally {
    trendLoading.value = false
  }
}

/**
 * 拉取地区分布。
 *
 * 副作用：一次 HTTP 请求；写 regions / regionNote / regionError。
 */
async function loadRegions() {
  regionLoading.value = true
  regionError.value = ''
  try {
    const res = await getDashboardRegions()
    regions.value = (res.data && res.data.list) || []
    regionNote.value = (res.data && res.data.note) || ''
  } catch (error) {
    regionError.value = describeRequestError(error, '地区分布加载失败')
  } finally {
    regionLoading.value = false
  }
}

/**
 * 拉取待我处理。
 *
 * 副作用：一次 HTTP 请求；写 todos / todoError。
 */
async function loadTodos() {
  todoLoading.value = true
  todoError.value = ''
  try {
    const res = await getDashboardTodos()
    todos.value = res.data || []
  } catch (error) {
    todoError.value = describeRequestError(error, '待办加载失败')
  } finally {
    todoLoading.value = false
  }
}

/**
 * 拉取最近 4 笔订单（复用段 5 订单列表接口；该接口是 RuoYi 口径 rows/total）。
 *
 * 副作用：一次 HTTP 请求；写 orders / orderError。
 */
async function loadRecentOrders() {
  orderLoading.value = true
  orderError.value = ''
  try {
    const res = await listOrders({ pageNum: 1, pageSize: 4 })
    orders.value = (res && res.rows) || []
  } catch (error) {
    orderError.value = describeRequestError(error, '最近订单加载失败')
  } finally {
    orderLoading.value = false
  }
}

/**
 * 链路自检（段 1 的 ping）：证明 RuoYi token 被 faze-api 认。
 *
 * 副作用：一次 HTTP 请求；写 ping / pingOk / pingError。
 */
async function loadPing() {
  try {
    const res = await getDashboardPing()
    ping.value = res.data || {}
    pingOk.value = true
    pingError.value = ''
  } catch (error) {
    pingOk.value = false
    pingError.value = describeRequestError(error, '链路自检失败')
  }
}

/**
 * 全量刷新（首屏 + 10 分钟定时 + 页面重新可见时）。
 */
async function refreshAll() {
  await Promise.all([loadSummary(), loadTrends(), loadRegions(), loadTodos(), loadRecentOrders()])
}

/**
 * 切换时间范围：只重拉核心指标（趋势接口无 range 参数，恒为近 14 天）。
 *
 * @param {string} value today|7d|30d
 */
function handleRangeChange(value) {
  if (range.value === value) {
    return
  }
  range.value = value
  loadSummary()
}

/**
 * 计算纵轴刻度步长：保证「0 起 + 3 段 = 4 条网格线」且覆盖数据峰值。
 *
 * 优先取整十档（1/2/5 系列），45 这类「设计稿峰值 41 → 纵轴 45」也能落进候选。
 *
 * @param {number} maxValue 数据最大值
 * @returns {number} 步长
 */
function niceAxisStep(maxValue) {
  const raw = Math.max(maxValue, 0) / 3
  const candidates = [1, 2, 5, 10, 15, 20, 25, 30, 45, 50, 60, 75, 100, 150, 200, 300, 500, 750, 1000, 1500, 2000, 5000]
  for (const step of candidates) {
    if (step >= raw) {
      return step
    }
  }
  return Math.ceil(raw / 1000) * 1000
}

/**
 * 渲染近 14 天订单折线。
 *
 * 逐条对齐稿面注释：
 *   - 网格线 4 条水平线（0 起，3 段）；
 *   - 面积 = 品牌色 14% 透明、从折线到基线；
 *   - 峰值点只标一个（不画满图圆点）；
 *   - 横轴隔天标一个日期。
 *
 * 副作用：初始化/更新 echarts 实例；无数据时清空画布。
 */
function renderChart() {
  if (!chartRef.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const points = trend.value
  if (!points.length) {
    chart.clear()
    return
  }
  const dates = points.map((point) => point.date)
  const values = points.map((point) => toNumber(point.value))
  const maxValue = values.reduce((max, value) => Math.max(max, value), 0)
  const step = niceAxisStep(maxValue)
  const maxAxis = step * 3
  const peakIndex = values.indexOf(maxValue)
  const peak = maxValue > 0 ? { date: dates[peakIndex], value: maxValue } : null

  chart.setOption({
    animation: false,
    grid: { left: 40, right: 16, top: 32, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      formatter: (items) => {
        const hit = items && items[0]
        if (!hit) {
          return ''
        }
        return `${hit.axisValue}　${hit.data} 单`
      }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLine: { lineStyle: { color: CHART_GRID_COLOR } },
      axisTick: { show: false },
      // 横轴隔天标一个日期（14 个点 → 7 个标签，与稿面 10-16/10-18/…/10-28 一致）
      axisLabel: {
        interval: 1,
        fontSize: 12,
        color: '#6b7280',
        formatter: (value) => String(value).slice(5)
      }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: maxAxis,
      splitNumber: 3,
      axisLabel: { fontSize: 12, color: '#6b7280' },
      splitLine: { lineStyle: { color: CHART_GRID_COLOR, width: 1 } }
    },
    series: [
      {
        name: '订单量',
        type: 'line',
        data: values,
        showSymbol: false,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: CHART_LINE_COLOR, width: 2 },
        itemStyle: { color: CHART_LINE_COLOR },
        // 面积：品牌色 14% 透明，从折线到基线
        areaStyle: { color: CHART_LINE_COLOR, opacity: 0.14 },
        // 峰值点：只标一个点 + 一个数值标签
        markPoint: peak
          ? {
              symbol: 'circle',
              symbolSize: 7,
              itemStyle: { color: '#ffffff', borderColor: CHART_LINE_COLOR, borderWidth: 2 },
              label: {
                show: true,
                position: 'top',
                distance: 8,
                formatter: `${peak.date.slice(5)} · ${peak.value}`,
                color: '#333333',
                fontSize: 11,
                backgroundColor: '#fafbfc',
                padding: [3, 6],
                borderRadius: 3
              },
              data: [{ name: '峰值', coord: [peak.date, peak.value] }]
            }
          : undefined
      }
    ]
  }, true)
}

/** 窗口尺寸变化时重算图表大小。 */
function resizeChart() {
  if (chart) {
    chart.resize()
  }
}

/** 清自动刷新定时器。 */
function stopRefreshTimer() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

/** 启动 10 分钟自动刷新（页面隐藏时由 visibilitychange 暂停）。 */
function startRefreshTimer() {
  stopRefreshTimer()
  refreshTimer = setInterval(() => {
    if (!document.hidden) {
      refreshAll()
    }
  }, REFRESH_INTERVAL_MS)
}

/** 页面可见性变化：切到后台暂停、切回来立即刷新并重启计时。 */
function handleVisibilityChange() {
  if (document.hidden) {
    stopRefreshTimer()
  } else {
    refreshAll()
    startRefreshTimer()
  }
}

/**
 * 导出报表（CSV）。
 *
 * 副作用：一次 blob 请求 + 触发浏览器下载；行数超同步上限时服务端转异步（410015 + taskId），
 * 本页按提示告知而不把 JSON 存成 .csv；接口未交付（404）时给出明确解释。
 */
async function handleExport() {
  exporting.value = true
  try {
    const payload = await exportDashboard({ range: range.value })
    const result = await classifyExportPayload(payload)
    if (result.kind === 'file') {
      downloadBlob(payload, buildExportFileName('dashboard'))
      ElMessage.success('报表已开始下载')
      return
    }
    if (result.kind === 'async') {
      ElMessage.warning(
        `本次导出超过 5000 行同步上限，服务端已转后台任务`
        + (result.taskId ? `（taskId：${result.taskId}）` : '')
        + '，完成后再下载。'
      )
      return
    }
    ElMessage.error(result.msg || '导出报表失败')
  } catch (error) {
    ElMessage.error(describeRequestError(error, '导出报表失败'))
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  refreshAll()
  loadPing()
  startRefreshTimer()
  window.addEventListener('resize', resizeChart)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  stopRefreshTimer()
  window.removeEventListener('resize', resizeChart)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
/* 页头与稿面一致：标题 + 数据时点，右侧 chips + 导出 */
.dash-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 12px;
}
.dash-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.dash-desc {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.dash-desc__muted {
  color: var(--el-text-color-placeholder);
}
.dash-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
/* 时间范围 chips：照稿面 pill 形，选中用品牌色软底 */
.chips {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chip {
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  cursor: pointer;
}
.chip:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}
.chip--active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
  font-weight: 500;
}
/* 链路自检条：默认折叠，只占一行 */
.self-check {
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-bg-color);
}
.self-check :deep(.el-collapse-item__header) {
  padding-left: 16px;
  border: 0;
}
.self-check :deep(.el-collapse-item__wrap) {
  border: 0;
}
.self-check :deep(.el-collapse-item__content) {
  padding: 0 16px 12px;
}
.self-check__title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.self-check__hint {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
/* 核心指标 4 卡：稿面 stat 压薄 padding，一屏装下指标 + 图表 + 待办 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}
.stat-card {
  min-height: 96px;
}
.stat-card :deep(.el-card__body) {
  padding: 12px 20px;
}
.stat-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.stat-icon {
  color: var(--el-text-color-placeholder);
}
.stat-value {
  margin-top: 6px;
  font-family: 'Inter', 'SF Mono', ui-monospace, 'PingFang SC', system-ui, sans-serif;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--el-text-color-primary);
}
.stat-value small {
  margin-right: 2px;
  font-family: inherit;
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.stat-value--pending {
  color: var(--el-text-color-secondary);
  font-size: 24px;
  cursor: help;
}
.stat-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-delta {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-weight: 500;
}
/* 涨跌配色：**涨红跌绿**（负责人 2026-11-09 拍板，国内财务习惯；与设计稿的涨绿跌红相反） */
.stat-delta--up {
  color: #ee0a24;
}
.stat-delta--down {
  color: #1f7a7a;
}
.stat-delta--none {
  color: var(--el-text-color-placeholder);
}
.stat-delta__arrow {
  font-size: 9px;
}
.stat-foot__note {
  cursor: help;
  border-bottom: 1px dashed var(--el-text-color-placeholder);
}
.stat-foot__muted {
  color: var(--el-text-color-placeholder);
}
/* 第二行：趋势宽 + 分布窄（稿面 1.62fr / 1fr） */
.dash-row {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(0, 1fr);
  gap: 16px;
  margin-top: 12px;
}
.dash-row--bottom {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.32fr);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.card-extra {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 左卡图表垂直居中（稿面注释：避免空白全堆在底部） */
.dash-chart :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 12px 20px;
}
.chart-body {
  width: 100%;
}
.chart {
  width: 100%;
  height: 200px;
}
/* 地区分布条 */
.region-body {
  min-height: 200px;
}
.bar-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bar-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 52px;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.bar-row__label {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.bar-row__track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--el-fill-color-light);
}
.bar-row__fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--el-color-primary);
}
.bar-row__value {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  color: var(--el-text-color-regular);
  text-align: right;
}
.region-note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-placeholder);
}
/* 待办列表：每行可点 */
.todo-body {
  display: flex;
  flex-direction: column;
}
.todo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 7px 20px;
  border: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.todo-item:last-child {
  border-bottom: 0;
}
.todo-item:hover {
  background: var(--el-fill-color-light);
}
.todo-icon {
  display: flex;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 15px;
}
.todo-text {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.todo-text__sub {
  display: block;
  margin-top: 1px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.todo-count {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 17px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.todo-count--hot {
  color: #cc0a20;
}
.todo-go {
  flex: 0 0 14px;
  color: var(--el-text-color-placeholder);
}
.todo-card :deep(.el-card__body) {
  padding: 0;
}
.todo-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 最近订单：紧凑四列 */
.order-body {
  min-height: 180px;
}
.mini-order {
  width: 100%;
  border-collapse: collapse;
}
.mini-order th {
  height: 34px;
  padding: 0 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 500;
  text-align: left;
}
.mini-order td {
  height: 42px;
  padding: 0 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: nowrap;
}
.mini-order tbody tr:last-child td {
  border-bottom: 0;
}
.mini-order tbody tr:hover td {
  background: var(--el-fill-color-light);
}
.mono {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
}
</style>
