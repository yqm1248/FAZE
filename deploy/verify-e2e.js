/*
 * 第 1 段端到端验收（后端直测 + 真实库，不经 UI）。
 *
 * 覆盖锚点里「只看页面截图证明不了」的部分：
 *   1-A1  注册/登录/忘记密码/改密/注销/同号重注册 七步
 *   1-A2  实名提交 → PC 审核 → App 状态变
 *   1-A3  律师入驻四步提交 → PC 审核 → App 状态变
 *   1-A4  PC 充值 → App 余额与流水可见（并验证 profile 的流水口径余额）
 *   1-A5  支付密码连错 5 次锁定 + Retry-After + 锁定期内正确密码也被拒
 *   1-A10 未成年人不填监护人被拒（接口层）+ 填了能注册
 *
 * 每次运行用一个**全新手机号**（脚本内 ++ 生成），因为注销是全链路最后一步。
 * 管理端 token 直接调 RuoYi /login 拿，不依赖 CDP。
 *
 * 用法：node verify-e2e.js
 * 退出码：0 全部 PASS / 1 有 FAIL
 */
const fs = require('node:fs');

const BASE = 'http://127.0.0.1:8081';
const RUOYI = 'http://127.0.0.1:8080';
/** 起始号段；实际用的号由运行时间决定，避免与历史数据撞唯一键。 */
const PHONE_SUFFIX = String(Date.now()).slice(-4);
const PHONE = '1380000' + PHONE_SUFFIX;
const MINOR_PHONE = '1370000' + PHONE_SUFFIX;
const PWD_INIT = 'Faze123456';
const PWD_NEW = 'Faze654321';
const PAY_PWD = '135790';
const FIXED_CODE = '123456';
const path = require('node:path');
const REPO_ROOT = path.resolve(__dirname, '..');
const IMG_PATH = REPO_ROOT + '/docs/acceptance/seg1/G5-states/01-splash.png';

const results = [];
let userToken = null;
let adminToken = null;
let userId = null;

