/**
 * 页面口径核算脚本 —— 回答「到底有多少个页面」，只用机器可数的证据。
 *
 * 职责：
 *   把 APP 端「页面」这件事在四种载体上的计数与差集算出来，输出到 stdout。
 *   四种载体：① `PAGE_ROUTES` 路由常量 ② `app/` 路由壳文件 ③ `features/` 页面实现文件
 *   ④ `design/ui/` 设计稿 HTML。PC 端的设计稿与页面另算。
 *
 * 调用方：
 *   - 人工：`node scripts/facts/page-inventory.mjs`
 *   - `docs/standards/说辞一致与真相源规范.md` §四「数字口径表」引用本脚本，
 *     该表里的数字以此脚本输出为准，不以人工填写为准。
 *
 * 数据来源：只读文件系统，不连数据库、不连服务端。
 *
 * 注意事项：
 *   - **本脚本不做业务判断**，只做集合运算。哪个差异是缺陷、哪个是合理口径，
 *     由 `docs/facts/DRIFT.md` 裁决，脚本不裁决。
 *   - 排除清单集中在本文件顶部的常量里，改排除规则必须同时改这里，不许散落在各处。
 *   - `(tabs)` 这类 route group 不占 URL 段，转 URL 时要去掉。
 *
 * 退出码：0 恒为成功（本脚本是「读数工具」，不是「门禁」）。
 *   要做门禁请用 `--assert`，此时发现未登记差异返回 1。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const APP_DIR = join(REPO, 'apps', 'mobile-app');
const ROUTES_FILE = join(APP_DIR, 'shared', 'navigation', 'routes.ts');

/** 不是「页面」的路由壳文件名（expo-router 约定）。 */
const NON_PAGE_SHELLS = new Set(['_layout.tsx', '+not-found.tsx']);

/** 开发期探测页的路由 key；它们算在路由常量里，但不算产品页面。 */
const DEV_ROUTE_KEYS = new Set(['devProbe', 'devRtl']);

/** 设计稿文件名形如 `07b-xxx.html` 的「b/c 后缀补充屏」：弹层 / 过渡态，不是独立页面。 */
const SUPPLEMENT_SCREEN_RE = /^\d{2}[a-z]-/;

/** 递归列出某目录下指定扩展名的文件，返回相对仓库根的 POSIX 风格路径。 */
function walk(dir, ext) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      out.push(...walk(full, ext));
    } else if (entry.endsWith(ext)) {
      out.push(relative(REPO, full).split(sep).join('/'));
    }
  }
  return out;
}

/** 从 `routes.ts` 抽出 `key: '/path' as Href` 形式的全部常量。 */
function readPageRoutes() {
  const src = readFileSync(ROUTES_FILE, 'utf8');
  const re = /^\s{2}(\w+):\s*'([^']+)'\s*as\s+Href,?$/gm;
  const rows = [];
  let m;
  while ((m = re.exec(src)) !== null) {
    rows.push({ key: m[1], path: m[2] });
  }
  return rows;
}

