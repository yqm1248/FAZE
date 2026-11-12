<!--
  A-01 登录页（法泽管理后台）

  职责：管理员账号密码登录 + 图形验证码（可配置开关）。左品牌板 + 右表单，
        右面板底部是版权与条款行（左对齐、贴页面最底）。

  调用方：RuoYi 静态路由 /login（src/router/index.js constantRoutes）。

  数据来源：
    1. 图形验证码 GET /captchaImage（RuoYi 自带，不是业务接口）；
       sys_config 的 sys.account.captchaEnabled 为 false 时整行不显示。
    2. 登录 POST /login（RuoYi），成功后写 Cookie Admin-Token，
       由 faze-api 共享同一 token（接口规范 §1.3 方案 A）。

  为什么重写这个文件（AGENTS §3.3 说不得修改 RuoYi 框架层，本文件是例外并已登记）：
    原生登录页是「信纸木窗背景图 + 居中白卡片 + 若依管理系统标题」，整页没有一处
    法泽品牌，底部还写着 Copyright © 2018-2026 RuoYi。负责人 2026-11-13 要求按
    design/admin/00-login.html 重做。已记录到 docs/analysis/ruoyi-patches.md。

  与设计稿 design/admin/00-login.html 的 5 处**有意差异**（不是没做到）：
    1. 设计稿画了验证码，实际 sys.account.captchaEnabled=false，所以验证码行
       按开关渲染，默认不显示；开启后与稿面一致。
    2. 设计稿把密码预填成 faze2026，实际账号是 admin/admin123 —— 见 SCRIPT 里的
       TODO 注释，上线前必须清空预填。
    3. 设计稿写「记住账号」，RuoYi 的 rememberMe 存的是**加密后的密码**，
       所以这里写「记住密码」才是准确口径。
    4. 设计稿左侧三点事实是 4 语言版本 / 1,284 注册用户 / 26 业务页面。
       实测：注册用户库 faze.app_user 只有 15 行且全是测试数据，1,284 是编的；
       业务页面设计稿 28 屏去掉登录页 = 27。所以改成「4 语言版本 / 27 功能页面」
       两项，**不展示用户数**（负责人 2026-11-13 明确要求）。
    5. 设计稿 footer 用 margin-top 挂在表单后面、跟着垂直居中一起悬在版心中下部
       （实测距页面底部 166px、text-align:center）。改为贴页面最底部、左对齐。
