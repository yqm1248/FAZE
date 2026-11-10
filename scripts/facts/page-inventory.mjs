/**
 * 页面口径核算脚本 —— 回答「到底有多少个页面」，只用机器可数的证据。
 *
 * 职责：
 *   把 APP 端「页面」这件事在四种载体上的计数与差集打印成人能读的摘要。
 *   四种载体：① `PAGE_ROUTES` 路由常量 ② `app/` 路由壳文件 ③ `features/` 页面实现文件
 *   ④ `design/ui/` 设计稿 HTML。PC 端的设计稿与页面另算。
 *
 * 调用方：
 *   - 人工：`node scripts/facts/page-inventory.mjs`
 *   - `docs/standards/说辞一致与真相源规范.md` §四「数字口径表」引用本脚本，
 *     该表里的数字以机器输出为准，不以人工填写为准。
 *
 * 数据来源：**不自己解析文件**，一律 import `extract-pages.mjs` 的 `collect()`。
 *   2026-11-11 改造前本脚本自带一套解析逻辑，与事实生成器各算各的，属于「同一件事
 *   两套实现」——正是本计划要消灭的形态。现在解析只有一处，本文件只负责排版打印。
 *
 * 注意事项：
 *   - **本脚本不做业务判断**，只做集合运算。哪个差异是缺陷、哪个是合理口径，
 *     由 `docs/facts/DRIFT.md` 裁决，脚本不裁决。
 *   - 退出码恒为 0（本脚本是「读数工具」，不是「门禁」）。门禁形态见 `check-facts.mjs`。
 */

import { collect } from './extract-pages.mjs';

const c = collect();

function line(label, value) {
  console.log(`${label.padEnd(34)} ${value}`);
}

console.log('=== APP 页面口径核算（以仓库文件为准，不含业务判断）===\n');

console.log('[1] 路由常量 PAGE_ROUTES');
line('  key 总数', c.pageRoutes.length);
line('  其中产品路由', c.prodRoutes.length);
line('  其中开发探测路由', c.devRoutes.length);
for (const d of c.devRoutes) console.log(`      dev: ${d.key} -> ${d.path}`);

console.log('\n[2] 路由壳文件 app/**/*.tsx（已排除 _layout / +not-found）');
line('  文件数', c.shells.length);
line('  去重后 URL 数', new Set(c.shells.map((s) => s.url)).size);

console.log('\n[3] 页面实现 features/**/*Page.tsx');
line('  文件数', c.pages.length);
line('  其中探测页', c.pages.filter((p) => p.isDevProbe).length);
for (const d of c.pages.filter((p) => p.isDevProbe)) console.log(`      dev: ${d.file}`);
line('  扣掉探测页后的页面实现数', c.pages.filter((p) => !p.isDevProbe).length);

console.log('\n[4] 设计稿 design/ui/*.html');
line('  HTML 总数', c.design.html.length);
line('  主屏（无 b/c 后缀）', c.design.mainScreens.length);
line('  补充屏（b/c 后缀：弹层/过渡态）', c.design.supplementScreens.length);
for (const s of c.design.supplementScreens) console.log(`      supplement: ${s}`);
line('  PC 后台 design/admin/*.html', c.design.adminHtml.length);

console.log('\n[5] 集合差集（差异本身不是缺陷，处置见 docs/facts/DRIFT.md）');
const d = c.diffs;
console.log(`  A. 路由常量有、路由壳没有（${d.routeWithoutShell.length} 条）`);
for (const p of d.routeWithoutShell) console.log(`      ${p}`);
console.log(`  B. 路由壳有、路由常量没有（${d.shellWithoutRoute.length} 条）`);
for (const p of d.shellWithoutRoute) console.log(`      ${p}`);
console.log(`  C. 页面实现有、无路由壳（${d.pageWithoutShell.length} 条）`);
for (const p of d.pageWithoutShell) console.log(`      ${p}`);
console.log(`  D. 路由壳指向的实现不存在（${d.shellWithoutPage.length} 条）`);
for (const p of d.shellWithoutPage) console.log(`      ${p}`);
console.log(`  E. 路由壳不是纯再导出（${d.impureShells.length} 条）`);
for (const p of d.impureShells) console.log(`      ${p}`);
console.log(`  F. 页面写死路由字符串（${d.literalNavigations.length} 条，违反 routes.ts 禁止项）`);
for (const p of d.literalNavigations) console.log(`      ${p.file} -> ${p.targets.join(', ')}`);

console.log('\n[6] 三种「页面数」的口径与构成（口径表用这一段）');
console.log(`  产品页面数        = ${c.counts.productPages}   （PAGE_ROUTES ${c.counts.pageRoutes} − 开发探测 ${c.counts.devRoutes}）`);
console.log(`  设计稿主屏数      = ${c.counts.designMainScreens}   （design/ui ${c.counts.designHtml} − 补充屏 ${c.counts.designSupplementScreens}）`);
console.log(`  页面实现数        = ${c.counts.pageImpls}   （features/**/*Page.tsx，含开发探测页）`);
console.log(`  路由壳数          = ${c.counts.shells}`);
console.log(`  产品页 vs 主屏差  = ${c.counts.productPages - c.counts.designMainScreens}   （>0 表示有页面无设计稿）`);
console.log(`  PC 后台屏数       = ${c.counts.adminScreens}   （与 APP 是两套口径，不相加）`);
console.log(`  有 testID 锚点的页 = ${c.pages.filter((p) => p.testIdCount > 0).length} / ${c.pages.length}`);
