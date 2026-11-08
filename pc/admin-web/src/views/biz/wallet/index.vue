<!--
  职责：PC A-07 余额与充值 —— 按用户查余额与流水、手工充值、手工调账。
  调用方：RuoYi 动态路由（sys_menu 2106，component = biz/wallet/index，route name = BizWallet）。
  数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
    GET  /api/v1/admin/wallets/{userId}                              钱包概览（R<AdminWalletVO>）
    POST /api/v1/admin/wallets/{userId}/recharge   头 Idempotency-Key  手工充值（R<{flowId,balanceCents,bizNo,createdAt,duplicated}>）
    POST /api/v1/admin/wallets/{userId}/adjust     头 Idempotency-Key  手工调账（同上）
    GET  /api/v1/admin/balance-flows?userId&bizType&beginTime&endTime&pageNum&pageSize  流水（顶层 rows/total）
  注意：
    1. **幂等键**：用 `src/api/biz/request.js` 的 `newIdempotencyKey()` 生成，走 HTTP 头。
       键在**用户第一次点提交时生成一次**并存在组件状态里，同一份表单的重试**复用同一个键**；
       表单任何字段一变就作废重生成（改内容后还复用旧键，服务端会把新内容当成重放）。
       每次重试都换新键等于没做幂等——这是锚点 1-A4 的直接验收对象。
    2. **ID 一律当字符串**：userId 从输入框拿到后原样拼进路径，绝不 Number()/parseInt()。
       输入先做 `^[0-9]+$` 校验，既防 URL 注入也避免把「1.0」「1e3」这类串送进后端。
    3. 金额一律整数分：输入的是**元**，用 `yuanToCents` 拆字符串做整数运算（不用浮点乘法）；
       展示用 `formatCents`（(cents/100).toFixed(2)）。不做任何浮点累加。
    4. `balanceCents` 是**流水汇总的总余额**（可用 + 冻结，接口注释口径），
       所以「可用余额 = balanceCents - frozenCents」，在整数分上做减法。
    5. `duplicated === true` 是**成功语义**（命中幂等、没重复入账），提示「该凭证已处理，未重复入账」，
       不能当错误弹。
    6. 契约未冻结 `bizType` 枚举（流水类型将来会增加），所以流水筛选用文本精确匹配，不写死白名单。
    7. 设计稿 A-07 顶部是「平台总量」四张卡，但冻结契约里**没有**平台级汇总接口
       （只有按用户的 /wallets/{userId}），因此本页顶部展示的是**当前查询用户**的余额构成，
       不伪造平台合计。这一处与设计稿的差异已在交付说明里标注。
    8. 四态：加载中 / 空（未查询 / 无流水）/ 错误（页面内常驻 el-alert + 重试）/ 正常。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="page-head">
      <div>
        <h1 class="page-head__title">余额与充值</h1>
        <p class="page-head__desc">
          平台采用账户余额制：用户充值进余额，消费时抵扣。手工充值用于线下付款与补偿，会记入流水并写审计日志。
        </p>
      </div>
      <div class="page-head__actions">
        <el-button :disabled="!wallet" @click="openRecharge">手工充值</el-button>
        <el-button type="primary" :disabled="!wallet" @click="openAdjust">调账</el-button>
      </div>
    </div>

    <!-- ==================== 查询栏 ==================== -->
    <el-card class="block-gap" shadow="never">
      <el-form inline label-width="72px" @submit.prevent>
        <el-form-item label="用户 ID">
          <el-input
            v-model="userIdInput"
            placeholder="必填，例：10001"
            clearable
            style="width: 200px"
            @keyup.enter="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="walletLoading" @click="handleQuery">查询</el-button>
          <el-button @click="handleResetQuery">清空</el-button>
        </el-form-item>
      </el-form>
      <div class="query-hint">
        用户 ID 是 BIGINT，接口以字符串传输；本页按字符串校验与透传，不做数值化。
      </div>
      <el-alert
        v-if="queryError"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        :title="queryError"
      />
    </el-card>

    <!-- ==================== 未查询：空态 ==================== -->
    <el-card v-if="!hasQueried" shadow="never">
      <el-empty description="输入用户 ID 后查询余额与流水" :image-size="96" />
    </el-card>

    <template v-else>
      <!-- ==================== 钱包概览 ==================== -->
      <el-card class="block-gap" shadow="never">
        <template #header>
          <div class="card-header">
            <span>账户余额 · 用户 ID <span class="mono">{{ queriedUserId }}</span></span>
            <el-button :loading="walletLoading" link @click="loadWallet">刷新余额</el-button>
          </div>
        </template>
        <el-alert
          v-if="walletError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          title="余额加载失败"
        >
          <div class="error-body">
            <span>{{ walletError }}</span>
            <el-button type="primary" link @click="loadWallet">重试</el-button>
          </div>
        </el-alert>
        <div v-loading="walletLoading">
          <div v-if="wallet" class="stat-grid">
            <div class="stat">
              <div class="stat__label">可用余额（元）</div>
              <div class="stat__value mono">{{ formatCents(availableCents) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">冻结金额（元）</div>
              <div class="stat__value mono" :class="{ 'stat__value--danger': toNumber(wallet.frozenCents) > 0 }">
                {{ formatCents(wallet.frozenCents) }}
              </div>
            </div>
            <div class="stat">
              <div class="stat__label">总余额（元，流水汇总）</div>
              <div class="stat__value mono">{{ formatCents(wallet.balanceCents) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">累计充值（元）</div>
              <div class="stat__value mono">{{ formatCents(wallet.totalRechargeCents) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">累计消费（元）</div>
              <div class="stat__value mono">{{ formatCents(wallet.totalConsumeCents) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">最近流水时间</div>
              <div class="stat__value stat__value--time">{{ formatDateTime(wallet.lastFlowAt) }}</div>
            </div>
          </div>
          <el-empty v-else-if="!walletLoading && !walletError" description="该用户暂无钱包记录" :image-size="72" />
        </div>
      </el-card>

      <!-- ==================== 流水 ==================== -->
      <el-card shadow="never">
        <template #header>
          <div class="card-header">
            <span>余额流水（共 {{ flowsTotal }} 条）</span>
            <el-button :loading="flowsLoading" link @click="loadFlows">刷新流水</el-button>
          </div>
        </template>

        <el-form inline label-width="72px" class="block-gap" @submit.prevent>
          <el-form-item label="业务类型">
            <el-input
              v-model="flowsQuery.bizType"
              placeholder="精确匹配，留空=全部"
              clearable
              style="width: 180px"
              @keyup.enter="handleFlowsSearch"
            />
          </el-form-item>
          <el-form-item label="时间">
            <el-date-picker
              v-model="flowsTimeRange"
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
            <el-button :loading="flowsLoading" @click="handleFlowsSearch">筛选</el-button>
            <el-button @click="handleFlowsReset">重置</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          v-if="flowsError"
          class="block-gap"
          type="error"
          :closable="false"
          show-icon
          title="流水加载失败"
        >
          <div class="error-body">
            <span>{{ flowsError }}</span>
            <el-button type="primary" link @click="loadFlows">重试</el-button>
          </div>
        </el-alert>

        <el-table v-loading="flowsLoading" :data="flows" row-key="id" stripe style="width: 100%">
          <template #empty>
            <el-empty
              :description="flowsError ? '流水加载失败，请点上方「重试」' : '该用户暂无流水记录'"
              :image-size="72"
            />
          </template>
          <el-table-column label="时间" min-width="150">
            <template #default="{ row }">
              <span class="mono muted">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="流水号" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务类型" min-width="120">
            <template #default="{ row }">
              <el-tag effect="plain" size="small">{{ textOrDash(row.bizType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="变动（元）" min-width="130" align="right">
            <template #default="{ row }">
              <span class="mono" :class="toNumber(row.deltaCents) >= 0 ? 'amount--in' : 'amount--out'">
                {{ formatSignedCents(row.deltaCents) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="变动后余额（元）" min-width="150" align="right">
            <template #default="{ row }">
              <span class="mono">{{ formatCents(row.balanceAfterCents) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ textOrDash(row.remark) }}</template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="flowsQuery.pageNum"
          v-model:page-size="flowsQuery.pageSize"
          class="pagination"
          background
          :page-sizes="[10, 20, 50, 100]"
          :total="flowsTotal"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleFlowsSizeChange"
          @current-change="loadFlows"
        />
      </el-card>
    </template>

    <!-- ==================== 手工充值弹窗 ==================== -->
    <el-dialog
      v-model="recharge.visible"
      title="手工充值"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        class="block-gap"
        type="warning"
        :closable="false"
        show-icon
        title="充值会直接入账并写操作日志。请确认已收到线下款项并核对凭证号。"
      />
      <el-alert
        v-if="recharge.error"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        :title="recharge.error"
      />
      <el-form label-width="90px">
        <el-form-item label="用户 ID">
          <span class="mono">{{ queriedUserId }}</span>
        </el-form-item>
        <el-form-item label="充值金额" required>
          <el-input v-model="recharge.form.amountYuan" placeholder="元，例：100.00" style="width: 200px">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="充值原因" required>
          <el-input
            v-model="recharge.form.reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="必填：谁批准、为什么充（写进审计日志）"
          />
        </el-form-item>
        <el-form-item label="凭证号" required>
          <el-input v-model="recharge.form.bizNo" maxlength="64" placeholder="线下转账流水号，≤64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="recharge.form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可空，≤500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recharge.visible = false">取消</el-button>
        <el-button type="primary" :loading="recharge.submitting" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 调账弹窗 ==================== -->
    <el-dialog
      v-model="adjust.visible"
      title="手工调账"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        class="block-gap"
        type="warning"
        :closable="false"
        show-icon
        title="调账直接改余额（可正可负）。负向调账不能把余额调成负数，且必须有原因。"
      />
      <el-alert
        v-if="adjust.error"
        class="block-gap"
        type="error"
        :closable="false"
        show-icon
        :title="adjust.error"
      />
      <el-form label-width="90px">
        <el-form-item label="用户 ID">
          <span class="mono">{{ queriedUserId }}</span>
        </el-form-item>
        <el-form-item label="调账方向" required>
          <el-radio-group v-model="adjust.form.direction">
            <!-- Element Plus 2.6+：值用 `value`，展示文案放插槽；`label` 当值已标记弃用（控制台会告警） -->
            <el-radio-button value="increase">增加余额</el-radio-button>
            <el-radio-button value="decrease">扣减余额</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调账金额" required>
          <el-input v-model="adjust.form.amountYuan" placeholder="元，例：12.50" style="width: 200px">
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="调账原因" required>
          <el-input
            v-model="adjust.form.reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="必填：写清调账依据（写进审计日志）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="adjust.form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可空，≤500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjust.visible = false">取消</el-button>
        <el-button type="primary" :loading="adjust.submitting" @click="submitAdjust">确认调账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="BizWallet">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adjustWallet, getWallet, listBalanceFlows, rechargeWallet } from '@/api/biz/wallet'
import { newIdempotencyKey } from '@/api/biz/request'
import {
  describeRequestError,
  formatCents,
  formatDateTime,
  formatSignedCents,
  textOrDash,
  toNumber,
  yuanToCents
} from '@/views/biz/shared/format'

/** el-date-picker 的当日时间边界。 */
const defaultRangeTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

/** 输入框里的用户 ID（字符串原样）。 */
const userIdInput = ref('')

/** 当前已查询的用户 ID（字符串；只有查询成功后才用于写操作） 。 */
const queriedUserId = ref('')

/** 是否已经查询过一次（控制初始空态）。 */
const hasQueried = ref(false)

/** 输入校验错误。 */
const queryError = ref('')

/** 钱包概览。 */
const wallet = ref(null)

/** 钱包加载中。 */
const walletLoading = ref(false)

/** 钱包错误。 */
const walletError = ref('')

/** 流水列表。 */
const flows = ref([])

/** 流水总条数。 */
const flowsTotal = ref(0)

/** 流水加载中。 */
const flowsLoading = ref(false)

/** 流水错误。 */
const flowsError = ref('')

/** 流水查询条件。 */
const flowsQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  bizType: ''
})

/** 流水时间区间（ISO 字符串）。 */
const flowsTimeRange = ref([])

/** 充值弹窗状态（含表单与幂等键）。 */
const recharge = reactive({
  visible: false,
  submitting: false,
  error: '',
  /** 幂等键：第一次点提交时生成，同一份表单的重试复用；表单变化即清空。 */
  idempotencyKey: '',
  form: {
    amountYuan: '',
    reason: '',
    bizNo: '',
    remark: ''
  }
})

/** 调账弹窗状态（含表单与幂等键）。 */
const adjust = reactive({
  visible: false,
  submitting: false,
  error: '',
  idempotencyKey: '',
  form: {
    direction: 'increase',
    amountYuan: '',
    reason: '',
    remark: ''
  }
})

/** 可用余额 = 流水汇总总余额 - 冻结（整数分上做减法，不做浮点） 。 */
const availableCents = computed(() => {
  if (!wallet.value) {
    return 0
  }
  return toNumber(wallet.value.balanceCents) - toNumber(wallet.value.frozenCents)
})

/**
 * 充值表单变化时作废幂等键。
 *
 * 为什么：键代表「这一份提交内容」。内容改了还复用旧键，服务端会把新内容当成重放，
 * 返回 duplicated=true，用户会以为钱没入账。
 */
watch(
  () => recharge.form,
  () => {
    recharge.idempotencyKey = ''
  },
  { deep: true }
)

/** 调账表单变化时作废幂等键。 */
watch(
  () => adjust.form,
  () => {
    adjust.idempotencyKey = ''
  },
  { deep: true }
)

/**
 * 点「查询」：校验用户 ID 后并行拉钱包与流水。
 *
 * @returns {Promise<void>} 无返回值
 */
async function handleQuery() {
  const id = userIdInput.value.trim()
  queryError.value = ''
  if (!id) {
    queryError.value = '请输入用户 ID'
    return
  }
  // 只接受纯数字：BIGINT 的字符串形式；同时也挡住把 "1e3" / "1.0" 送进 URL
  if (!/^[0-9]+$/.test(id)) {
    queryError.value = '用户 ID 只能是数字（字符串形式），请检查是否输入了空格、小数点或字母'
    return
  }
  queriedUserId.value = id
  hasQueried.value = true
  flowsQuery.pageNum = 1
  await Promise.all([loadWallet(), loadFlows()])
}

/**
 * 清空查询：回到初始空态。
 *
 * @returns {void}
 */
function handleResetQuery() {
  userIdInput.value = ''
  queriedUserId.value = ''
  hasQueried.value = false
  queryError.value = ''
  wallet.value = null
  walletError.value = ''
  flows.value = []
  flowsTotal.value = 0
  flowsError.value = ''
  flowsQuery.bizType = ''
  flowsQuery.pageNum = 1
  flowsTimeRange.value = []
}

/**
 * 拉取钱包概览。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadWallet() {
  if (!queriedUserId.value) {
    return
  }
  walletLoading.value = true
  walletError.value = ''
  try {
    const res = await getWallet(queriedUserId.value)
    wallet.value = res.data || null
  } catch (e) {
    wallet.value = null
    walletError.value = describeRequestError(e, '余额加载失败')
  } finally {
    walletLoading.value = false
  }
}

/**
 * 拉取余额流水。
 *
 * @returns {Promise<void>} 无返回值
 */
async function loadFlows() {
  if (!queriedUserId.value) {
    return
  }
  flowsLoading.value = true
  flowsError.value = ''
  const params = {
    userId: queriedUserId.value,
    pageNum: flowsQuery.pageNum,
    pageSize: flowsQuery.pageSize
  }
  if (flowsQuery.bizType.trim()) {
    params.bizType = flowsQuery.bizType.trim()
  }
  if (Array.isArray(flowsTimeRange.value) && flowsTimeRange.value.length === 2) {
    params.beginTime = flowsTimeRange.value[0]
    params.endTime = flowsTimeRange.value[1]
  }
  try {
    const res = await listBalanceFlows(params)
    flows.value = Array.isArray(res.rows) ? res.rows : []
    flowsTotal.value = toNumber(res.total)
  } catch (e) {
    flows.value = []
    flowsTotal.value = 0
    flowsError.value = describeRequestError(e, '流水加载失败')
  } finally {
    flowsLoading.value = false
  }
}

/**
 * 流水筛选（回到第 1 页）。
 *
 * @returns {void}
 */
function handleFlowsSearch() {
  flowsQuery.pageNum = 1
  loadFlows()
}

/**
 * 流水筛选重置。
 *
 * @returns {void}
 */
function handleFlowsReset() {
  flowsQuery.bizType = ''
  flowsTimeRange.value = []
  flowsQuery.pageNum = 1
  loadFlows()
}

/**
 * 流水每页条数变化。
 *
 * @returns {void}
 */
function handleFlowsSizeChange() {
  flowsQuery.pageNum = 1
  loadFlows()
}

/**
 * 打开充值弹窗并重置表单与幂等键。
 *
 * @returns {void}
 */
function openRecharge() {
  recharge.form.amountYuan = ''
  recharge.form.reason = ''
  recharge.form.bizNo = ''
  recharge.form.remark = ''
  recharge.error = ''
  recharge.submitting = false
  recharge.idempotencyKey = ''
  recharge.visible = true
}

/**
 * 打开调账弹窗并重置表单与幂等键。
 *
 * @returns {void}
 */
function openAdjust() {
  adjust.form.direction = 'increase'
  adjust.form.amountYuan = ''
  adjust.form.reason = ''
  adjust.form.remark = ''
  adjust.error = ''
  adjust.submitting = false
  adjust.idempotencyKey = ''
  adjust.visible = true
}

/**
 * 校验充值表单。
 *
 * @returns {{amountCents:number}|null} 合法时返回金额（分），非法时把错误写进弹窗并返回 null
 */
function validateRecharge() {
  const amountCents = yuanToCents(recharge.form.amountYuan)
  if (amountCents === null) {
    recharge.error = '金额格式不对：请输入最多两位小数的非负金额，如 100 或 100.50'
    return null
  }
  if (amountCents <= 0) {
    recharge.error = '充值金额必须大于 0'
    return null
  }
  const reason = recharge.form.reason.trim()
  if (!reason) {
    recharge.error = '请填写充值原因（后端同样会拦）'
    return null
  }
  if (reason.length > 200) {
    recharge.error = '充值原因不能超过 200 字'
    return null
  }
  const bizNo = recharge.form.bizNo.trim()
  if (!bizNo) {
    recharge.error = '请填写凭证号（线下转账流水号）'
    return null
  }
  if (bizNo.length > 64) {
    recharge.error = '凭证号不能超过 64 字符'
    return null
  }
  if (recharge.form.remark.length > 500) {
    recharge.error = '备注不能超过 500 字'
    return null
  }
  return { amountCents }
}

/**
 * 提交充值：校验 → 二次确认 → 生成/复用幂等键 → 调接口 → 刷新余额与流水。
 *
 * 副作用：写操作（入账 + 操作日志）；成功后刷新钱包与流水。
 *
 * @returns {Promise<void>} 无返回值
 */
async function submitRecharge() {
  recharge.error = ''
  const valid = validateRecharge()
  if (!valid) {
    return
  }
  const amountText = formatCents(valid.amountCents)
  try {
    await ElMessageBox.confirm(
      `确认为用户 ID ${queriedUserId.value} 充值 ¥${amountText}？\n凭证号：${recharge.form.bizNo.trim()}`,
      '充值二次确认',
      { type: 'warning', confirmButtonText: '确认入账', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  // 键在第一点提交时生成一次；表单没变就复用（重试不换键 = 真幂等）
  if (!recharge.idempotencyKey) {
    recharge.idempotencyKey = newIdempotencyKey('recharge')
  }
  const idempotencyKey = recharge.idempotencyKey
  recharge.submitting = true
  try {
    const res = await rechargeWallet(
      queriedUserId.value,
      {
        amountCents: valid.amountCents,
        reason: recharge.form.reason.trim(),
        bizNo: recharge.form.bizNo.trim(),
        remark: recharge.form.remark.trim() || undefined
      },
      idempotencyKey
    )
    const data = res.data || {}
    if (data.duplicated === true) {
      ElMessage.warning('该凭证已处理，未重复入账')
    } else {
      ElMessage.success(`充值成功，当前余额 ¥${formatCents(data.balanceCents)}`)
    }
    recharge.visible = false
    recharge.idempotencyKey = ''
    // 先用返回值立刻更新余额（不让用户等一次往返），再整体重取一次
    if (wallet.value && data.balanceCents !== undefined && data.balanceCents !== null) {
      wallet.value.balanceCents = data.balanceCents
    }
    await Promise.all([loadWallet(), loadFlows()])
  } catch (e) {
    // 拦截器已 toast 一次；这里留在弹窗里，用户可直接重试（复用同一个幂等键）
    recharge.error = describeRequestError(e, '充值失败')
  } finally {
    recharge.submitting = false
  }
}

/**
 * 校验调账表单。
 *
 * @returns {{deltaCents:number}|null} 合法时返回带符号的变动金额（分），非法时写错误并返回 null
 */
function validateAdjust() {
  const amountCents = yuanToCents(adjust.form.amountYuan)
  if (amountCents === null) {
    adjust.error = '金额格式不对：请输入最多两位小数的非负金额，如 12 或 12.50'
    return null
  }
  if (amountCents <= 0) {
    adjust.error = '调账金额必须大于 0'
    return null
  }
  const deltaCents = adjust.form.direction === 'decrease' ? -amountCents : amountCents
  if (deltaCents === 0) {
    adjust.error = '变动金额不能为 0'
    return null
  }
  const reason = adjust.form.reason.trim()
  if (!reason) {
    adjust.error = '请填写调账原因（后端同样会拦）'
    return null
  }
  if (reason.length > 200) {
    adjust.error = '调账原因不能超过 200 字'
    return null
  }
  if (adjust.form.remark.length > 500) {
    adjust.error = '备注不能超过 500 字'
    return null
  }
  return { deltaCents }
}

/**
 * 提交调账：校验 → 二次确认 → 生成/复用幂等键 → 调接口 → 刷新余额与流水。
 *
 * @returns {Promise<void>} 无返回值
 */
async function submitAdjust() {
  adjust.error = ''
  const valid = validateAdjust()
  if (!valid) {
    return
  }
  const directionText = valid.deltaCents > 0 ? '增加' : '扣减'
  try {
    await ElMessageBox.confirm(
      `确认对用户 ID ${queriedUserId.value} ${directionText}余额 ¥${formatCents(Math.abs(valid.deltaCents))}？`,
      '调账二次确认',
      { type: 'warning', confirmButtonText: '确认调账', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  // 调账没有天然业务号，幂等键是唯一去重手段：同一次提交的重试必须复用
  if (!adjust.idempotencyKey) {
    adjust.idempotencyKey = newIdempotencyKey('adjust')
  }
  const idempotencyKey = adjust.idempotencyKey
  adjust.submitting = true
  try {
    const res = await adjustWallet(
      queriedUserId.value,
      {
        deltaCents: valid.deltaCents,
        reason: adjust.form.reason.trim(),
        remark: adjust.form.remark.trim() || undefined
      },
      idempotencyKey
    )
    const data = res.data || {}
    if (data.duplicated === true) {
      ElMessage.warning('该凭证已处理，未重复入账')
    } else {
      ElMessage.success(`调账成功，当前余额 ¥${formatCents(data.balanceCents)}`)
    }
    adjust.visible = false
    adjust.idempotencyKey = ''
    if (wallet.value && data.balanceCents !== undefined && data.balanceCents !== null) {
      wallet.value.balanceCents = data.balanceCents
    }
    await Promise.all([loadWallet(), loadFlows()])
  } catch (e) {
    adjust.error = describeRequestError(e, '调账失败')
  } finally {
    adjust.submitting = false
  }
}
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

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

/* 余额构成：6 项自适应网格；数值比标签重一档，竖着扫能立刻比大小 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
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
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.stat__value--time {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-regular);
}

.stat__value--danger {
  color: var(--el-color-danger);
}

.mono {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 入账用成功色、出账用危险色：只借 Element Plus 的主题变量，不写死色值 */
.amount--in {
  color: var(--el-color-success);
}

.amount--out {
  color: var(--el-color-danger);
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