-->
<template>
  <div class="login">
    <!-- ==================== 左：品牌板 ==================== -->
    <aside class="login__brand">
      <!-- 天平水印：法律平台的最小视觉暗示，不抢标题 -->
      <svg
        class="login__watermark"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="0.6"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M12 4.2v15.6" /><path d="M7.4 20h9.2" /><path d="M4.6 8.4h14.8" />
        <path d="M4.6 8.4 2.4 13.6h4.4L4.6 8.4Z" /><path d="M19.4 8.4l2.2 5.2h-4.4l2.2-5.2Z" />
        <circle cx="12" cy="4.2" r="1.2" />
      </svg>

      <div class="login__logo">
        <img :src="brandLogo" class="login__mark" alt="法泽 FAZE" />
        <span class="login__brand-name">
          <b>法泽 FAZE</b>
          <span>MANAGEMENT CONSOLE</span>
        </span>
      </div>

      <div class="login__copy">
        <span class="login__eyebrow">伊犁师范大学法学专业实验基地</span>
        <h1 class="login__headline">伊犁普惠法律服务<em>管理后台</em></h1>
        <p class="login__lede">
          面向新疆伊犁地区的普惠型法律服务平台。这里集中管理用户与律师资质、
          订单与资金流水、内容与案件进度，以及平台侧的审核与治理动作。
        </p>
      </div>

      <!-- 三点事实：只放可核实的产品事实，不放经营指标 -->
      <div class="login__facts">
        <div class="login__fact"><b>4</b><span>语言版本</span></div>
        <div class="login__fact"><b>27</b><span>功能页面</span></div>
      </div>
    </aside>

    <!-- ==================== 右：登录表单 ==================== -->
    <main class="login__panel">
      <div class="login__form-wrap">
        <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login__form">
          <h2 class="login__title">登录</h2>
          <p class="login__subtitle">仅限平台管理员账号，所有操作全程留痕</p>

          <div class="login__fields">
            <!-- 账号 -->
            <el-form-item prop="username">
              <el-input
                v-model="loginForm.username"
                type="text"
                size="large"
                auto-complete="off"
                placeholder="请输入管理员账号"
              >
                <template #prefix>
                  <svg-icon icon-class="user" class="login__input-icon" />
                </template>
              </el-input>
            </el-form-item>

            <!-- 密码 -->
            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                size="large"
                auto-complete="off"
                placeholder="请输入密码"
                show-password
                @keyup.enter="handleLogin"
              >
                <template #prefix>
                  <svg-icon icon-class="password" class="login__input-icon" />
                </template>
              </el-input>
            </el-form-item>

            <!-- 验证码：sys.account.captchaEnabled=false 时整行不渲染 -->
            <el-form-item prop="code" v-if="captchaEnabled">
              <div class="login__captcha">
                <el-input
                  v-model="loginForm.code"
                  size="large"
                  auto-complete="off"
                  placeholder="请输入验证码"
                  @keyup.enter="handleLogin"
                >
                  <template #prefix>
                    <svg-icon icon-class="validCode" class="login__input-icon" />
                  </template>
                </el-input>
                <button class="login__captcha-img" type="button" title="看不清？点击换一张" @click="getCode">
                  <img :src="codeUrl" alt="验证码" />
                </button>
              </div>
            </el-form-item>

            <div class="login__row">
              <el-checkbox v-model="loginForm.rememberMe" class="login__remember">记住密码</el-checkbox>
              <a class="login__link" @click.prevent="handleForgot">忘记密码</a>
            </div>

            <el-button
              :loading="loading"
              size="large"
              type="primary"
              class="login__submit"
              style="width: 100%"
              @click.prevent="handleLogin"
            >
              <span v-if="!loading">登 录</span>
              <span v-else>登 录 中...</span>
            </el-button>
          </div>

          <div class="login__tip">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="8.6" />
              <path d="M12 11v5.4" /><path d="M12 7.8v.2" />
            </svg>
            <span>
              本系统面向平台管理员开放。涉及用户隐私与资金的操作会被完整记录（操作人、
              时间、来源 IP、变更前后值），请勿共享账号。
            </span>
          </div>
        </el-form>
      </div>

      <!-- 底部：贴页面最底、左对齐（与表单左边缘对齐） -->
      <footer class="login__foot">
        <div>© 2026 法泽 FAZE · 伊犁师范大学法学专业实验基地</div>
        <div>
          <a class="login__link" @click.prevent="handleDoc('服务条款')">服务条款</a>
          <span class="login__dot">·</span>
          <a class="login__link" @click.prevent="handleDoc('隐私政策')">隐私政策</a>
          <span class="login__dot">·</span>
          <span>后台版本 v1.0.0</span>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { getCodeImg } from '@/api/login'
import Cookies from 'js-cookie'
import { encrypt, decrypt } from '@/utils/jsencrypt'
import useUserStore from '@/store/modules/user'
import { ElMessage } from 'element-plus'
import brandLogo from '@/assets/logo/logo.png'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()

/**
 * 账号密码表单。
 *
 * TODO（上线前必做）：删掉这里的默认账号密码。
 *   原 RuoYi 版把 admin/admin123 写死在初始值里，是给开发者省事的；真实上线的
 *   管理后台不能预填口令。测试期先保留，方便测试人员直接点登录。
 */
const loginForm = ref({
  username: 'admin',
  password: 'admin123',
  rememberMe: false,
  code: '',
  uuid: ''
})

const loginRules = {
  username: [{ required: true, trigger: 'blur', message: '请输入管理员账号' }],
  password: [{ required: true, trigger: 'blur', message: '请输入密码' }],
  code: [{ required: true, trigger: 'change', message: '请输入验证码' }]
}

