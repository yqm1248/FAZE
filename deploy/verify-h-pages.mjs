/*
 * 第 3 段 H 组 · PC 后台两页（A-27 法条库 / A-28 AI 对话检索）的浏览器实测与截图。
 *
 * 做什么（真实浏览器 + 真实 RuoYi 登录态）：
 *   1. RuoYi /login 拿 admin token，注入 Cookie `Admin-Token`（不点登录页，避开验证码/时序）；
 *   2. 打开 /index，等动态路由与侧边栏渲染，核对「内容管理 → 法条库」「系统设置 → AI 对话检索」已挂载；
 *   3. 进 A-27：核对三处删除项不在页面、保留列齐全、批量导入弹窗可开；截图；
 *   4. 进 A-28：核对删除项不在页面、只读区无编辑控件、refs 说明与导出按钮在；截图；
 *   5. 1440×900 下量侧边栏 scrollHeight/clientHeight（H3 的高度验收）；
 *   6. 收集 console 报错与 /api 非 2xx 响应，写 JSON 证据。
 *
 * 两种模式：
 *   node deploy/verify-h-pages.mjs          真实接口模式。faze-api 的 H1/H2 接口没重启时页面是 404 错误态，
 *                                           本模式只验「页面能开、结构对、删除项不在」，接口 404 记 INFO 不判 FAIL。
 *   node deploy/verify-h-pages.mjs --mock   用 CDP Fetch 域拦截 /biz-api/api/v1/admin/**，回放固定 mock 数据，
 *                                           只用于**布局自查**（表格列宽、refs 快照、快照 vs 当前提示）。
 *                                           截图文件名带 `-mockdata`，**不是接口验收证据**，H1/H2 的数据级验收
 *                                           由 Lead 在 faze-api 重启后用真 token 补。
 *
 * 前置：PC 前端 :80（vite dev）+ RuoYi :8080 + faze-api :8081 已启动；
 *       Chrome 以 --remote-debugging-port=9222 启动；
 *       faze_admin.sys_menu 已执行 database/seed/seg3_admin_menu.sql。
 *
 * 注意：
 *   - 脚本**新建一个标签页**（PUT /json/new），用完关闭，不劫持用户/Lead 正在看的标签页。
 *   - 用 Emulation.setDeviceMetricsOverride 固定 1440×900 视口，截图即 PC 全屏尺寸。
 */
import fs from 'node:fs'
import path from 'node:path'

const CDP = 'http://127.0.0.1:9222'
const APP = 'http://localhost'
const OUT_DIR = 'E:/dshcode/FAZE/docs/acceptance/seg3/_artifacts'
const MOCK = process.argv.includes('--mock')
const SHOT_SUFFIX = MOCK ? '-mockdata' : ''

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const results = []
const consoleErrors = []
const failedRequests = []
let ws
let msgId = 0
const pending = new Map()
let targetId = ''

