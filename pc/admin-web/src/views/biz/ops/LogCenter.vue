<!--
  职责：PC 后台 A-26 日志中心——操作日志 / 登录日志双 Tab（各自独立筛选与分页）、
        详情抽屉（把 detail 的 before/after/reason 格式化成对比表格）、双导出 CSV。
  调用方：RuoYi 动态路由（sys_menu 2236，component = biz/ops/LogCenter，route name = OpsLogCenter）。
  数据来源：src/api/biz/ops.js 的日志接口（第 7 段 A4）；
            src/api/biz/sysConfig.js 读留存天数（sys_config，与 A-08 同一个接口）。
  注意：
    1. **本页列表返回 `R<PageResult>`**（`res.data.list / res.data.total / hasMore`），
       不是 RuoYi 的 TableDataInfo（`rows/total`）——见 ops.js 文件头注释与工作流 §十一 第 15 条。
    2. **「目标对象」原样显示服务端 `targetDisplay`**：后端做白名单 label 映射
       （「订单#123」这类），映射不到时给 `targetType#id`。**前端绝不拿 targetType + targetId 拼**。
    3. **详情抽屉不是裸 JSON**：`detail` 是字符串就先 `JSON.parse`，解析失败优雅降级成文本 + 提示；
       解析成功时把 `before` / `after` 做字段级对比表（变更字段高亮），`reason` 单独展示。
    4. **留存文案必须改稿**：稿面「操作日志保留 90 天」低于合规下限 3 年，不能照抄。
       本页显示「操作日志按合规要求长期保留（≥ 3 年），登录日志保留 180 天」，
       天数从 sys_config 读：实际键是 `log.operation_retention_days` / `log.login_retention_days`
       （工作流 A4 写作 `audit.operation-log-days`，实际库内键名不同，两套都兼容读）。
    5. **操作人列只有 ID**：`operation_log.operator_id` 指向 `faze_admin.sys_user.id`，
       faze 库无权跨库读名字（§十一 第 12 条），所以显示「管理员 #id」；筛选同样只支持 ID，
       不伪造账号名。
    6. 日志只读：本页没有任何编辑/删除操作，只有「详情」。