const codeUrl = ref('')
const loading = ref(false)
/** 验证码开关：由 /captchaImage 返回的 captchaEnabled 决定，默认按开启渲染。 */
const captchaEnabled = ref(true)
const redirect = ref(undefined)

watch(
  route,
  (newRoute) => {
    redirect.value = newRoute.query && newRoute.query.redirect
  },
  { immediate: true }
)

/**
 * 提交登录。
 *
 * 副作用：写 Cookie（记住密码时）、调用 userStore.login、成功后跳转 redirect 或首页。
 */
function handleLogin() {
  proxy.$refs.loginRef.validate((valid) => {
    if (!valid) return
    loading.value = true
    if (loginForm.value.rememberMe) {
      Cookies.set('username', loginForm.value.username, { expires: 30 })
      Cookies.set('password', encrypt(loginForm.value.password), { expires: 30 })
      Cookies.set('rememberMe', loginForm.value.rememberMe, { expires: 30 })
    } else {
      Cookies.remove('username')
      Cookies.remove('password')
      Cookies.remove('rememberMe')
    }
    userStore
      .login(loginForm.value)
      .then(() => {
        const query = route.query
        const otherQueryParams = Object.keys(query).reduce((acc, cur) => {
          if (cur !== 'redirect') acc[cur] = query[cur]
          return acc
        }, {})
        router.push({ path: redirect.value || '/', query: otherQueryParams })
      })
      .catch(() => {
        loading.value = false
        if (captchaEnabled.value) getCode()
      })
  })
}

/** 拉取图形验证码；captchaEnabled=false 时只把开关关掉，不发图片。 */
function getCode() {
  getCodeImg().then((res) => {
    captchaEnabled.value = res.captchaEnabled === undefined ? true : res.captchaEnabled
    if (captchaEnabled.value) {
      codeUrl.value = 'data:image/gif;base64,' + res.img
      loginForm.value.uuid = res.uuid
    }
  })
}

/** 从 Cookie 回填「记住密码」的账号与口令。 */
function getCookie() {
  const username = Cookies.get('username')
  const password = Cookies.get('password')
  const rememberMe = Cookies.get('rememberMe')
  loginForm.value = {
    username: username === undefined ? loginForm.value.username : username,
    password: password === undefined ? loginForm.value.password : decrypt(password),
    rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
  }
}

/**
 * 「忘记密码」：RuoYi 没有自助重置入口，只能由超管在「系统管理 → 用户管理」重置。
 *
 * @param {string} [label] 提示里显示的功能名，缺省为「忘记密码」
 */
function handleDoc(label = '忘记密码') {
  if (label === '忘记密码') {
    ElMessage.info('请联系平台超级管理员，在「系统管理 → 用户管理」中重置密码')
  } else {
    // 《服务条款》《隐私政策》正文尚未定稿（DEF-008），先明确告知而不是给死链
    ElMessage.info(`《${label}》正文尚未发布，当前版本请以平台线下协议为准`)
  }
}

getCode()
getCookie()
</script>

<style lang="scss" scoped>
/* 品牌色与深色板色号取自 design/admin/tokens.css，与后台侧边栏同一套 */
$brand: #4a66ee;
$brand-on-dark: #b9c2fa;
$bg-sidebar: #22283a;
$text-sidebar: #b8bfd0;
$text-primary: #1a1a1a;
$text-muted: #6b7280;
$border-input: #dcdfe6;

.login {
  display: grid;
  /* 左板按设计稿 760/1440 = 52.8% 取，宽度上限锁 760，窄屏最小 420 */
  grid-template-columns: clamp(420px, 52.8vw, 760px) minmax(0, 1fr);
  width: 100%;
  height: 100%;
  background: #ffffff;
}

/* ---------- 左：品牌板 ---------- */
.login__brand {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 48px 48px 40px;
  background: $bg-sidebar;
  overflow: hidden;
}

.login__watermark {
  position: absolute;
  right: -90px;
  bottom: -70px;
  width: 460px;
  height: 460px;
  color: #ffffff;
  opacity: 0.05;
  pointer-events: none;
}

