<!--
  职责：PC A-09 对账报表 —— 恒等式巡检摘要、手动重新巡检、差异列表（正常时为空表 + 明确的「差异 = 0」）。
  调用方：RuoYi 动态路由（sys_menu 2108，component = biz/reconciliation/index，route name = BizReconciliation）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET  /api/v1/admin/reconciliation/summary  摘要（R<{checkedOwners,balancedOwners,diffOwners,totalCheckedCents,checkedAt}>）
    GET  /api/v1/admin/reconciliation/diffs    差异列表（R<List<{ownerType,ownerId,cacheCents,flowCents,diffCents}>>）
    POST /api/v1/admin/reconciliation/run      手动巡检（R<summary>，服务端写 operation_log：RECONCILE_RUN）
  注意：
    1. **口径**：按 (owner_type, owner_id) 汇总 balance_flow.delta_cents，
       与 user_balance.balance_cents + frozen_cents 比对（与 deploy/verify-balance.ps1 同一恒等式）。
    2. **不建 reconciliation_diff 表**：差异是**现算的内存态**，两次调用之间数据可能变，
       所以每次刷新都把 summary 与 diffs 一起重取，不能只刷一个。
    3. **契约缺口**：summary 里没有「差异总额」字段（只有 totalCheckedCents = 被检查余额合计），
       而页面要点要求展示「差异总额」。本页用 `/diffs` 的 |diffCents| 求和（整数分相加）后换算展示，
       并在标签上写明「绝对值合计」——不冒充服务端口径，也不把 totalCheckedCents 当差异总额。
    4. 金额一律整数分：合计先用整数相加，再 `formatCents` 换算；不做浮点累加。
    5. 四态：加载中（v-loading）/ 空（el-empty，明确写「差异 = 0」）/ 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">对账报表</h1>
        <p class="page-head__desc">
          巡检资金恒等式：Σ balance_flow.delta_cents 应等于 user_balance.balance_cents + frozen_cents。
          差异为现算结果，不落库。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :loading="summaryLoading || diffsLoading" @click="loadAll">刷新</el-button>
        <el-button type="primary" :loading="running" @click="handleRun">重新巡检</el-button>
      </div>
    </div>

    <!-- ==================== 恒等式说明 ==================== -->
    <el-alert class="block-gap" type="info" :closable="false" show-icon>
      <template #title>巡检口径</template>
      <div class="alert-body">
        按 (owner_type, owner_id) 汇总 balance_flow.delta_cents，与 user_balance.balance_cents + frozen_cents 比对；
        与 deploy/verify-balance.ps1 使用同一恒等式。结果为内存态现算，页面每次刷新都会重新取一次。
      </div>
    </el-alert>

    <!-- ==================== 错误态（常驻 + 可重试） ==================== -->
    <el-alert
      v-if="summaryError"
      class="block-gap"
      type="error"
      :closable="false"
      show-icon
      title="巡检摘要加载失败"
    >
      <div class="error-body">
        <span>{{ summaryError }}</span>
        <el-button type="primary" link @click="loadSummary">重试</el-button>
      </div>
    </el-alert>

    <!-- ==================== 摘要 ==================== -->
    <el-card class="block-gap" shadow="never">
      <template #header>
        <div class="card-header">
          <span>巡检摘要</span>
          <span class="card-header__time">
            检查时间：{{ summary ? formatDateTime(summary.checkedAt) : '—' }}
          </span>
        </div>
      </template>
      <div v-loading="summaryLoading">
        <div v-if="summary" class="stat-grid">
          <div class="stat">
            <div class="stat__label">检查主体数</div>
            <div class="stat__value mono">{{ toNumber(summary.checkedOwners) }}</div>
          </div>
          <div class="stat">
            <div class="stat__label">平衡主体数</div>
            <div class="stat__value mono">{{ toNumber(summary.balancedOwners) }}</div>
          </div>
          <div class="stat">
            <div class="stat__label">不平衡主体数</div>
            <div class="stat__value mono" :class="{ 'stat__value--danger': toNumber(summary.diffOwners) > 0 }">
              {{ toNumber(summary.diffOwners) }}
            </div>
          </div>
          <div class="stat">
            <div class="stat__label">被检查余额合计（元）</div>
            <div class="stat__value mono">{{ formatCents(summary.totalCheckedCents) }}</div>
          </div>
          <div class="stat">
            <div class="stat__label">差异总额（元，绝对值合计）</div>
            <div class="stat__value mono" :class="{ 'stat__value--danger': diffTotalCents > 0 }">
              {{ formatCents(diffTotalCents) }}
            </div>
          </div>
          <div class="stat">
            <div class="stat__label">差异条目</div>
            <div class="stat__value mono">{{ diffs.length }} 条</div>
          </div>
        </div>
        <el-empty v-else-if="!summaryLoading && !summaryError" description="尚未取得巡检摘要" :image-size="72" />
      </div>
      <div class="card-foot">
        说明：「差异总额」由差异列表的 |diffCents| 求和得出（契约的 summary 未提供该字段）；
        「被检查余额合计」是服务端 totalCheckedCents，两者含义不同，不要混读。
      </div>
    </el-card>

    <!-- ==================== 差异列表 ==================== -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>差异列表（{{ diffs.length }} 条）</span>
          <el-button :loading="diffsLoading" link @click="loadDiffs">刷新差异</el-button>
        </div>
      </template>

      <el-alert
        v-if="diffsError"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        title="差异列表加载失败"
      >
        <div class="error-body">
          <span>{{ diffsError }}</span>
          <el-button type="primary" link @click="loadDiffs">重试</el-button>
        </div>
      </el-alert>

      <!-- 摘要与列表条数不一致：两者都是现算，可能是两次调用之间数据变了 -->
      <el-alert
        v-else-if="summary && toNumber(summary.diffOwners) !== diffs.length"
        class="block-gap"
        type="warning"
        :closable="false"
        show-icon
        :title="`摘要显示 ${toNumber(summary.diffOwners)} 个不平衡主体，但差异列表有 ${diffs.length} 条；两者是分别现算的，建议点「重新巡检」再核对一次`"
      />

      <el-alert
        v-if="!diffsLoading && !diffsError && diffs.length === 0"
        class="block-gap"
        type="success"
        :closable="false"
        show-icon
        title="差异 = 0：恒等式全部平衡"
      >
        <div class="alert-body">
          本次巡检没有发现资金差异。该结论只代表当前这一刻的账面状态；账户余额随时可能变动，需要时再巡检一次。
        </div>
      </el-alert>

      <el-table v-loading="diffsLoading" :data="diffs" row-key="ownerKey" stripe style="width: 100%">
        <template #empty>
          <el-empty
            :description="diffsError ? '差异列表加载失败，请点上方「重试」' : '差异 = 0：没有不平衡的资金主体'"
            :image-size="72"
          />
        </template>
        <el-table-column label="主体类型" min-width="140">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ ownerTypeText(row.ownerType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主体 ID" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.ownerId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="缓存余额（元）" min-width="150" align="right">
          <template #default="{ row }">
            <span class="mono">{{ formatCents(row.cacheCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="流水汇总（元）" min-width="150" align="right">
          <template #default="{ row }">
            <span class="mono">{{ formatCents(row.flowCents) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差异（元）" min-width="140" align="right">
          <template #default="{ row }">
            <span class="mono" :class="toNumber(row.diffCents) === 0 ? '' : 'amount--danger'">
              {{ formatSignedCents(row.diffCents) }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div class="card-foot">
        差异 = 缓存余额 − 流水汇总。修复账面差异属于运营决策，本页只做只读展示，不提供自动调整入口。
      </div>
    </el-card>
  </div>
</template>

<script setup name="BizReconciliation">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getReconciliationDiffs,
  getReconciliationSummary,
  runReconciliation
} from '@/api/biz/reconciliation'
import {
  describeRequestError,
  formatCents,
  formatDateTime,
  formatSignedCents,
  toNumber
} from '@/views/biz/shared/format'

/** 主体类型文案（库内 owner_type 可能是 user / lawyer 等；未知值原样回显）。 */
const OWNER_TYPE_TEXT = {
  user: '用户',
  lawyer: '律师',
  platform: '平台'
}

/** 巡检摘要。 */
const summary = ref(null)

/** 摘要加载中。 */
const summaryLoading = ref(false)

/** 摘要错误。 */
const summaryError = ref('')

/** 差异列表。 */
const diffs = ref([])

/** 差异加载中。 */
const diffsLoading = ref(false)

/** 差异错误。 */
const diffsError = ref('')

/** 手动巡检执行中。 */
const running = ref(false)

/**
 * 差异总额（分）：对差异列表的 |diffCents| 求和。
 *
 * 为什么不是用 summary：契约的 summary 只有 totalCheckedCents（被检查余额合计），
 * **没有**差异总额字段。这里在整数分上累加绝对值，避免正负抵消后误显示「0」。
 *
 * @returns {number} 差异总额（分）
 */
const diffTotalCents = computed(() =>
  diffs.value.reduce((total, row) => total + Math.abs(toNumber(row.diffCents)), 0)
)

/**
 * 主体类型文案。
 *
 * @param {string} ownerType 库内主体类型
 * @returns {string} 中文文案（未知值原样回显）
 */
function ownerTypeText(ownerType) {
  if (!ownerType) {
    return '—'
  }
  return OWNER_TYPE_TEXT[ownerType] || ownerType
}

/**
 * 拉取巡检摘要。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadSummary() {
  summaryLoading.value = true
  summaryError.value = ''
  try {
    const res = await getReconciliationSummary()
    summary.value = res.data || null
  } catch (e) {
    summary.value = null
    summaryError.value = describeRequestError(e, '巡检摘要加载失败')
  } finally {
    summaryLoading.value = false
  }
}

/**
 * 拉取差异列表。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadDiffs() {
  diffsLoading.value = true
  diffsError.value = ''
  try {
    const res = await getReconciliationDiffs()
    const list = Array.isArray(res.data) ? res.data : []
    // 表格 row-key 用「类型 + ID」组合：两类主体可能撞 ID
    diffs.value = list.map((row) => ({
      ...row,
      ownerKey: `${row.ownerType || 'unknown'}:${row.ownerId}`
    }))
  } catch (e) {
    diffs.value = []
    diffsError.value = describeRequestError(e, '差异列表加载失败')
  } finally {
    diffsLoading.value = false
  }
}

/**
 * 同时重取摘要与差异（两者都是现算，必须一起取）。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadAll() {
  await Promise.all([loadSummary(), loadDiffs()])
}

/**
 * 手动巡检：调 /run 拿本次摘要，再重取差异列表。
 *
 * 副作用：服务端写 operation_log（action=RECONCILE_RUN）；纯计算不加锁。
 *
 * @returns {Promise<void>} 无返回值
 */
async function handleRun() {
  running.value = true
  try {
    const res = await runReconciliation()
    summary.value = res.data || null
    summaryError.value = ''
    await loadDiffs()
    const data = res.data || {}
    ElMessage.success(
      `巡检完成：检查 ${toNumber(data.checkedOwners)} 个主体，不平衡 ${toNumber(data.diffOwners)} 个`
    )
  } catch (e) {
    summaryError.value = describeRequestError(e, '巡检执行失败')
  } finally {
    running.value = false
  }
}

onMounted(loadAll)
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

.alert-body {
  font-size: 12px;
  line-height: 1.7;
}

.error-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-header__time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}

.stat {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background-color: var(--el-fill-color-lighter);
}

.stat__label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.stat__value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

/* 不平衡/差异非 0 时用危险色：这是风险信号，不能和正常数一样灰 */
.stat__value--danger,
.amount--danger {
  color: var(--el-color-danger);
}

.mono {
  font-variant-numeric: tabular-nums;
}

.card-foot {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
</style>