/** 把 `app/` 下的路由壳文件路径转成 URL，`(tabs)` 这类 route group 去掉。 */
function shellToUrl(relPath) {
  let s = relPath.replace(/^apps\/mobile-app\/app\//, '').replace(/\.tsx$/, '');
  const segs = s.split('/').filter((seg) => !(seg.startsWith('(') && seg.endsWith(')')));
  if (segs.length === 0) return '/';
  const last = segs.pop();
  const base = last === 'index' ? '' : `/${last}`;
  return (segs.length > 0 ? `/${segs.join('/')}` : '') + base || '/';
}

const pageRoutes = readPageRoutes();

const shellFiles = walk(join(APP_DIR, 'app'), '.tsx').filter(
  (p) => !NON_PAGE_SHELLS.has(p.split('/').pop()),
);
const shellUrls = shellFiles.map(shellToUrl).sort();

const routePaths = pageRoutes.map((r) => r.path).sort();

const onlyInRoutes = routePaths.filter((p) => !shellUrls.includes(p));
const onlyInShells = shellUrls.filter((p) => !routePaths.includes(p));

const prodRoutes = pageRoutes.filter((r) => !DEV_ROUTE_KEYS.has(r.key));
const devRoutes = pageRoutes.filter((r) => DEV_ROUTE_KEYS.has(r.key));

const pageImpls = walk(join(APP_DIR, 'features'), 'Page.tsx').sort();
const devPageImpls = pageImpls.filter((p) => /(Probe|ProbePage|RtlProbe)Page\.tsx$/.test(p));

const designDir = join(REPO, 'design', 'ui');
const designHtml = readdirSync(designDir)
  .filter((f) => f.endsWith('.html'))
  .sort();
const supplementScreens = designHtml.filter((f) => SUPPLEMENT_SCREEN_RE.test(f));
const mainScreens = designHtml.filter((f) => !SUPPLEMENT_SCREEN_RE.test(f));

const adminHtml = readdirSync(join(REPO, 'design', 'admin'))
  .filter((f) => f.endsWith('.html'))
  .sort();

function line(label, value) {
  console.log(`${label.padEnd(34)} ${value}`);
}

console.log('=== APP 页面口径核算（以仓库文件为准，不含业务判断）===\n');

console.log('[1] 路由常量 PAGE_ROUTES');
line('  key 总数', pageRoutes.length);
line('  其中产品路由', prodRoutes.length);
line('  其中开发探测路由', devRoutes.length);
for (const d of devRoutes) console.log(`      dev: ${d.key} -> ${d.path}`);

console.log('\n[2] 路由壳文件 app/**/*.tsx（已排除 _layout / +not-found）');
line('  文件数', shellFiles.length);
line('  去重后 URL 数', new Set(shellUrls).size);

console.log('\n[3] 页面实现 features/**/*Page.tsx');
line('  文件数', pageImpls.length);
line('  其中探测页', devPageImpls.length);
for (const d of devPageImpls) console.log(`      dev: ${d}`);
line('  扣掉探测页后的页面实现数', pageImpls.length - devPageImpls.length);

console.log('\n[4] 设计稿 design/ui/*.html');
line('  HTML 总数', designHtml.length);
line('  主屏（无 b/c 后缀）', mainScreens.length);
line('  补充屏（b/c 后缀：弹层/过渡态）', supplementScreens.length);
for (const s of supplementScreens) console.log(`      supplement: ${s}`);
line('  PC 后台 design/admin/*.html', adminHtml.length);

console.log('\n[5] 集合差集（差异本身不是缺陷，处置见 docs/facts/DRIFT.md）');
console.log(`  A. 路由常量有、路由壳没有（${onlyInRoutes.length} 条）`);
for (const p of onlyInRoutes) console.log(`      ${p}`);
console.log(`  B. 路由壳有、路由常量没有（${onlyInShells.length} 条）`);
for (const p of onlyInShells) console.log(`      ${p}`);

console.log('\n[6] 三种「页面数」的口号与构成（口径表用这一段）');
console.log(`  产品页面数        = ${prodRoutes.length}   （PAGE_ROUTES ${pageRoutes.length} − 开发探测 ${devRoutes.length}）`);
console.log(`  设计稿主屏数      = ${mainScreens.length}   （design/ui ${designHtml.length} − 补充屏 ${supplementScreens.length}）`);
console.log(`  页面实现数        = ${pageImpls.length}   （features/**/*Page.tsx，含开发探测页）`);
console.log(`  路由壳数          = ${shellFiles.length}`);
console.log(`  产品页 vs 主屏差  = ${prodRoutes.length - mainScreens.length}   （>0 表示有页面无设计稿）`);
console.log(`  PC 后台屏数       = ${adminHtml.length}   （与 APP 是两套口径，不相加）`);