.login__logo {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #ffffff;
}

.login__mark {
  width: 38px;
  height: 38px;
  /* 品牌图标是白底方图（源图无 alpha），深色板上必须圆角，否则是一个白方块 */
  border-radius: 10px;
}

.login__brand-name {
  display: flex;
  flex-direction: column;
  line-height: 1.2;

  b {
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.08em;
  }

  span {
    margin-top: 2px;
    font-size: 12px;
    color: $text-sidebar;
    letter-spacing: 0.14em;
  }
}

.login__copy {
  margin-top: auto;
  margin-bottom: auto;
  max-width: 420px;
}

.login__eyebrow {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 4px;
  color: $text-sidebar;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.login__headline {
  margin: 20px 0 0;
  font-size: 32px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: #ffffff;

  em {
    font-style: normal;
    color: $brand-on-dark;
  }
}

.login__lede {
  margin-top: 16px;
  font-size: 14px;
  line-height: 1.8;
  color: $text-sidebar;
}

.login__facts {
  display: flex;
  gap: 32px;
  padding-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
}

.login__fact {
  b {
    display: block;
    font-size: 20px;
    font-weight: 600;
    color: #ffffff;
  }

  span {
    font-size: 12px;
    color: $text-sidebar;
  }
}

/* ---------- 右：表单 ---------- */
.login__panel {
  display: flex;
  flex-direction: column;
  padding: 48px 48px 28px;
  background: #ffffff;
  min-height: 0;
}

.login__form-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 0;
}

.login__form {
  width: 360px;

  /* 覆盖 Element Plus 的 form-item 默认下边距，按设计稿 16px 间距 */
  :deep(.el-form-item) {
    margin-bottom: 16px;
  }

  :deep(.el-input__wrapper) {
    height: 40px;
  }

  :deep(.el-input__inner) {
    height: 40px;
    font-size: 14px;
  }
}

.login__title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.25;
  letter-spacing: -0.01em;
  color: $text-primary;
}

.login__subtitle {
  margin: 8px 0 0;
  font-size: 13px;
  color: $text-muted;
}

.login__fields {
  margin-top: 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;

  /* 字段间距由外层 gap 控制，抵消掉 el-form-item 自带的下边距 */
  :deep(.el-form-item) {
    margin-bottom: 0;
  }
}

.login__input-icon {
  width: 14px;
  height: 14px;
  color: #a8abb2;
}

.login__captcha {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px;
  gap: 12px;
  width: 100%;
}

.login__captcha-img {
  height: 40px;
  padding: 0;
  border: 1px solid $border-input;
  border-radius: 4px;
  background: #fafbfc;
  cursor: pointer;
  overflow: hidden;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &:hover {
    border-color: $brand;
  }
}

.login__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 13px;
}

.login__remember {
  :deep(.el-checkbox__label) {
    font-size: 13px;
    color: #4b5563;
  }
}

.login__link {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  color: $brand;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
    text-underline-offset: 2px;
  }
}

.login__submit {
  height: 40px;
  margin-top: 20px;
  font-size: 14px;
  letter-spacing: 0.04em;
}

.login__tip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 24px;
  padding: 12px;
  border-radius: 6px;
  background: #fafbfc;
  font-size: 12px;
  line-height: 1.65;
  color: $text-muted;

  svg {
    flex: 0 0 14px;
    width: 14px;
    height: 14px;
    margin-top: 2px;
    color: #a8abb2;
  }
}

/* 底部：贴页面最底、左对齐（决策见文件头注释第 5 条）。
   宽度 360 与表单同宽并居中，于是文字左边缘正好与表单左边缘对齐。 */
.login__foot {
  align-self: center;
  width: 360px;
  font-size: 12px;
  line-height: 1.9;
  color: $text-muted;
  text-align: left;
}

.login__dot {
  margin: 0 6px;
  color: #c0c4cc;
}

/* 窄屏（笔记本分屏 / 小窗）：左板收掉，只留表单，避免两栏都挤 */
@media (max-width: 900px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__brand {
    display: none;
  }
}
</style>
