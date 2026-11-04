<!--
  职责：PC 后台「法泽业务」总览页（第 1 段 F1 的 biz 菜单占位页，验收项「能看到一个 biz 菜单页」）。
  调用方：RuoYi 动态路由（sys_menu component = biz/dashboard/index）。
  数据来源：faze-api `GET /api/v1/admin/dashboard/ping`（F1 自检用，返回服务端时间与管理员身份）。
  注意：
    1. 本页是 F1 的产物，作用是**证明三段链路都通了**：RuoYi 登录态 → faze-api 认这个 token
       → 管理端业务接口返回真实数据。它不做业务，业务在 A-03~A-09。
    2. 任何一处断链这里都会显示红字并给出定位提示，而不是白屏。
    3. 不得在这里写死中文以外的文案来源问题——PC 后台只有简体中文（AGENTS §1 的 4 语言
       约束针对移动端；PC 后台沿用 RuoYi 的中文界面）。i18n 规范 §二的 14 个 namespace 不含 admin。
-->
<template>
  <div class="app-container">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>法泽业务后台</span>
          <el-button type="primary" link :loading="loading" @click="loadPing">重新自检</el-button>
        </div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="faze-api">8081（/biz-api 前缀经 vite 代理）</el-descriptions-item>
        <el-descriptions-item label="服务端时间">{{ ping.serverTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="当前管理员">{{ ping.adminName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="管理员 ID">{{ ping.adminId || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">第 1 段交付的 7 个页面</el-divider>
      <el-descriptions :column="1" border>
        <el-descriptions-item
          v-for="item in pages"
          :key="item.code"
          :label="item.code"
        >{{ item.name }} —— {{ item.desc }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup name="BizDashboard">
import { onMounted, ref } from 'vue'
import { getDashboardPing } from '@/api/biz/dashboard'

const loading = ref(false)
const error = ref('')
const ping = ref({})

const pages = [
  { code: 'A-03', name: '用户列表', desc: '查询、详情抽屉、封禁/解封' },
  { code: 'A-04', name: '实名认证审核', desc: '待审队列、证件影像、通过/驳回' },
  { code: 'A-05', name: '律师入驻审核', desc: '申请队列、四步详情、通过/驳回' },
  { code: 'A-06', name: '律师列表', desc: '查询与详情' },
  { code: 'A-07', name: '余额与充值', desc: '手工充值、调账、按用户查流水' },
  { code: 'A-08', name: '系统参数', desc: 'sys_config 增删改查' },
  { code: 'A-09', name: '对账报表', desc: '恒等式巡检结果与差异列表' }
]

/**
 * 拉取自检数据。
 *
 * 副作用：一次 HTTP 请求；失败时把错误写进 error 供页面展示（不弹窗，避免刷新就弹）。
 */
async function loadPing() {
  loading.value = true
  error.value = ''
  try {
    const res = await getDashboardPing()
    ping.value = res.data || {}
  } catch (e) {
    error.value = '业务接口链路未通：' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
}

onMounted(loadPing)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