/** 记一条结果。 */
function check(id, desc, ok, detail) {
  results.push({ id, desc, ok, detail: detail === undefined ? '' : String(detail) });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id.padEnd(16)} ${desc}${detail ? '  [' + detail + ']' : ''}`);
}

/** 调 faze-api。 */
async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method: opts.method || 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    json = null;
  }
  return {
    status: res.status,
    json,
    retryAfter: res.headers.get('Retry-After'),
    remaining: res.headers.get('X-Pay-Pwd-Remaining'),
  };
}

/** 调管理端接口（带 RuoYi token）。 */
function adminApi(path, opts = {}) {
  return api(path, { ...opts, token: adminToken });
}

/** 发短信验证码（dev 固定码）。 */
function sendCode(phone, scene) {
  return api('/api/v1/pub/sms/code', { method: 'POST', body: { phone, scene } });
}

/** 等 N 毫秒（同号发码有 60 秒间隔）。 */
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/** 从列表响应里取 rows（兼容 R<TableDataInfo> 的两种包法）。 */
function rowsOf(resp) {
  const j = resp.json || {};
  if (j.data && Array.isArray(j.data.rows)) return j.data.rows;
  if (Array.isArray(j.rows)) return j.rows;
  return [];
}

async function main() {
  console.log(`本次用手机号：${PHONE} / 未成年号：${MINOR_PHONE}\n`);
  const imgBytes = fs.readFileSync(IMG_PATH);

  // ── 0. 管理端 token ───────────────────────────────────────────────────
  const login = await fetch(RUOYI + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  }).then((r) => r.json());
  adminToken = login.token;
  check('E2E-0', '管理端 token 可用（RuoYi /login）', Boolean(adminToken), `code=${login.code}`);

  // ── 1. 1-A1① 注册 ──────────────────────────────────────────────────────
  const code0 = await sendCode(PHONE, 'register');
  const reg = await api('/api/v1/pub/auth/register', {
    method: 'POST',
    body: { phone: PHONE, smsCode: FIXED_CODE, password: PWD_INIT, isMinor: false, agreementAccepted: true },
  });
  userToken = reg.json && reg.json.data ? reg.json.data.accessToken : null;
  userId = reg.json && reg.json.data ? reg.json.data.user.id : null;
  check('1A1-1', '注册成功（发码+注册）', Boolean(userToken), `code=${reg.json && reg.json.code} userId=${userId} sms=${code0.json && code0.json.code}`);

  // ── 2. 1-A1②③ 登录 + 我的数据 ─────────────────────────────────────────
  const prof0 = await api('/api/v1/app/account/profile', { token: userToken });
  const p0 = (prof0.json && prof0.json.data) || {};
  check('1A1-2', '登录态可取资料（手机号脱敏）', /^\d{3}\*{4}\d{4}$/.test(p0.phoneMasked || ''), `phoneMasked=${p0.phoneMasked}`);
  check('FIX-1', 'profile.payPasswordSet 可读（未设=false）', p0.payPasswordSet === false, `payPasswordSet=${p0.payPasswordSet}`);
  check('FIX-2', 'profile.lawyerStatus 可读（未入驻=NONE）', p0.lawyerStatus === 'NONE', `lawyerStatus=${p0.lawyerStatus}`);
  check('FIX-3', 'profile.balanceCents 可读（无流水=0）', String(p0.balanceCents) === '0', `balanceCents=${p0.balanceCents}`);

  // ── 3. 1-A5 支付密码：设置 → 连错 5 次锁定 ─────────────────────────────
  const setPay = await api('/api/v1/app/account/pay-password', { method: 'POST', token: userToken, body: { password: PAY_PWD } });
  check('1A5-1', '支付密码设置成功', setPay.json && setPay.json.code === 200, `code=${setPay.json && setPay.json.code}`);

  const prof1 = await api('/api/v1/app/account/profile', { token: userToken });
  check('FIX-1b', '设置后 profile.payPasswordSet=true', prof1.json && prof1.json.data && prof1.json.data.payPasswordSet === true, `payPasswordSet=${prof1.json && prof1.json.data && prof1.json.data.payPasswordSet}`);

  const remainingSeq = [];
  let locked = null;
  for (let i = 1; i <= 5; i++) {
    const bad = await api('/api/v1/app/account/pay-password/verify', { method: 'POST', token: userToken, body: { password: '000000' } });
    remainingSeq.push(bad.remaining);
    if (bad.json && bad.json.code === 401007) {
      locked = bad;
      break;
    }
  }
  check('1A5-2', '连错返回剩余次数（X-Pay-Pwd-Remaining）', remainingSeq.slice(0, 4).every((v) => v !== null && v !== undefined), `remaining=${JSON.stringify(remainingSeq)}`);
  check('1A5-3', '第 5 次错 → 401007 锁定', Boolean(locked), `code=${locked && locked.json && locked.json.code}`);
  check('1A5-4', '锁定响应带 Retry-After', Boolean(locked && locked.retryAfter), `Retry-After=${locked && locked.retryAfter}`);

  const rightWhileLocked = await api('/api/v1/app/account/pay-password/verify', { method: 'POST', token: userToken, body: { password: PAY_PWD } });
  check('1A5-5', '锁定期内正确密码也被拒（401007）', rightWhileLocked.json && rightWhileLocked.json.code === 401007, `code=${rightWhileLocked.json && rightWhileLocked.json.code}`);

  // ── 4. 1-A2 实名：上传 → 提交 → PC 审核 → 状态变 ───────────────────────
  const up1 = await api('/api/v1/app/upload/token', {
    method: 'POST',
    token: userToken,
    body: { scene: 'realname', fileName: 'front.png', size: imgBytes.length, contentType: 'image/png' },
  });
  const up2 = await api('/api/v1/app/upload/token', {
    method: 'POST',
    token: userToken,
    body: { scene: 'realname', fileName: 'back.png', size: imgBytes.length, contentType: 'image/png' },
  });
  const key1 = up1.json && up1.json.data && up1.json.data.key;
  const key2 = up2.json && up2.json.data && up2.json.data.key;
  const putRes1 = await fetch(up1.json.data.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: imgBytes });
  const putRes2 = await fetch(up2.json.data.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: imgBytes });
  check('1A2-1', '三段式上传：token + PUT 成功', putRes1.ok && putRes2.ok && Boolean(key1), `put=${putRes1.status}/${putRes2.status}`);

  const submitReal = await api('/api/v1/app/account/realname', {
    method: 'POST',
    token: userToken,
    body: { realName: '端到端测试', idCardNumber: '650102199001011234', idCardFront: key1, idCardBack: key2 },
  });
  check('1A2-2', '实名提交成功', submitReal.json && submitReal.json.code === 200, `code=${submitReal.json && submitReal.json.code} msg=${submitReal.json && submitReal.json.msg}`);

  const realPending = await api('/api/v1/app/account/realname', { token: userToken });
  check('1A2-3', '提交后 App 状态=PENDING', realPending.json && realPending.json.data && realPending.json.data.status === 'PENDING', `status=${realPending.json && realPending.json.data && realPending.json.data.status}`);

  const queue = await adminApi('/api/v1/admin/verifications?type=realname&status=pending&pageNum=1&pageSize=50');
  const qRows = rowsOf(queue);
  const mine = qRows.find((r) => String(r.userId) === String(userId));
  check('1A2-4', 'PC 待审队列出现该条（身份证脱敏）', Boolean(mine) && /\*/.test(mine.idCardMasked || ''), `rows=${qRows.length} idCard=${mine && mine.idCardMasked}`);

  let auditReal = { json: null };
  if (mine) {
    auditReal = await adminApi(`/api/v1/admin/verifications/${mine.id}/audit`, { method: 'POST', body: { type: 'realname', action: 'approve' } });
  }
  check('1A2-5', 'PC 审核通过', auditReal.json && auditReal.json.code === 200, `code=${auditReal.json && auditReal.json.code}`);

  const realAfter = await api('/api/v1/app/account/realname', { token: userToken });
  check('1A2-6', '审核后 App 状态=APPROVED（含审核时间）', realAfter.json && realAfter.json.data && realAfter.json.data.status === 'APPROVED' && Boolean(realAfter.json.data.reviewedAt), `status=${realAfter.json && realAfter.json.data && realAfter.json.data.status}`);

  // ── 5. 1-A3 律师入驻：四步提交 → PC 审核 → 状态变 ──────────────────────
  const upLic = await api('/api/v1/app/upload/token', {
    method: 'POST',
    token: userToken,
    body: { scene: 'license', fileName: 'license.png', size: imgBytes.length, contentType: 'image/png' },
  });
  const licKey = upLic.json && upLic.json.data && upLic.json.data.key;
  await fetch(upLic.json.data.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: imgBytes });

  const submitLawyer = await api('/api/v1/app/lawyer-applications', {
    method: 'POST',
    token: userToken,
    body: {
      step: 4,
      submit: true,
      realName: '端到端测试',
      licenseNumber: 'E2E-LIC-' + PHONE_SUFFIX,
      licenseImage: licKey,
      lawFirm: '端到端律师事务所',
      specialties: ['劳动争议', '合同纠纷'],
      bio: '端到端验收用，非真实律师',
    },
  });
  check('1A3-1', '四步入驻提交成功', submitLawyer.json && submitLawyer.json.code === 200, `code=${submitLawyer.json && submitLawyer.json.code} msg=${submitLawyer.json && submitLawyer.json.msg}`);

  const curApp = await api('/api/v1/app/lawyer-applications/current', { token: userToken });
  check('1A3-2', '提交后 App 状态=PENDING', curApp.json && curApp.json.data && curApp.json.data.status === 'PENDING', `status=${curApp.json && curApp.json.data && curApp.json.data.status}`);

  const lq = await adminApi('/api/v1/admin/lawyer-applications?status=pending&pageNum=1&pageSize=50');
  const lrows = rowsOf(lq);
  // 队列行的字段是 applicantUserId（不是 userId）
  const lmine = lrows.find((r) => String(r.applicantUserId) === String(userId));
  check('1A3-3', 'PC 待审队列出现该申请（四步字段齐）', Boolean(lmine) && Boolean(lmine.lawFirm), `rows=${lrows.length} firm=${lmine && lmine.lawFirm}`);

  let auditLawyer = { json: null };
  if (lmine) {
    auditLawyer = await adminApi(`/api/v1/admin/lawyer-applications/${lmine.id}/audit`, { method: 'POST', body: { action: 'approve' } });
  }
  check('1A3-4', 'PC 审核通过（写 lawyer 表）', auditLawyer.json && auditLawyer.json.code === 200, `code=${auditLawyer.json && auditLawyer.json.code}`);

  const curAfter = await api('/api/v1/app/lawyer-applications/current', { token: userToken });
  check('1A3-5', '审核后 App 状态=APPROVED', curAfter.json && curAfter.json.data && curAfter.json.data.status === 'APPROVED', `status=${curAfter.json && curAfter.json.data && curAfter.json.data.status}`);

  const prof2 = await api('/api/v1/app/account/profile', { token: userToken });
  check('FIX-2b', '审核后 profile.lawyerStatus=APPROVED', prof2.json && prof2.json.data && prof2.json.data.lawyerStatus === 'APPROVED', `lawyerStatus=${prof2.json && prof2.json.data && prof2.json.data.lawyerStatus}`);

  // ── 6. 1-A4 PC 充值 → App 余额与流水（充值端点 userId 在路径里）────────
  const bizNo = 'e2e-recharge-' + Date.now();
  const recharge = await adminApi(`/api/v1/admin/wallets/${userId}/recharge`, {
    method: 'POST',
    headers: { 'Idempotency-Key': bizNo },
    body: { amountCents: 10000, reason: '端到端验收充值 100 元', bizNo },
  });
  check('1A4-1', 'PC 充值 100 元成功', recharge.json && recharge.json.code === 200, `code=${recharge.json && recharge.json.code} msg=${recharge.json && recharge.json.msg}`);

  const bal = await api('/api/v1/app/wallet/balance', { token: userToken });
  const balData = (bal.json && bal.json.data) || {};
  check('1A4-2', 'App 余额 = 100.00 元', String(balData.availableCents) === '10000' || String(balData.totalCents) === '10000', `available=${balData.availableCents} total=${balData.totalCents}`);

  const flows = await api('/api/v1/app/wallet/flows?pageNum=1&pageSize=10', { token: userToken });
  const flowList = (flows.json && flows.json.data && flows.json.data.list) || [];
  check('1A4-3', 'App 流水新增 1 条且金额正确', flowList.length === 1 && String(flowList[0].deltaCents) === '10000', `count=${flowList.length} delta=${flowList[0] && flowList[0].deltaCents}`);

  const prof3 = await api('/api/v1/app/account/profile', { token: userToken });
  check('FIX-3b', 'profile.balanceCents 走流水口径=10000', String(prof3.json && prof3.json.data && prof3.json.data.balanceCents) === '10000', `balanceCents=${prof3.json && prof3.json.data && prof3.json.data.balanceCents}`);

  // ── 7. 1-A10 未成年：接口层必填联动 ────────────────────────────────────
  await sendCode(MINOR_PHONE, 'register');
  const minorFail = await api('/api/v1/pub/auth/register', {
    method: 'POST',
    body: { phone: MINOR_PHONE, smsCode: FIXED_CODE, password: PWD_INIT, isMinor: true, agreementAccepted: true },
  });
  check('1A10-1', '未成年不填监护人 → 402009', minorFail.json && minorFail.json.code === 402009, `code=${minorFail.json && minorFail.json.code}`);

  // 失败那次可能已消费验证码，重新发一次再提交
  await sendCode(MINOR_PHONE, 'register');
  const minorOk = await api('/api/v1/pub/auth/register', {
    method: 'POST',
    body: { phone: MINOR_PHONE, smsCode: FIXED_CODE, password: PWD_INIT, isMinor: true, guardianPhone: '13700000000', agreementAccepted: true },
  });
  check('1A10-2', '填监护人后注册成功', minorOk.json && minorOk.json.code === 200, `code=${minorOk.json && minorOk.json.code} msg=${minorOk.json && minorOk.json.msg}`);

  // ── 8. 1-A1⑤ 有余额时不允许注销 ────────────────────────────────────────
  const cancelWithMoney = await api('/api/v1/app/account', { method: 'DELETE', token: userToken });
  check('1A1-3', '有余额时注销被拒（401006）', cancelWithMoney.json && cancelWithMoney.json.code === 401006, `code=${cancelWithMoney.json && cancelWithMoney.json.code}`);

  // ── 9. 1-A1④⑤ 忘记密码 → 重置 → 新密码登录（此时才发码，避开 60 秒间隔）
  await sleep(62000);
  const codeReset = await sendCode(PHONE, 'reset');
  const reset = await api('/api/v1/pub/auth/reset-password', {
    method: 'POST',
    body: { phone: PHONE, smsCode: FIXED_CODE, newPassword: PWD_NEW },
  });
  check('1A1-4', '忘记密码：重置成功', reset.json && reset.json.code === 200, `code=${reset.json && reset.json.code} sms=${codeReset.json && codeReset.json.code}`);

  const oldLogin = await api('/api/v1/pub/auth/login/password', { method: 'POST', body: { phone: PHONE, password: PWD_INIT, deviceId: 'e2e-old' } });
  check('1A1-5', '旧密码已失效（402001）', oldLogin.json && oldLogin.json.code === 402001, `code=${oldLogin.json && oldLogin.json.code}`);

  const newLogin = await api('/api/v1/pub/auth/login/password', { method: 'POST', body: { phone: PHONE, password: PWD_NEW, deviceId: 'e2e-new' } });
  userToken = newLogin.json && newLogin.json.data ? newLogin.json.data.accessToken : userToken;
  check('1A1-6', '新密码可登录', newLogin.json && newLogin.json.code === 200, `code=${newLogin.json && newLogin.json.code}`);

  // ── 10. 1-A1⑥⑦ 清空余额 → 注销 → 同号立即重新注册 ─────────────────────
  const adjust = await adminApi(`/api/v1/admin/wallets/${userId}/adjust`, {
    method: 'POST',
    headers: { 'Idempotency-Key': 'e2e-adjust-' + Date.now() },
    body: { deltaCents: -10000, reason: '端到端验收：清空余额后再注销' },
  });
  check('1A1-7', '管理端调账清空余额', adjust.json && adjust.json.code === 200, `code=${adjust.json && adjust.json.code} msg=${adjust.json && adjust.json.msg}`);

  const cancelOk = await api('/api/v1/app/account', { method: 'DELETE', token: userToken });
  check('1A1-8', '余额清零后注销成功', cancelOk.json && cancelOk.json.code === 200, `code=${cancelOk.json && cancelOk.json.code} msg=${cancelOk.json && cancelOk.json.msg}`);

  const afterCancel = await api('/api/v1/app/account/profile', { token: userToken });
  check('1A1-9', '注销后旧 token 失效（402003）', afterCancel.json && afterCancel.json.code === 402003, `code=${afterCancel.json && afterCancel.json.code}`);

  await sleep(62000);
  const codeAgain = await sendCode(PHONE, 'register');
  const reReg = await api('/api/v1/pub/auth/register', {
    method: 'POST',
    body: { phone: PHONE, smsCode: FIXED_CODE, password: PWD_INIT, isMinor: false, agreementAccepted: true },
  });
  check('1A1-10', '注销后同号可立即重新注册', reReg.json && reReg.json.code === 200, `code=${reReg.json && reReg.json.code} sms=${codeAgain.json && codeAgain.json.code}`);

  // ── 汇总 ───────────────────────────────────────────────────────────────
  const fail = results.filter((r) => !r.ok);
  console.log(`\n===== 端到端：${results.length - fail.length}/${results.length} 通过 =====`);
  if (fail.length) {
    console.log('失败项：');
    for (const f of fail) console.log(`  - ${f.id} ${f.desc}  ${f.detail}`);
  }
  fs.mkdirSync(REPO_ROOT + '/docs/acceptance/seg1', { recursive: true });
  fs.writeFileSync(
    REPO_ROOT + '/docs/acceptance/seg1/e2e-report.json',
    JSON.stringify({ runAt: new Date().toISOString(), phone: PHONE, minorPhone: MINOR_PHONE, userId, results }, null, 2),
  );
  process.exit(fail.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('脚本异常：' + String(e));
  process.exit(2);
});