function check(name, ok, detail) {
  results.push({ name, ok: Boolean(ok), detail: detail || '' })
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${name}${detail ? ' :: ' + detail : ''}`)
}

function info(name, detail) {
  results.push({ name, ok: true, detail: detail || '', info: true })
  console.log(`[INFO] ${name}${detail ? ' :: ' + detail : ''}`)
}

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const mid = ++msgId
    pending.set(mid, { resolve, reject })
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
}

async function evalJs(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) {
    throw new Error('eval error: ' + JSON.stringify(r.exceptionDetails.exception || r.exceptionDetails))
  }
  return r.result.value
}

async function waitFor(expression, timeoutMs, label) {
  const t0 = Date.now()
  let last = ''
  while (Date.now() - t0 < timeoutMs) {
    try {
      const v = await evalJs(expression)
      if (v) return v
      last = String(v)
    } catch (e) {
      last = e.message
    }
    await sleep(300)
  }
  throw new Error(`等待超时(${timeoutMs}ms): ${label}；最后值=${last}`)
}

async function clickByText(selector, text) {
  return evalJs(`(() => {
    const list = Array.from(document.querySelectorAll(${JSON.stringify(selector)}));
    const el = list.find((e) => e.textContent && e.textContent.includes(${JSON.stringify(text)}) && e.offsetParent !== null);
    if (!el) return false;
    el.click();
    return true;
  })()`)
}

async function screenshot(name) {
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  const file = path.join(OUT_DIR, name)
  fs.writeFileSync(file, Buffer.from(shot.data, 'base64'))
  const size = fs.statSync(file).size
  console.log(`[SHOT] ${name}  ${size} bytes`)
  return file
}

/** 取页面正文（innerText），用于「删除项不在页面」的逐条断言。 */
async function bodyText() {
  return evalJs(`document.body.innerText || ''`)
}

async function assertAbsent(labels) {
  const text = await bodyText()
  const hit = labels.filter((label) => text.includes(label))
  return { ok: hit.length === 0, hit }
}

async function assertPresent(labels) {
  const text = await bodyText()
  const missing = labels.filter((label) => !text.includes(label))
  return { ok: missing.length === 0, missing }
}

/** 通过 vue-router 跳转（动态路由来自 /getRouters，直接 navigate 会因未注册而回首页）。 */
/**
 * 关闭当前可见的 el-dialog。
 *
 * 先点 footer 里的按钮（验收 UI 上的「关闭/取消」真的能关），1.5s 后还在就点右上角 X 兜底，
 * 最后断言可见弹窗数归零。el-dialog 关闭后 DOM 不销毁、只是 overlay display:none，
 * 所以判据必须看 offsetParent，不能看元素是否存在。
 *
 * @param {string} footerText footer 按钮文案
 * @param {string} label 断言名（写失败详情用）
 * @returns {Promise<string>} 实际生效的方式（footer / header / none）
 */
async function closeVisibleDialog(footerText, label) {
  await clickByText('.el-dialog__footer button, .el-dialog button', footerText)
  await sleep(1500)
  let remaining = await evalJs(
    `Array.from(document.querySelectorAll('.el-dialog')).filter((d) => d.offsetParent !== null).length`
  )
  let method = 'footer'
  if (remaining > 0) {
    method = 'header'
    await evalJs(`(() => {
      const d = Array.from(document.querySelectorAll('.el-dialog')).find((el) => el.offsetParent !== null);
      if (!d) return false;
      const btn = d.querySelector('.el-dialog__headerbtn');
      if (btn) { btn.click(); return true; }
      return false;
    })()`)
    await sleep(1200)
    remaining = await evalJs(
      `Array.from(document.querySelectorAll('.el-dialog')).filter((d) => d.offsetParent !== null).length`
    )
  }
  check(`${label}可关闭`, remaining === 0, `关闭方式=${method}，残留可见弹窗=${remaining}`)
  return remaining === 0 ? method : 'failed'
}

async function pushRoute(route) {
  await evalJs(`(() => {
    const vue = document.querySelector('#app').__vue_app__;
    const router = vue.config.globalProperties.$router;
    router.push(${JSON.stringify(route)});
    return true;
  })()`)
}

/**
 * 展开/收起侧边栏某个二级组，并等过渡结束（菜单高度是动画变化的，
 * 动画没结束就截图/量高度会得到「子项被裁一半」的假象）。
 *
 * @param {string} label 一级菜单标题（如「内容管理」）
 * @param {boolean} open true=展开，false=收起
 */
async function toggleSubmenu(label, open) {
  await evalJs(`(() => {
    const titles = Array.from(document.querySelectorAll('.el-sub-menu__title'));
    const title = titles.find((e) => e.textContent && e.textContent.includes(${JSON.stringify(label)}));
    if (!title) return false;
    const sub = title.closest('.el-sub-menu');
    const opened = sub && sub.classList.contains('is-opened');
    if (opened !== ${open ? 'true' : 'false'}) {
      title.click();
    }
    return true;
  })()`)
  await waitFor(
    `(() => {
      const titles = Array.from(document.querySelectorAll('.el-sub-menu__title'));
      const title = titles.find((e) => e.textContent && e.textContent.includes(${JSON.stringify(label)}));
      if (!title) return false;
      const sub = title.closest('.el-sub-menu');
      return sub ? sub.classList.contains('is-opened') === ${open ? 'true' : 'false'} : false;
    })()`,
    8000,
    `子菜单「${label}」${open ? '展开' : '收起'}`
  )
  // 展开/收起是高度过渡：等内层 el-menu 的高度真的到位，不然量高度/截图会拿到半截
  await waitFor(
    `(() => {
      const titles = Array.from(document.querySelectorAll('.el-sub-menu__title'));
      const title = titles.find((e) => e.textContent && e.textContent.includes(${JSON.stringify(label)}));
      if (!title) return false;
      const inner = title.closest('.el-sub-menu').querySelector('.el-menu');
      if (!inner) return ${open ? 'false' : 'true'};
      const h = inner.getBoundingClientRect().height;
      return ${open ? 'h > 40' : 'h < 1'};
    })()`,
    8000,
    `子菜单「${label}」高度过渡结束`
  )
  await sleep(400)
}

// ─────────────────────────── mock 数据（仅布局自查） ───────────────────────────
const MOCK_LAW_ROWS = [
  {
    id: '1', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第五百七十七条', articleSeq: 577,
    contentSummary: '当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。',
    effectiveDate: '2021-01-01', expireDate: null, status: 'active', isEnabled: 1, keywords: '违约金 违约责任',
    createdAt: '2026-10-20T08:00:00', updatedAt: '2026-10-28T08:20:00', aiRefCount: 126
  },
  {
    id: '2', lawCode: 'labor_contract', lawName: '中华人民共和国劳动合同法', articleNo: '第四十七条', articleSeq: 47,
    contentSummary: '经济补偿按劳动者在本单位工作的年限，每满一年支付一个月工资的标准向劳动者支付。',
    effectiveDate: '2013-07-01', expireDate: null, status: 'active', isEnabled: 1, keywords: '经济补偿',
    createdAt: '2026-10-21T08:00:00', updatedAt: '2026-10-25T01:18:00', aiRefCount: 63
  },
  {
    id: '3', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第一千一百六十五条', articleSeq: 1165,
    contentSummary: '行为人因过错侵害他人民事权益造成损害的，应当承担侵权责任。',
    effectiveDate: '2021-01-01', expireDate: null, status: 'active', isEnabled: 1, keywords: '侵权责任',
    createdAt: '2026-10-19T08:00:00', updatedAt: '2026-10-24T09:36:00', aiRefCount: 58
  },
  {
    id: '4', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第一千零六十二条', articleSeq: 1062,
    contentSummary: '夫妻在婚姻关系存续期间所得的工资、奖金、劳务报酬等财产，为夫妻的共同财产，归夫妻共同所有。',
    effectiveDate: '2021-01-01', expireDate: null, status: 'active', isEnabled: 1, keywords: '夫妻共同财产',
    createdAt: '2026-10-18T08:00:00', updatedAt: '2026-10-22T06:07:00', aiRefCount: 41
  }
]

const MOCK_LAW_DISABLED = [
  {
    id: '99', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第一千一百九十八条', articleSeq: 1198,
    contentSummary: '宾馆、商场、银行、车站等经营场所、公共场所的经营者、管理者未尽到安全保障义务造成他人损害的，应当承担侵权责任。',
    effectiveDate: '2016-01-01', expireDate: null, status: 'active', isEnabled: 0, keywords: '安全保障义务',
    createdAt: '2026-10-18T08:00:00', updatedAt: '2026-10-28T12:22:00', aiRefCount: 12
  }
]

const MOCK_LAW_DETAIL = {
  id: '1', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第五百七十七条', articleSeq: 577,
  content:
    '第五百七十七条 当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。\n' +
    '当事人一方明确表示或者以自己的行为表明不履行合同义务的，对方可以在履行期限届满前请求其承担违约责任。',
  contentSummary: '当事人一方不履行合同义务的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。',
  effectiveDate: '2021-01-01', expireDate: null, status: 'active', isEnabled: 1, keywords: '违约金 违约责任',
  createdBy: null, createdByType: 'system',
  createdAt: '2026-10-20T08:00:00', updatedAt: '2026-10-28T08:20:00', aiRefCount: 126
}

const MOCK_CONVERSATIONS = [
  {
    id: '7', userId: '10284', userName: '阿依努尔·买买提', title: '被公司辞退，没有提前通知，能要经济补偿吗？',
    questionSummary: '被公司辞退，没有提前通知，能要经济补偿吗？', lang: 'zh-Hans', createdAt: '2026-10-29T13:40:00',
    updatedAt: '2026-10-29T13:40:03', deletedAt: null, refCount: 3
  },
  {
    id: '6', userId: '10281', userName: '李伟', title: '离婚的时候孩子抚养权一般怎么判？',
    questionSummary: '离婚的时候孩子抚养权一般怎么判？', lang: 'zh-Hans', createdAt: '2026-10-29T12:15:00',
    updatedAt: '2026-10-29T12:15:02', deletedAt: null, refCount: 1
  },
  {
    id: '5', userId: '10283', userName: '张建国', title: '借条上没写利息，还能不能主张利息？',
    questionSummary: '借条上没写利息，还能不能主张利息？', lang: 'zh-Hans', createdAt: '2026-10-29T10:52:00',
    updatedAt: '2026-10-29T10:52:02', deletedAt: null, refCount: 1
  },
  {
    id: '4', userId: '10280', userName: '古丽娜尔·哈力克', title: '工伤认定一般要多久出结果？',
    questionSummary: '工伤认定一般要多久出结果？', lang: 'zh-Hans', createdAt: '2026-10-29T08:30:00',
    updatedAt: '2026-10-29T08:30:02', deletedAt: '2026-10-30T02:00:00', refCount: 0
  }
]

const MOCK_MESSAGES = {
  conversation: MOCK_CONVERSATIONS[0],
  messages: [
    {
      id: '101', conversationId: '7', userId: '10284', role: 'user',
      content: '被公司辞退，没有提前通知，能要经济补偿吗？', model: null,
      promptTokens: null, completeTokens: null, contentId: null, isExported: 0, exportUrls: [],
      refs: [], lang: 'zh-Hans', createdAt: '2026-10-29T13:40:00', updatedAt: '2026-10-29T13:40:00'
    },
    {
      id: '102', conversationId: '7', userId: '10284', role: 'assistant',
      content:
        '先区分两种情形：用人单位单方解除与协商一致解除，补偿标准不同。\n' +
        '如果公司以你「不能胜任工作」或「客观情况发生重大变化」为由解除，且未提前三十日书面通知，依据《劳动合同法》第四十条、第四十七条，需要支付经济补偿。\n' +
        '经济补偿按在本单位工作年限计算：每满一年支付一个月工资，六个月以上不满一年的按一年计，不满六个月的支付半个月工资。',
      model: 'doubao-seed-1.6-32k', promptTokens: 812, completeTokens: 356,
      contentId: 'AI-0123456789abcdef0123456789abcdef', isExported: 0, exportUrls: [],
      refs: [
        {
          lawItemId: '2', lawCode: 'labor_contract', lawName: '中华人民共和国劳动合同法', articleNo: '第四十七条',
          content: '经济补偿按劳动者在本单位工作的年限，每满一年支付一个月工资的标准向劳动者支付。',
          effectiveDate: '2013-07-01', status: 'active'
        },
        {
          lawItemId: '87', lawCode: 'labor_contract', lawName: '中华人民共和国劳动合同法', articleNo: '第八十七条',
          content: '用人单位违反本法规定解除或者终止劳动合同的，应当依照本法第四十七条规定的经济补偿标准的二倍向劳动者支付赔偿金。',
          effectiveDate: '2013-07-01', status: 'active'
        },
        {
          lawItemId: '88', lawCode: 'civil_code', lawName: '中华人民共和国民法典', articleNo: '第九百九十九条',
          content: '模型生成时引用了该条号，法条库中已查不到对应条文，此处保留引用当时快照供核对。',
          effectiveDate: '2021-01-01', status: 'active'
        }
      ],
      lang: 'zh-Hans', createdAt: '2026-10-29T13:40:00', updatedAt: '2026-10-29T13:40:03'
    }
  ],
  // 快照 vs 当前：47 未变；87 已 amended（应提示「已更新」）；88 不在数组（应提示「已查不到」）
  currentLawItems: [
    {
      lawItemId: '2', lawCode: 'labor_contract', lawName: '中华人民共和国劳动合同法', articleNo: '第四十七条',
      status: 'active', isEnabled: 1, effectiveDate: '2013-07-01', expireDate: null, updatedAt: '2026-10-25T01:18:00'
    },
    {
      lawItemId: '87', lawCode: 'labor_contract', lawName: '中华人民共和国劳动合同法', articleNo: '第八十七条',
      status: 'amended', isEnabled: 0, effectiveDate: '2013-07-01', expireDate: '2026-11-01', updatedAt: '2026-11-01T02:00:00'
    }
  ]
}

const b64 = (o) => Buffer.from(JSON.stringify(o), 'utf8').toString('base64')

/** Fetch 域拦截：把 /biz-api/api/v1/admin/** 的请求回放成 mock 响应（仅布局自查）。 */
async function handleMock(paused) {
  const url = paused.request.url
  let payload
  if (/\/admin\/law-items\/\d+$/.test(url)) {
    payload = { code: 200, msg: '操作成功', data: MOCK_LAW_DETAIL }
  } else if (url.includes('/admin/law-items')) {
    const disabled = /[?&]isEnabled=0(&|$)/.test(url)
    const rows = disabled ? MOCK_LAW_DISABLED : MOCK_LAW_ROWS
    payload = { code: 200, msg: '查询成功', total: rows.length, rows }
  } else if (/\/admin\/ai-conversations\/\d+\/messages/.test(url)) {
    payload = { code: 200, msg: '操作成功', data: MOCK_MESSAGES }
  } else if (url.includes('/admin/ai-conversations')) {
    payload = { code: 200, msg: '查询成功', total: MOCK_CONVERSATIONS.length, rows: MOCK_CONVERSATIONS }
  } else {
    payload = { code: 404, msg: 'mock: 未定义该接口' }
  }
  await send('Fetch.fulfillRequest', {
    requestId: paused.requestId,
    responseCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json;charset=utf-8' }],
    body: b64(payload)
  }).catch(() => {})
}

/**
 * 量侧边栏滚动状态。
 *
 * @returns {Promise<object>} client/scroll 高度与菜单项数量
 */
async function measureSidebar() {
  return evalJs(`(() => {
    const container = document.querySelector('.sidebar-container');
    const wrap = container ? container.querySelector('.el-scrollbar__wrap') : null;
    const view = wrap ? wrap.querySelector('.el-scrollbar__view') : null;
    return {
      containerClient: container ? container.clientHeight : null,
      wrapClient: wrap ? wrap.clientHeight : null,
      wrapScroll: wrap ? wrap.scrollHeight : null,
      viewHeight: view ? view.getBoundingClientRect().height : null,
      itemCount: document.querySelectorAll('.sidebar-container .el-menu-item').length,
      subMenuCount: document.querySelectorAll('.sidebar-container .el-sub-menu').length
    };
  })()`)
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true })

  // ── 1. RuoYi 登录，拿 token ───────────────────────────────────────────
  const loginRes = await fetch('http://127.0.0.1:8080/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  }).then((r) => r.json())
  if (loginRes.code !== 200) {
    throw new Error('RuoYi 登录失败：' + JSON.stringify(loginRes))
  }
  info('RuoYi 登录成功', `token ${loginRes.token.length} 字符`)

  // ── 2. 新建独立标签页（不劫持已有标签） ──────────────────────────────
  const created = await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' }).then((r) => r.json())
  targetId = created.id
  ws = new WebSocket(created.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    ws.onopen = res
    ws.onerror = rej
  })
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id)
      pending.delete(m.id)
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
      return
    }
    if (MOCK && m.method === 'Fetch.requestPaused') {
      handleMock(m.params)
      return
    }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails
      consoleErrors.push(`exception: ${d.text} ${(d.exception && d.exception.description) || ''}`.slice(0, 300))
    }
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      consoleErrors.push(`log: ${m.params.entry.text}`.slice(0, 300))
    }
    if (m.method === 'Network.responseReceived') {
      const r = m.params.response
      if (/\/api\/v1\//.test(r.url) && r.status >= 400) {
        failedRequests.push(`${r.status} ${r.url}`)
      }
    }
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Log.enable')
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false
  })
  await send('Page.bringToFront')
  await send('Network.setCookie', { name: 'Admin-Token', value: loginRes.token, domain: 'localhost', path: '/' })
  if (MOCK) {
    await send('Fetch.enable', { patterns: [{ urlPattern: '*/biz-api/api/v1/admin/*', requestStage: 'Request' }] })
    info('mock 模式：/biz-api/api/v1/admin/** 已由 CDP Fetch 拦截，截图仅供布局自查', '')
  }

  // ── 3. 打开 /index，等动态路由就绪 ──────────────────────────────────
  await send('Page.navigate', { url: `${APP}/index` })
  await sleep(2500)
  await evalJs(`(() => {
    if (document.getElementById('cdp-no-transition')) return 'already';
    const s = document.createElement('style');
    s.id = 'cdp-no-transition';
    s.textContent = '.fade-transform-leave-active,.fade-transform-enter-active{transition:none !important;animation:none !important;}';
    document.head.appendChild(s);
    return 'injected';
  })()`)
  await waitFor(`document.querySelectorAll('.el-menu-item, .el-sub-menu').length > 5`, 45000, '侧边栏渲染')
  info('动态路由与侧边栏已就绪', '')

  const menuText = await evalJs(`document.querySelector('.sidebar-container')?.innerText || ''`)
  check('侧边栏出现一级目录「内容管理」', menuText.includes('内容管理'))
  check('侧边栏出现一级目录「系统设置」', menuText.includes('系统设置'))

  // ── 4. H3：1440×900 侧边栏高度实测 ──────────────────────────────────
  // 先展开「内容管理」（设计稿要求展开后无滚动条），量一次
  await toggleSubmenu('内容管理', true)
  const sidebarContentOpen = await measureSidebar()
  info('侧边栏高度（1440×900，内容管理展开）', JSON.stringify(sidebarContentOpen))
  check(
    '1440×900 展开内容管理后侧边栏无滚动条',
    sidebarContentOpen.wrapScroll !== null &&
      sidebarContentOpen.wrapClient !== null &&
      sidebarContentOpen.wrapScroll <= sidebarContentOpen.wrapClient + 1,
    `wrap client=${sidebarContentOpen.wrapClient} scroll=${sidebarContentOpen.wrapScroll}`
  )
  await screenshot(`h3-sidebar-1440x900${SHOT_SUFFIX}.png`)

  // 既有 IA 债务：法泽业务 下 12 个子项，展开后是否超高（如实记录，不改框架）
  await toggleSubmenu('法泽业务', true)
  const sidebarWithAll = await measureSidebar()
  info('侧边栏高度（追加展开「法泽业务」12 项后）', JSON.stringify(sidebarWithAll))
  await screenshot(`h3-sidebar-biz-expanded${SHOT_SUFFIX}.png`)
  // 收起「法泽业务」，让后续页面截图回到默认态
  await toggleSubmenu('法泽业务', false)

  // ── 5. A-27 法条库 ──────────────────────────────────────────────────
  await pushRoute('/content/law-library')
  await sleep(1200)
  await waitFor(`document.querySelector('.page-head__title')?.textContent.includes('法条库')`, 20000, 'A-27 页面标题')
  const a27Title = await evalJs(`document.querySelector('.page-head__title').textContent.trim()`)
  check('A-27 页面可打开', a27Title === '法条库', a27Title)
  // 等侧边栏高亮组的展开动画收尾，避免截图里子项像被裁掉
  await sleep(700)

  const a27Absent = await assertAbsent(['新增法条', '导出条文', '导出筛选结果', '复核状态'])
  check('A-27 三处删除项不在页面', a27Absent.ok, a27Absent.ok ? '已全部删除' : `仍存在：${a27Absent.hit.join('、')}`)
  const a27Present = await assertPresent(['法律名称', '条号', '生效版本', '条文摘要', 'AI 引用次数', '更新时间', '批量导入', '显示已失效'])
  check('A-27 保留列与按钮齐全', a27Present.ok, a27Present.ok ? '全部存在' : `缺少：${a27Present.missing.join('、')}`)

  if (MOCK) {
    await waitFor(`document.querySelectorAll('.el-table__row').length > 0`, 20000, 'A-27 表格数据行（mock）')
    const headers = await evalJs(`(() => {
      const ths = Array.from(document.querySelectorAll('.el-table__header th'));
      return ths.map((th) => th.innerText.trim()).filter(Boolean);
    })()`)
    check('A-27 表头 7 列完整可见（含 更新时间）', headers.length === 7 && headers.includes('更新时间'), headers.join(' | '))
    await screenshot(`h1-a27-law-library${SHOT_SUFFIX}.png`)

    // 查看详情弹窗
    await clickByText('.el-table__row button, .el-table__row .btn', '查看')
    await waitFor(
      `Array.from(document.querySelectorAll('.el-dialog')).some((d) => d.innerText.includes('条文全文'))`,
      15000,
      'A-27 查看弹窗'
    )
    await sleep(700)
    await screenshot(`h1-a27-view-dialog${SHOT_SUFFIX}.png`)
    await closeVisibleDialog('关闭', 'A-27 查看弹窗')
    await sleep(300)

    // 修正弹窗
    await clickByText('.el-table__row button, .el-table__row .btn', '修正')
    await waitFor(
      `Array.from(document.querySelectorAll('.el-dialog')).some((d) => d.offsetParent !== null && d.innerText.includes('修正法条'))`,
      15000,
      'A-27 修正弹窗'
    )
    await sleep(700)
    await screenshot(`h1-a27-amend-dialog${SHOT_SUFFIX}.png`)
    await closeVisibleDialog('取消', 'A-27 修正弹窗')
    await sleep(300)

    // 显示已失效
    await evalJs(`(() => {
      const sw = document.querySelector('.el-switch');
      if (!sw) return false;
      sw.click();
      return true;
    })()`)
    await waitFor(`document.querySelectorAll('.el-table__row').length > 0`, 20000, 'A-27 已停用筛选结果（mock）')
    const disabledRow = await evalJs(`(() => {
      const row = document.querySelector('.el-table__row');
      return row ? row.innerText : '';
    })()`)
    check('A-27「显示已失效」能筛出停用条文', disabledRow.includes('1198') && disabledRow.includes('已停用'), disabledRow.slice(0, 60).replace(/\\s+/g, ' '))
    await sleep(500)
    await screenshot(`h1-a27-disabled${SHOT_SUFFIX}.png`)
    await evalJs(`(() => { const sw = document.querySelector('.el-switch'); if (sw) { sw.click(); } return true; })()`)
    await sleep(600)
  } else {
    // ── 真实接口模式：列表必须真的拿到数据（后端 H1 已上线时）──
    await waitFor(`document.querySelectorAll('.el-table__row').length > 0`, 20000, 'A-27 表格真实数据行')
    const rowCount = await evalJs(`document.querySelectorAll('.el-table__row').length`)
    check('A-27 列表有真实数据（faze-api /admin/law-items）', rowCount > 0, `${rowCount} 行`)
    const headers = await evalJs(`(() => {
      const ths = Array.from(document.querySelectorAll('.el-table__header th'));
      return ths.map((th) => th.innerText.trim()).filter(Boolean);
    })()`)
    check('A-27 表头 7 列完整可见（含 更新时间）', headers.length === 7 && headers.includes('更新时间'), headers.join(' | '))
    const refStats = await evalJs(`(() => {
      const rows = Array.from(document.querySelectorAll('.el-table__row'));
      const nums = rows.map((row) => {
        const el = row.querySelector('.cell-num');
        return el ? el.innerText.trim() : '';
      });
      return { firstRowText: rows[0] ? rows[0].innerText.replace(/\\s+/g, ' ').slice(0, 120) : '', nums };
    })()`)
    check(
      'A-27 AI 引用次数列有数字（B4 口径）',
      refStats.nums.length > 0 && refStats.nums.every((n) => /^\d+$/.test(n)),
      `取值=${JSON.stringify(refStats.nums)}；首行=${refStats.firstRowText}`
    )
    await screenshot(`h1-a27-law-library${SHOT_SUFFIX}.png`)

    // 查看详情弹窗（真实全文）
    await clickByText('.el-table__row button, .el-table__row .btn', '查看')
    await waitFor(
      `Array.from(document.querySelectorAll('.el-dialog')).some((d) => d.innerText.includes('条文全文'))`,
      15000,
      'A-27 查看弹窗'
    )
    await sleep(700)
    const viewContentLen = await evalJs(`(() => {
      const el = document.querySelector('.el-dialog .detail-content');
      return el ? el.innerText.trim().length : 0;
    })()`)
    check('A-27 查看弹窗拉到真实全文', viewContentLen > 20, `全文 ${viewContentLen} 字`)
    await screenshot(`h1-a27-view-dialog${SHOT_SUFFIX}.png`)
    await closeVisibleDialog('关闭', 'A-27 查看弹窗')
    await sleep(300)

    // 修正弹窗（真实全文预填，不提交，避免改开发库数据）
    await clickByText('.el-table__row button, .el-table__row .btn', '修正')
    await waitFor(
      `Array.from(document.querySelectorAll('.el-dialog')).some((d) => d.offsetParent !== null && d.innerText.includes('修正法条'))`,
      15000,
      'A-27 修正弹窗'
    )
    await sleep(700)
    const amendTextLen = await evalJs(`(() => {
      const el = document.querySelector('.el-dialog textarea');
      return el ? el.value.trim().length : 0;
    })()`)
    check('A-27 修正弹窗预填真实全文', amendTextLen > 20, `预填 ${amendTextLen} 字`)
    await screenshot(`h1-a27-amend-dialog${SHOT_SUFFIX}.png`)
    await closeVisibleDialog('取消', 'A-27 修正弹窗')
    await sleep(300)

    // 批量导入弹窗
    await clickByText('.table-toolbar button, .el-button', '批量导入')
    await sleep(900)
    // 注意：el-dialog 关闭后 DOM 还在（只是隐藏），必须找「可见的那个」
    const importDialog = await evalJs(`(() => {
      const d = Array.from(document.querySelectorAll('.el-dialog')).find((el) => el.offsetParent !== null);
      return d ? d.innerText : '';
    })()`)
    check('A-27 批量导入弹窗可打开', importDialog.includes('批量导入法条'), importDialog.slice(0, 40).replace(/\\s+/g, ' '))
    check(
      'A-27 导入弹窗含两种入参方式与上限说明',
      importDialog.includes('上传 JSON 文件') && importDialog.includes('直接粘贴 JSON') && importDialog.includes('500'),
      ''
    )
    await screenshot(`h1-a27-import-dialog${SHOT_SUFFIX}.png`)
    await clickByText('.el-dialog__footer button, .el-dialog button', '取消')
    await sleep(500)
  }

  // ── 6. A-28 AI 对话检索 ─────────────────────────────────────────────
  await pushRoute('/setting/ai-retrieval')
  await sleep(1200)
  await waitFor(`document.querySelector('.page-head__title')?.textContent.includes('AI 对话检索')`, 20000, 'A-28 页面标题')
  const a28Title = await evalJs(`document.querySelector('.page-head__title').textContent.trim()`)
  check('A-28 页面可打开', a28Title === 'AI 对话检索', a28Title)
  await sleep(700)

  const a28Absent = await assertAbsent(['导出检索结果', '法条引用', '复核状态', '标记已复核', '校验状态', '已校验', '条号不存在', '待复核'])
  check('A-28 设计稿删除项不在页面', a28Absent.ok, a28Absent.ok ? '已全部删除' : `仍存在：${a28Absent.hit.join('、')}`)
  const a28Present = await assertPresent(['用户 ID', '时间范围', '对话记录', '回答原文与法条快照', 'refs 快照在回答生成时冻结', '导出本条记录'])
  check('A-28 保留区块与查询项齐全', a28Present.ok, a28Present.ok ? '全部存在' : `缺少：${a28Present.missing.join('、')}`)

  const editable = await evalJs(`(() => {
    const panel = document.querySelector('.ai-detail');
    if (!panel) return { count: -1 };
    return { count: panel.querySelectorAll('input, textarea, [contenteditable="true"]').length };
  })()`)
  check('A-28 详情区无任何编辑控件（只读）', editable.count === 0, `input/textarea 数量=${editable.count}`)

  // 分栏定高：两栏不应超出视口（页脚常驻、内层滚动）——放在数据加载完之后量，避免量到加载中间态
  if (MOCK) {
    await waitFor(`document.querySelectorAll('.rec').length > 0 && document.querySelectorAll('.ai-msg').length > 0`, 20000, 'A-28 会话与消息（mock）')
    const recCount = await evalJs(`document.querySelectorAll('.rec').length`)
    const msgCount = await evalJs(`document.querySelectorAll('.ai-msg').length`)
    const refsCount = await evalJs(`document.querySelectorAll('.ai-refs').length`)
    const noticeText = await evalJs(`Array.from(document.querySelectorAll('.ai-ref__notice')).map((e) => e.innerText).join(' || ')`)
    const deletedTag = await evalJs(`document.body.innerText.includes('用户已删除')`)
    check('A-28 左栏会话队列渲染', recCount === 4, `${recCount} 条`)
    check('A-28 右侧消息与 refs 快照渲染', msgCount === 2 && refsCount === 1, `消息 ${msgCount}，refs 块 ${refsCount}`)
    check('A-28 已软删会话标「用户已删除」', deletedTag === true)
    check(
      'A-28 快照 vs 当前提示（已更新 / 已查不到）',
      noticeText.includes('更新') && noticeText.includes('查不到'),
      noticeText.slice(0, 120)
    )
    await sleep(600)
    await screenshot(`h2-a28-ai-retrieval${SHOT_SUFFIX}.png`)
    // 右栏内容长，滚到 refs 快照区再截一张（快照 vs 当前提示的视觉证据）
    await evalJs(`(() => {
      const body = document.querySelector('.ai-detail .el-card__body');
      if (body) { body.scrollTop = body.scrollHeight; }
      return true;
    })()`)
    await sleep(600)
    await screenshot(`h2-a28-refs${SHOT_SUFFIX}.png`)
    // 滚回顶部，后面的最终稳定态截图仍是「会话头 + 消息」视图
    await evalJs(`(() => {
      const body = document.querySelector('.ai-detail .el-card__body');
      if (body) { body.scrollTop = 0; }
      return true;
    })()`)
    await sleep(300)
  } else {
    await waitFor(`document.querySelectorAll('.rec').length > 0`, 20000, 'A-28 真实会话列表')
    const recCount = await evalJs(`document.querySelectorAll('.rec').length`)
    check('A-28 会话列表有真实数据（faze-api /admin/ai-conversations）', recCount > 0, `${recCount} 条`)
    const deletedTag = await evalJs(`document.body.innerText.includes('用户已删除')`)
    info('A-28 当前页是否出现「用户已删除」', deletedTag ? '有（已标红）' : '本页没有已软删会话（列表含已删除，需筛选到对应时间）')
    // 选一条「引用条数 > 0」的会话，验证真实 refs 快照
    const clickedRefs = await evalJs(`(() => {
      const recs = Array.from(document.querySelectorAll('.rec'));
      const target = recs.find((r) => /[1-9]\\d* 条引用/.test(r.innerText));
      if (!target) return false;
      target.click();
      return true;
    })()`)
    if (clickedRefs) {
      await waitFor(`document.querySelectorAll('.ai-msg').length > 0`, 20000, 'A-28 选中会话的消息')
      await sleep(600)
      const refsCount = await evalJs(`document.querySelectorAll('.ai-refs').length`)
      check('A-28 有引用会话的 refs 快照渲染', refsCount > 0, `refs 块 ${refsCount}`)
      const noticeCount = await evalJs(`document.querySelectorAll('.ai-ref__notice').length`)
      info('A-28 真实数据里「快照 vs 当前」提示条数', `${noticeCount} 条（法条未变更时为 0，属正常）`)
      await evalJs(`(() => {
        const body = document.querySelector('.ai-detail .el-card__body');
        if (body) { body.scrollTop = body.scrollHeight; }
        return true;
      })()`)
      await sleep(600)
      await screenshot(`h2-a28-refs${SHOT_SUFFIX}.png`)
      await evalJs(`(() => {
        const body = document.querySelector('.ai-detail .el-card__body');
        if (body) { body.scrollTop = 0; }
        return true;
      })()`)
      await sleep(300)
    } else {
      info(
        'A-28 当前页没有「引用条数 > 0」的会话，refs 快照未在真实数据上验证',
        'mock 模式有布局证据（h2-a28-refs-mockdata.png）'
      )
    }
    await screenshot(`h2-a28-ai-retrieval${SHOT_SUFFIX}.png`)
  }

  // 分栏定高：两栏不应超出视口（页脚常驻、内层滚动）。放在数据渲染完成之后量，
  // 避免量到「内容刚渲染、flex 还没约束住」的中间态。
  await sleep(500)
  const splitBox = await evalJs(`(() => {
    const split = document.querySelector('.ai-split');
    const card = document.querySelector('.ai-detail');
    const body = document.querySelector('.ai-detail .el-card__body');
    const foot = document.querySelector('.detail-foot .el-button');
    const listFoot = document.querySelector('.ai-list .el-card__footer');
    const exportFoot = document.querySelector('.ai-detail .el-card__footer');
    const col = card ? card.parentElement : null;
    const cs = card ? getComputedStyle(card) : null;
    const colCs = col ? getComputedStyle(col) : null;
    if (!split) return null;
    const r = split.getBoundingClientRect();
    const cr = card ? card.getBoundingClientRect() : null;
    const er = exportFoot ? exportFoot.getBoundingClientRect() : null;
    return {
      splitTop: Math.round(r.top),
      splitBottom: Math.round(r.bottom),
      innerHeight: window.innerHeight,
      cardBottom: cr ? Math.round(cr.bottom) : null,
      cardHeight: cr ? Math.round(cr.height) : null,
      cardStyle: cs ? { height: cs.height, flex: cs.flex, minHeight: cs.minHeight, overflow: cs.overflow } : null,
      colStyle: colCs ? { height: colCs.height, display: colCs.display, alignItems: colCs.alignItems, colH: Math.round(col.getBoundingClientRect().height) } : null,
      bodyClient: body ? body.clientHeight : null,
      bodyScroll: body ? body.scrollHeight : null,
      exportFootTop: er ? Math.round(er.top) : null,
      exportFootBottom: er ? Math.round(er.bottom) : null,
      exportVisible: foot ? foot.offsetParent !== null : false,
      listFootVisible: listFoot ? listFoot.offsetParent !== null : false,
      exportDisabled: foot ? foot.disabled : null
    };
  })()`)
  info('A-28 分栏几何（1440×900）', JSON.stringify(splitBox))
  check(
    'A-28 分栏不超出视口且页脚常驻',
    splitBox &&
      splitBox.splitBottom <= splitBox.innerHeight + 1 &&
      splitBox.cardBottom <= splitBox.innerHeight + 1 &&
      splitBox.exportVisible === true &&
      splitBox.listFootVisible === true,
    JSON.stringify(splitBox)
  )
  // 用最终稳定态重截一次（覆盖前面那张）
  await screenshot(`h2-a28-ai-retrieval${SHOT_SUFFIX}.png`)

  if (failedRequests.length) {
    info('页面发出的 /api 非 2xx 响应', `${failedRequests.length} 条（后端接口未就绪时属预期）`)
    failedRequests.slice(0, 6).forEach((item) => console.log('  · ' + item))
  } else {
    info('页面发出的 /api 全部 2xx', '')
  }
  if (consoleErrors.length) {
    info('浏览器 console 报错', `${consoleErrors.length} 条`)
    consoleErrors.slice(0, 8).forEach((item) => console.log('  · ' + item))
  } else {
    info('浏览器 console 无报错', '')
  }

  // ── 7. 汇总 ─────────────────────────────────────────────────────────
  const failed = results.filter((r) => !r.ok)
  const summary = {
    at: new Date().toISOString(),
    mode: MOCK ? 'mock（仅布局自查，不是接口验收）' : 'real-api',
    viewport: '1440x900',
    sidebar: { contentOpen: sidebarContentOpen, withBizGroupOpen: sidebarWithAll },
    failedRequests,
    consoleErrors,
    results
  }
  fs.writeFileSync(path.join(OUT_DIR, `h-pc-verify${SHOT_SUFFIX}.json`), JSON.stringify(summary, null, 2))
  console.log(`\n===== 结果：${results.length - failed.length}/${results.length} PASS（接口 404 记 INFO，不判 FAIL） =====`)
  if (failed.length) {
    failed.forEach((f) => console.log(`  FAIL: ${f.name} :: ${f.detail}`))
    process.exitCode = 1
  }
  ws.close()
}

async function cleanup() {
  try {
    if (ws) {
      ws.close()
    }
  } catch {}
  if (targetId) {
    try {
      await fetch(`${CDP}/json/close/${targetId}`)
    } catch {}
  }
}

main()
  .then(cleanup)
  .catch(async (e) => {
    console.error('[FATAL]', e.message)
    await cleanup()
    process.exitCode = 2
  })