-->
<template>
  <div class="app-container">
    <!-- ==================== 页头 ==================== -->
    <div class="logs-head">
      <div>
        <h2 class="logs-title">日志中心</h2>
        <p class="logs-desc">{{ retentionText }}</p>
      </div>
      <el-button type="primary" :loading="exporting" @click="handleExport">
        <el-icon><Download /></el-icon>
        <span>{{ activeTab === 'operation' ? '导出操作日志' : '导出登录日志' }}</span>
      </el-button>
    </div>

    <!-- ==================== 日志卡片 ==================== -->
    <el-card shadow="never" class="logs-card">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- ---------- 操作日志 ---------- -->
        <el-tab-pane label="操作日志" name="operation">
          <el-form :inline="true" :model="opQuery" class="query-bar" @submit.prevent>
            <el-form-item label="操作人">
              <el-input
                v-model="opQuery.operatorId"
                placeholder="管理员 ID"
                clearable
                style="width: 140px"
                @keyup.enter="reloadOperation"
              />
            </el-form-item>
            <el-form-item label="模块">
              <el-select v-model="opQuery.module" clearable placeholder="全部" style="width: 130px">
                <el-option v-for="item in MODULE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="动作">
              <el-select
                v-model="opQuery.action"
                clearable
                filterable
                placeholder="全部"
                style="width: 210px"
              >
                <el-option v-for="item in ACTION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间">
              <el-select v-model="opQuery.timeRange" style="width: 120px" @change="reloadOperation">
                <el-option v-for="item in TIME_RANGE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadOperation">查询</el-button>
              <el-button @click="resetOperation">重置</el-button>
            </el-form-item>
          </el-form>
          <p class="query-hint">
            操作人一栏请填管理员 ID（暂不支持按姓名 / 账号搜索）。
          </p>

          <el-alert
            v-if="opError"
            :title="'操作日志加载失败：' + opError"
            type="error"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table v-loading="opLoading" :data="opRows" border stripe>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="mono-time">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作人" width="110">
              <template #default="{ row }">{{ operatorText(row) }}</template>
            </el-table-column>
            <el-table-column label="模块" width="110">
              <template #default="{ row }">{{ moduleLabel(row.module) }}</template>
            </el-table-column>
            <el-table-column label="动作" width="150">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ actionLabel(row.action) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="目标对象" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <!-- 必须原样显示服务端 targetDisplay，前端不拼 id -->
                <span class="target">{{ targetDisplay(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源 IP" width="130">
              <template #default="{ row }">
                <span class="mono-ip">{{ textOrDash(row.ip) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="resultTag(row.result)" size="small" effect="light">
                  {{ resultText(row.result) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty
            v-if="!opLoading && !opError && opRows.length === 0"
            description="暂无操作日志"
            :image-size="80"
          />
          <el-pagination
            v-model:current-page="opQuery.pageNum"
            v-model:page-size="opQuery.pageSize"
            :total="opTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadOperationLogs"
            @size-change="reloadOperation"
          />
        </el-tab-pane>

        <!-- ---------- 登录日志 ---------- -->
        <el-tab-pane label="登录日志" name="login">
          <el-form :inline="true" :model="loginQuery" class="query-bar" @submit.prevent>
            <el-form-item label="账号">
              <el-input
                v-model="loginQuery.account"
                placeholder="脱敏账号片段"
                clearable
                style="width: 160px"
                @keyup.enter="reloadLogin"
              />
            </el-form-item>
            <el-form-item label="IP">
              <el-input
                v-model="loginQuery.ip"
                placeholder="来源 IP"
                clearable
                style="width: 140px"
                @keyup.enter="reloadLogin"
              />
            </el-form-item>
            <el-form-item label="结果">
              <el-select v-model="loginQuery.result" clearable placeholder="全部" style="width: 110px">
                <el-option label="成功" value="success" />
                <el-option label="失败" value="fail" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间">
              <el-select v-model="loginQuery.timeRange" style="width: 120px" @change="reloadLogin">
                <el-option v-for="item in TIME_RANGE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadLogin">查询</el-button>
              <el-button @click="resetLogin">重置</el-button>
            </el-form-item>
          </el-form>

          <el-alert
            v-if="loginError"
            :title="'登录日志加载失败：' + loginError"
            type="error"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table v-loading="loginLoading" :data="loginRows" border stripe>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">
                <span class="mono-time">{{ formatDateTime(row.createdAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="account" label="账号" width="150" />
            <el-table-column label="登录方式" width="120">
              <template #default="{ row }">{{ loginTypeLabel(row.loginType) }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="resultTag(row.result)" size="small" effect="light">
                  {{ resultText(row.result) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" width="130">
              <template #default="{ row }">{{ textOrDash(row.failReason) }}</template>
            </el-table-column>
            <el-table-column label="来源 IP" width="130">
              <template #default="{ row }">
                <span class="mono-ip">{{ textOrDash(row.ip) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="设备 / User-Agent" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ deviceText(row) }}</template>
            </el-table-column>
          </el-table>
          <el-empty
            v-if="!loginLoading && !loginError && loginRows.length === 0"
            description="暂无登录日志"
            :image-size="80"
          />
          <el-pagination
            v-model:current-page="loginQuery.pageNum"
            v-model:page-size="loginQuery.pageSize"
            :total="loginTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadLoginLogs"
            @size-change="reloadLogin"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- ==================== 稿面提示：业务侧日志 vs RuoYi 自带日志 ==================== -->
    <el-alert
      class="logs-note"
      type="info"
      :closable="false"
      show-icon
      title="本页是业务侧日志视图"
    >
      <template #default>
        业务操作日志与登录日志，来自业务库；「系统管理」菜单下另有一套框架级日志，两者字段口径不同，请勿混用。
      </template>
    </el-alert>

    <!-- ==================== 详情抽屉 ==================== -->
    <el-drawer v-model="drawerVisible" title="日志详情" size="720px">
      <template v-if="activeLog">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="时间">{{ formatDateTime(activeLog.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ operatorText(activeLog) }}</el-descriptions-item>
          <el-descriptions-item label="模块">{{ moduleLabel(activeLog.module) }}</el-descriptions-item>
          <el-descriptions-item label="动作">
            {{ actionLabel(activeLog.action) }}
            <span class="desc-code">（{{ activeLog.action || '—' }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="目标对象">{{ targetDisplay(activeLog) }}</el-descriptions-item>
          <el-descriptions-item label="来源 IP">{{ textOrDash(activeLog.ip) }}</el-descriptions-item>
          <el-descriptions-item label="结果">{{ resultText(activeLog.result) }}</el-descriptions-item>
          <el-descriptions-item label="记录 ID">{{ activeLog.id }}</el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">变更明细</h3>

        <!-- detail 是非法 JSON：优雅降级成文本 + 提示 -->
        <template v-if="detailState.parsed === false">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="detail 不是合法 JSON，已按原文展示"
            style="margin-bottom: 12px"
          />
          <pre class="detail-raw">{{ detailState.raw }}</pre>
        </template>

        <!-- detail 为空 -->
        <el-empty
          v-else-if="detailState.parsed === true && detailState.value === null"
          description="该条日志未记录 detail"
          :image-size="80"
        />

        <template v-else-if="detailState.parsed === true">
          <!-- before / after 字段级对比表 -->
          <template v-if="comparisonRows.length > 0">
            <table class="diff-table">
              <thead>
                <tr>
                  <th style="width: 28%">字段</th>
                  <th style="width: 36%">变更前</th>
                  <th style="width: 36%">变更后</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in comparisonRows" :key="item.key" :class="{ 'diff-row--changed': item.changed }">
                  <td class="diff-key">{{ item.label }}<span class="diff-key__raw">{{ item.key }}</span></td>
                  <td class="diff-value">{{ item.beforeText }}</td>
                  <td class="diff-value">{{ item.afterText }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <!-- reason 单独展示 -->
          <el-descriptions v-if="detailReason !== null" :column="1" border size="small" class="reason-block">
            <el-descriptions-item label="原因（reason）">{{ detailReason }}</el-descriptions-item>
          </el-descriptions>

          <!-- before/after 之外的其它字段：也格式化成键值表，不裸 JSON -->
          <template v-if="otherDetailRows.length > 0">
            <h4 class="detail-subtitle">其它字段</h4>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item v-for="item in otherDetailRows" :key="item.key" :label="item.label">
                {{ item.text }}
              </el-descriptions-item>
            </el-descriptions>
          </template>

          <p v-if="comparisonRows.length === 0 && otherDetailRows.length === 0 && detailReason === null" class="detail-empty-hint">
            该条日志的 detail 里没有可展示的字段。
          </p>
        </template>
      </template>
      <el-empty v-else description="请选择一条日志" />
    </el-drawer>
  </div>
</template>

<script setup name="OpsLogCenter">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { exportLoginLogs, exportOperationLogs, listLoginLogs, listOperationLogs } from '@/api/biz/ops'
import { listSysConfigs } from '@/api/biz/sysConfig'
import { describeRequestError, formatDateTime, textOrDash } from '@/views/biz/shared/format'
import { buildExportFileName, classifyExportPayload, downloadBlob } from '@/views/biz/shared/exportDownload'

/** 合规下限：操作日志 ≥ 3 年（1095 天）、登录日志 ≥ 180 天（工作流 A4 第 6 条）。 */
const OPERATION_RETENTION_MIN_DAYS = 1095
const LOGIN_RETENTION_MIN_DAYS = 180

/** 模块选项（取值 = 库内 module 列的真实值，label 为中文）。 */
const MODULE_OPTIONS = [
  { value: 'account', label: '账户与认证' },
  { value: 'trade', label: '交易与资金' },
  { value: 'content', label: '内容管理' },
  { value: 'ops', label: '运营治理' },
  { value: 'ai', label: 'AI 与法条' },
  { value: 'caseflow', label: '案件流转' },
  { value: 'document', label: '文书模板' },
  { value: 'conversation', label: '会话' }
]

/** 动作码 → 中文（表头与筛选共用；未登记的动作原样显示 code）。 */
const ACTION_LABELS = {
  // account
  CANCEL_ACCOUNT: '注销账号',
  CHANGE_PASSWORD: '修改密码',
  CHANGE_PAY_PASSWORD: '修改支付密码',
  CHANGE_PHONE: '更换手机号',
  ENTERPRISE_APPROVED: '企业认证通过',
  LAWYER_APPLICATION_APPROVED: '入驻申请通过',
  LAWYER_APPLICATION_REJECTED: '入驻申请驳回',
  REALNAME_APPROVED: '实名认证通过',
  REALNAME_REJECTED: '实名认证驳回',
  RESET_PAY_PASSWORD: '重置支付密码',
  SET_PAY_PASSWORD: '设置支付密码',
  SUBMIT_ENTERPRISE: '提交企业认证',
  SUBMIT_REALNAME: '提交实名认证',
  USER_BANNED: '封禁用户',
  USER_UNBANNED: '解封用户',
  WITHDRAW_ACCOUNT_CHANGE: '修改提现账户',
  // ai
  AI_CONVERSATION_EXPORT: '导出 AI 会话',
  LAW_ITEM_DISABLE: '停用法条',
  LAW_ITEM_EDIT: '编辑法条',
  LAW_ITEM_IMPORT: '导入法条',
  // caseflow
  CASE_AUDIT: '案件审核',
  DELIVERY_SEND: '文书送达',
  // content
  ARTICLE_CREATE: '新建文章',
  ARTICLE_OFFLINE: '下架文章',
  ARTICLE_PUBLISH: '发布文章',
  ARTICLE_UPDATE: '修改文章',
  CASE_LIBRARY_CREATE: '新建案例',
  CASE_LIBRARY_PUBLISH: '发布案例',
  // conversation
  CHAT_VIEW: '查看聊天记录',
  // document
  DOC_TEMPLATE_UPDATE: '修改文书模板',
  // ops
  ANNOUNCEMENT_CREATE: '新建公告',
  ANNOUNCEMENT_PUSH: '发送公告',
  LOG_EXPORT: '导出日志',
  SENSITIVE_WORD_CREATE: '新增敏感词',
  SENSITIVE_WORD_DELETE: '删除敏感词',
  SENSITIVE_WORD_IMPORT: '导入敏感词',
  SENSITIVE_WORD_UPDATE: '修改敏感词',
  SYS_CONFIG_UPDATED: '修改系统参数',
  CONTENT_REVIEW_IGNORE: '内容误判忽略',
  CONTENT_REVIEW_WARN: '内容警告用户',
  CONTENT_REVIEW_BAN: '内容封禁用户',
  APPEAL_HANDLE: '申诉处置',
  TICKET_ASSIGN: '分配工单',
  TICKET_CLOSE: '关闭工单',
  TICKET_REPLY: '回复工单',
  // trade
  MANUAL_ADJUST: '手工调账',
  MANUAL_RECHARGE: '手工充值',
  ORDER_REFUND_APPROVED: '退款通过',
  ORDER_REFUND_REJECTED: '退款驳回',
  RECHARGE_SYNC: '充值同步',
  RECONCILE_HANDLE: '对账处理',
  RECONCILE_RUN: '执行对账',
  SETTLEMENT_BACKFILL: '结算补录',
  SETTLEMENT_CONFIRM: '结算确认',
  SETTLEMENT_GENERATE: '生成结算',
  WITHDRAW_APPROVE: '提现审核通过',
  WITHDRAW_PAID: '提现打款',
  WITHDRAW_REJECT: '提现驳回',
  WITHDRAW_VOUCHER_UPLOAD: '上传提现凭证'
}

/** 动作筛选选项（value 是库内动作码，label 带中文，支持输入过滤）。 */
const ACTION_OPTIONS = Object.keys(ACTION_LABELS)
  .sort()
  .map((value) => ({ value, label: `${ACTION_LABELS[value]}（${value}）` }))

/** 时间范围选项（对应后端 beginTime/endTime；全部=不传时间）。 */
const TIME_RANGE_OPTIONS = [
  { value: 'today', label: '今天' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: '90d', label: '近 90 天' },
  { value: 'all', label: '全部' }
]

/** 登录方式文案。 */
const LOGIN_TYPE_LABELS = {
  password: '密码登录',
  sms: '短信登录',
  register: '注册',
  reset: '重置密码',
  refresh: '刷新令牌'
}

/** 抽屉里 detail 常见字段的中文名（未登记的键原样显示）。 */
const DETAIL_FIELD_LABELS = {
  status: '状态',
  reason: '原因',
  remark: '备注',
  banEndAt: '封禁截止',
  amountCents: '金额（分）',
  balanceCents: '余额（分）',
  bizNo: '业务单号',
  announcementNo: '公告编号',
  ticketNo: '工单编号',
  orderNo: '订单编号',
  title: '标题',
  content: '内容',
  enabled: '启用',
  level: '级别',
  category: '分类',
  priority: '优先级',
  agentId: '处理人 ID',
  before: '变更前',
  after: '变更后'
}

const activeTab = ref('operation')
const exporting = ref(false)

/** 留存天数：null 表示没读到配置，页面用合规口径兜底文案。 */
const retentionDays = reactive({
  operation: null,
  login: null
})

/** 操作日志：筛选 / 数据 / 分页。 */
const opQuery = reactive({ operatorId: '', module: '', action: '', timeRange: '7d', pageNum: 1, pageSize: 10 })
const opRows = ref([])
const opTotal = ref(0)
const opLoading = ref(false)
const opError = ref('')
const opLoaded = ref(false)

/** 登录日志：筛选 / 数据 / 分页（与操作日志完全独立）。 */
const loginQuery = reactive({ account: '', ip: '', result: '', timeRange: '7d', pageNum: 1, pageSize: 10 })
const loginRows = ref([])
const loginTotal = ref(0)
const loginLoading = ref(false)
const loginError = ref('')
const loginLoaded = ref(false)

/** 详情抽屉。 */
const drawerVisible = ref(false)
const activeLog = ref(null)

/** 留存提示文案：天数从 sys_config 读，未读到时按合规下限兜底。 */
const retentionText = computed(() => {
  const opDays = retentionDays.operation
  const loginDays = retentionDays.login
  let opText
  if (opDays === null) {
    opText = '操作日志按合规要求长期保留（≥ 3 年）'
  } else if (opDays >= OPERATION_RETENTION_MIN_DAYS) {
    opText = `操作日志按合规要求长期保留（≥ 3 年，当前配置 ${opDays} 天）`
  } else {
    opText = `操作日志当前配置 ${opDays} 天（低于合规下限 ${OPERATION_RETENTION_MIN_DAYS} 天，服务应拒绝启动，请立即核对 sys_config）`
  }
  const loginText = loginDays === null
    ? `登录日志保留 ${LOGIN_RETENTION_MIN_DAYS} 天`
    : `登录日志保留 ${loginDays} 天`
  return `记录管理员与系统对数据的每一次改动。${opText}，${loginText}，到期由服务端归档清理。`
})

/**
 * detail 解析状态。
 *
 * - `parsed=false`：字符串不是合法 JSON，降级为原文展示；
 * - `parsed=true, value=null`：没有 detail；
 * - `parsed=true, value=object`：正常结构化展示。
 *
 * @returns {{parsed:boolean, value:*, raw:*}} 解析状态
 */
const detailState = computed(() => {
  const row = activeLog.value
  if (!row) {
    return { parsed: true, value: null, raw: null }
  }
  const raw = row.detail
  if (raw === null || raw === undefined || raw === '') {
    return { parsed: true, value: null, raw: null }
  }
  if (typeof raw === 'object') {
    return { parsed: true, value: raw, raw }
  }
  try {
    return { parsed: true, value: JSON.parse(raw), raw }
  } catch (error) {
    return { parsed: false, value: null, raw }
  }
})

/**
 * 把任意 detail 值格式化成单元格文本。
 *
 * @param {*} value 值
 * @returns {string} 展示文本
 */
function formatDetailValue(value) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch (error) {
      return String(value)
    }
  }
  return String(value)
}

/**
 * 字段中文名。
 *
 * @param {string} key 字段键
 * @returns {string} 中文名或原键
 */
function detailFieldLabel(key) {
  return DETAIL_FIELD_LABELS[key] || key
}

/**
 * before / after 的字段级对比行。
 *
 * @returns {object[]} 行数组（key / label / beforeText / afterText / changed）
 */
const comparisonRows = computed(() => {
  const state = detailState.value
  if (!state.parsed || !state.value || typeof state.value !== 'object') {
    return []
  }
  const source = state.value
  const hasBefore = Object.prototype.hasOwnProperty.call(source, 'before')
  const hasAfter = Object.prototype.hasOwnProperty.call(source, 'after')
  if (!hasBefore && !hasAfter) {
    return []
  }
  const before = source.before && typeof source.before === 'object' ? source.before : {}
  const after = source.after && typeof source.after === 'object' ? source.after : {}
  const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)]))
  return keys.map((key) => {
    const beforeValue = before[key]
    const afterValue = after[key]
    return {
      key,
      label: detailFieldLabel(key),
      beforeText: formatDetailValue(beforeValue),
      afterText: formatDetailValue(afterValue),
      changed: JSON.stringify(beforeValue) !== JSON.stringify(afterValue)
    }
  })
})

/** detail 里的 reason（没有则 null）。 */
const detailReason = computed(() => {
  const state = detailState.value
  if (!state.parsed || !state.value || typeof state.value !== 'object') {
    return null
  }
  return Object.prototype.hasOwnProperty.call(state.value, 'reason')
    ? formatDetailValue(state.value.reason)
    : null
})

/** before / after / reason 之外的其它字段（也格式化成键值表）。 */
const otherDetailRows = computed(() => {
  const state = detailState.value
  if (!state.parsed || !state.value || typeof state.value !== 'object') {
    return []
  }
  return Object.keys(state.value)
    .filter((key) => key !== 'before' && key !== 'after' && key !== 'reason')
    .map((key) => ({ key, label: detailFieldLabel(key), text: formatDetailValue(state.value[key]) }))
})

/** 当前活动的 Tab 数据是否已加载（切换 Tab 时按需拉取）。 */
function handleTabChange() {
  if (activeTab.value === 'operation' && !opLoaded.value) {
    loadOperationLogs()
  }
  if (activeTab.value === 'login' && !loginLoaded.value) {
    loadLoginLogs()
  }
}

/** 模块码 → 中文名。 */
function moduleLabel(module) {
  const hit = MODULE_OPTIONS.find((item) => item.value === module)
  return hit ? hit.label : textOrDash(module)
}

/** 动作码 → 中文名。 */
function actionLabel(action) {
  return ACTION_LABELS[action] || textOrDash(action)
}

/** 操作人文案：本段只有 ID（§十一 第 12 条），不伪造名字。 */
function operatorText(row) {
  if (!row) {
    return '—'
  }
  if (row.operatorName) {
    return row.operatorName
  }
  if (row.operatorId !== null && row.operatorId !== undefined && row.operatorId !== '') {
    return `管理员 #${row.operatorId}`
  }
  if (row.operatorType === 'system') {
    return '系统'
  }
  return textOrDash(row.operatorType)
}

/** 目标对象：原样显示服务端 targetDisplay。 */
function targetDisplay(row) {
  return row && row.targetDisplay ? row.targetDisplay : '—'
}

/** 结果文案。 */
function resultText(result) {
  if (result === 'success') return '成功'
  if (result === 'fail') return '失败'
  return textOrDash(result)
}

/** 结果标签色。 */
function resultTag(result) {
  if (result === 'success') return 'success'
  if (result === 'fail') return 'danger'
  return 'info'
}

/** 登录方式文案。 */
function loginTypeLabel(loginType) {
  return LOGIN_TYPE_LABELS[loginType] || textOrDash(loginType)
}

/** 设备与 UA：设备号优先，UA 截断展示。 */
function deviceText(row) {
  const device = row.deviceId ? `设备 ${row.deviceId}` : ''
  const ua = row.userAgent ? String(row.userAgent) : ''
  if (device && ua) {
    return `${device} · ${ua}`
  }
  return device || ua || '—'
}

/**
 * 时间范围 → UTC 的 `yyyy-MM-ddTHH:mm:ss`（后端 LocalDateTime 无时区，按 UTC 比较）。
 *
 * @param {string} rangeValue today|7d|30d|90d|all
 * @returns {object} `{beginTime, endTime}`；all 返回空对象
 */
function buildTimeParams(rangeValue) {
  if (!rangeValue || rangeValue === 'all') {
    return {}
  }
  const now = new Date()
  let begin
  if (rangeValue === 'today') {
    begin = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0)
  } else {
    const days = { '7d': 7, '30d': 30, '90d': 90 }[rangeValue]
    begin = new Date(now.getTime() - days * 24 * 60 * 60 * 1000)
  }
  return { beginTime: toUtcText(begin), endTime: toUtcText(now) }
}

/**
 * Date → UTC 文本（不带时区后缀，后端用 LocalDateTime 接）。
 *
 * @param {Date} date JS 时间
 * @returns {string} `yyyy-MM-ddTHH:mm:ss`
 */
function toUtcText(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
    + `T${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`
}

/** 组装操作日志查询参数（列表与导出共用，导出不带分页）。 */
function buildOperationParams(withPaging) {
  const params = { ...buildTimeParams(opQuery.timeRange) }
  if (opQuery.operatorId !== '' && opQuery.operatorId !== null) {
    params.operatorId = opQuery.operatorId
  }
  if (opQuery.module) {
    params.module = opQuery.module
  }
  if (opQuery.action) {
    params.action = opQuery.action
  }
  if (withPaging) {
    params.pageNum = opQuery.pageNum
    params.pageSize = opQuery.pageSize
  }
  return params
}

/** 组装登录日志查询参数（列表与导出共用，导出不带分页）。 */
function buildLoginParams(withPaging) {
  const params = { ...buildTimeParams(loginQuery.timeRange) }
  if (loginQuery.account) {
    params.account = loginQuery.account
  }
  if (loginQuery.ip) {
    params.ip = loginQuery.ip
  }
  if (loginQuery.result) {
    params.result = loginQuery.result
  }
  if (withPaging) {
    params.pageNum = loginQuery.pageNum
    params.pageSize = loginQuery.pageSize
  }
  return params
}

/**
 * 加载操作日志（R<PageResult>，分页字段在 res.data.list）。
 *
 * 副作用：一次 HTTP 请求；写 opRows / opTotal / opError。
 */
async function loadOperationLogs() {
  opLoading.value = true
  opError.value = ''
  try {
    const res = await listOperationLogs(buildOperationParams(true))
    opRows.value = (res.data && res.data.list) || []
    opTotal.value = (res.data && res.data.total) || 0
    opLoaded.value = true
  } catch (error) {
    opError.value = describeRequestError(error, '操作日志加载失败')
  } finally {
    opLoading.value = false
  }
}

/**
 * 加载登录日志（R<PageResult>，分页字段在 res.data.list）。
 *
 * 副作用：一次 HTTP 请求；写 loginRows / loginTotal / loginError。
 */
async function loadLoginLogs() {
  loginLoading.value = true
  loginError.value = ''
  try {
    const res = await listLoginLogs(buildLoginParams(true))
    loginRows.value = (res.data && res.data.list) || []
    loginTotal.value = (res.data && res.data.total) || 0
    loginLoaded.value = true
  } catch (error) {
    loginError.value = describeRequestError(error, '登录日志加载失败')
  } finally {
    loginLoading.value = false
  }
}

/** 操作日志重置到第一页重查。 */
function reloadOperation() {
  opQuery.pageNum = 1
  loadOperationLogs()
}

/** 操作日志重置筛选条件。 */
function resetOperation() {
  opQuery.operatorId = ''
  opQuery.module = ''
  opQuery.action = ''
  opQuery.timeRange = '7d'
  opQuery.pageNum = 1
  loadOperationLogs()
}

/** 登录日志重置到第一页重查。 */
function reloadLogin() {
  loginQuery.pageNum = 1
  loadLoginLogs()
}

/** 登录日志重置筛选条件。 */
function resetLogin() {
  loginQuery.account = ''
  loginQuery.ip = ''
  loginQuery.result = ''
  loginQuery.timeRange = '7d'
  loginQuery.pageNum = 1
  loadLoginLogs()
}

/**
 * 打开详情抽屉。
 *
 * @param {object} row 操作日志行
 */
function openDetail(row) {
  activeLog.value = row
  drawerVisible.value = true
}

/**
 * 读留存天数（sys_config）：实际键名 + 工作流写的键名都兼容。
 *
 * 副作用：一次 HTTP 请求；写 retentionDays。
 */
async function loadRetentionDays() {
  try {
    const res = await listSysConfigs([
      'log.operation_retention_days',
      'log.login_retention_days',
      'audit.operation-log-days',
      'audit.login-log-days'
    ])
    const configs = res.data || []
    const map = {}
    for (const item of configs) {
      map[item.configKey] = item.configValue
    }
    const operation = map['log.operation_retention_days'] !== undefined
      ? map['log.operation_retention_days']
      : map['audit.operation-log-days']
    const login = map['log.login_retention_days'] !== undefined
      ? map['log.login_retention_days']
      : map['audit.login-log-days']
    retentionDays.operation = toDays(operation)
    retentionDays.login = toDays(login)
  } catch (error) {
    // 读不到配置不影响查日志，按合规下限兜底文案
    retentionDays.operation = null
    retentionDays.login = null
  }
}

/**
 * 配置值 → 天数（无法解析返回 null）。
 *
 * @param {string|number|null|undefined} value 配置值
 * @returns {number|null} 天数
 */
function toDays(value) {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const days = Number(value)
  return Number.isFinite(days) && days > 0 ? Math.floor(days) : null
}

/**
 * 导出当前 Tab 的日志 CSV。
 *
 * 副作用：一次 blob 请求 + 浏览器下载；行数超同步上限时服务端转异步（410015 + taskId），
 * 本页提示任务号而不把 JSON 存成 .csv；失败时按 404/网络错误给出可读提示。
 */
async function handleExport() {
  exporting.value = true
  try {
    const isOperation = activeTab.value === 'operation'
    const payload = isOperation
      ? await exportOperationLogs(buildOperationParams(false))
      : await exportLoginLogs(buildLoginParams(false))
    const result = await classifyExportPayload(payload)
    if (result.kind === 'file') {
      downloadBlob(payload, buildExportFileName(isOperation ? 'operation_logs' : 'login_logs'))
      ElMessage.success('日志已开始下载')
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
    ElMessage.error(result.msg || '导出日志失败')
  } catch (error) {
    ElMessage.error(describeRequestError(error, '导出日志失败'))
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadOperationLogs()
  loadRetentionDays()
})
</script>

<style scoped>
.logs-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 12px;
}
.logs-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.logs-desc {
  max-width: 900px;
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.logs-card :deep(.el-card__body) {
  padding-top: 8px;
}
.query-bar {
  margin-bottom: 4px;
}
.query-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.mono-time {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}
.mono-ip {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.target {
  color: var(--el-text-color-regular);
}
.logs-note {
  margin-top: 16px;
}
.desc-code {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.detail-title {
  margin: 20px 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.detail-subtitle {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}
.diff-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.diff-table th,
.diff-table td {
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  text-align: left;
  vertical-align: top;
}
.diff-table th {
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-weight: 500;
}
.diff-row--changed td {
  background: var(--el-color-warning-light-9);
}
.diff-key {
  color: var(--el-text-color-regular);
}
.diff-key__raw {
  display: block;
  margin-top: 2px;
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.diff-value {
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.reason-block {
  margin-top: 16px;
}
.detail-raw {
  max-height: 420px;
  padding: 12px;
  overflow: auto;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
  font-family: 'Inter', 'SF Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-all;
}
.detail-empty-hint {
  margin-top: 12px;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
</style>
