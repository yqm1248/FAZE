/**
 * 页面事实生成脚本 —— 逐页抽取「文件 / 路由 / testID 锚点 / LocalizedText 使用」。
 *
 * 职责：
 *   把 APP 端每个页面的可机器观测特征抽出来，输出 `docs/facts/pages.json`。
 *   它同时是三种「页面数」口径的**唯一解析实现**：`page-inventory.mjs` 只 import
 *   本文件的 `collect()` 做打印，不另写一套解析，避免同一件事出现两套数字。
 *
 * 调用方：
 *   - `node scripts/facts/extract-pages.mjs`（人工 / `npm run facts`）
 *   - `scripts/facts/page-inventory.mjs`（import `collect()` 打印口径摘要）
 *   - `scripts/facts/render-docs.mjs`（渲染文档里的 `FACTS:pages` 受控区块）
 *   - CI 的 facts 同步检查（重跑后 `git diff --exit-code docs/facts/` 必须为空）
 *
 * 数据来源：只读 `apps/mobile-app/{app,features,shared/navigation}` 与 `design/`，不连服务端。
 *
 * 注意事项：
 *   1. **本脚本不做业务判断**。哪个差异是缺陷、哪个是合理口径，由 `docs/facts/DRIFT.md`
 *      裁决。脚本只负责把差异算出来并逐条列出。
 *   2. `testID` 是 E2E 锚点，缺它不代表页面坏，只代表「自动化点不到这个页面」——
 *      这是事实，不是缺陷。清单交给 G7 的不可见断言挑重点。
 *   3. 裸 `<Text`（不含 `LocalizedText`）是**违规信号**，`check-raw-text.ps1` 已在 CI 拦。
 *      这里仍统计，是因为要能回答「还剩几处、在哪几页」，而不是「有没有」。
 *   4. 不写生成时间，写 `sourceDigest`：源码不变则文件字节不变，`git diff --exit-code`
 *      才能在 CI 里当门禁用（写当天日期会让次日必然失败）。
 *   5. 退出码恒为 0（事实生成器不是门禁）。
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const APP_DIR = join(REPO, 'apps', 'mobile-app');
const ROUTES_FILE = join(APP_DIR, 'shared', 'navigation', 'routes.ts');
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'pages.json');

/** expo-router 的约定文件，不是页面。 */
const NON_PAGE_SHELLS = new Set(['_layout.tsx', '+not-found.tsx']);

/** 开发期探测页的路由 key；算在路由常量里，不算产品页面。 */
const DEV_ROUTE_KEYS = new Set(['devProbe', 'devRtl']);

/** 设计稿的「b/c 后缀补充屏」：弹层 / 过渡态，不是独立页面。 */
const SUPPLEMENT_SCREEN_RE = /^\d{2}[a-z]-/;

/** 递归列出目录下指定扩展名文件（绝对路径，已排序，跨平台稳定）。 */
function walk(dir, ext) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const entry of readdirSync(dir).sort()) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full, ext));
    else if (entry.endsWith(ext)) out.push(full);
  }
  return out;
}

/** 仓库相对路径，统一用 `/`（Windows 与 Linux 输出一致，否则 CI diff 会炸）。 */
function rel(abs) {
  return relative(REPO, abs).split(sep).join('/');
}

/** 从 `routes.ts` 抽出 `key: '/path' as Href` 形式的全部常量。 */
function readPageRoutes() {
  const src = readFileSync(ROUTES_FILE, 'utf8');
  const re = /^\s{2}(\w+):\s*'([^']+)'\s*as\s+Href,?$/gm;
  const rows = [];
  let m;
  while ((m = re.exec(src)) !== null) rows.push({ key: m[1], path: m[2] });
  return rows;
}

/** `app/` 下的路由壳文件路径 → URL（`(tabs)` 这类 route group 不占 URL 段）。 */
function shellToUrl(relPathUnderApp) {
  const segs = relPathUnderApp
    .replace(/\.tsx$/, '')
    .split('/')
    .filter((seg) => !(seg.startsWith('(') && seg.endsWith(')')));
  if (segs.length === 0) return '/';
  const last = segs.pop();
  const base = last === 'index' ? '' : `/${last}`;
  const prefix = segs.length > 0 ? `/${segs.join('/')}` : '';
  return prefix + base || '/';
}

/**
 * 路由壳里唯一被允许的内容：`export { X as default } from '@/features/...'`。
 * 两种写法都要认：`export { FooPage as default }` 与 `export { default }`（实现侧用 default 导出时）。
 */
function parseShellTarget(src) {
  const m = /export\s*\{\s*(?:(\w+)\s+as\s+default|default)\s*\}\s*from\s*'([^']+)'/.exec(src);
  if (!m) return null;
  return { exportName: m[1] ?? 'default', modulePath: m[2] };
}

/** 把 `@/features/home/pages/HomePage` 这类别名路径解析成仓库相对文件路径。 */
function resolveAlias(modulePath) {
  if (!modulePath.startsWith('@/')) return null;
  const candidate = join(APP_DIR, modulePath.slice(2) + '.tsx');
  return existsSync(candidate) ? rel(candidate) : null;
}

/** 统计一个页面源码里的可观测特征。只做计数与摘取，不做风格裁决。 */
function inspectPage(abs, src) {
  // 先剥注释再统计：注释里写「禁裸 <Text>」这种说明文字不该被算成违规
  // （2026-11-11 实测：不做剥离时 FaqPage、RechargePage 各误报 1 处，且两处都在块注释里）
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const testIds = [...code.matchAll(/testID\s*=\s*(?:"([^"]+)"|\{'([^']+)'\})/g)].map(
    (m) => m[1] ?? m[2],
  );
  const localized = [...code.matchAll(/<LocalizedText\b/g)].length;
  // 裸 <Text：排除 LocalizedText；`<Text` + 空白或 `>` 才算，避免匹配到 TextStyle 之类
  const rawText = [...code.matchAll(/<Text(?=[\s>])/g)].length;
  const navigations = [...code.matchAll(/router\.(?:push|replace|navigate)\(\s*(?:PAGE_ROUTES\.(\w+)|[`'"]([^`'"]+)[`'"])/g)]
    .map((m) => (m[1] ? { kind: 'routeKey', value: m[1] } : { kind: 'literal', value: m[2] }))
    // 模板字符串里已经引用了 PAGE_ROUTES 的不算「写死路由」：
    // router.push(`${String(PAGE_ROUTES.docForm)}?templateId=${id}`) 是合规写法
    .filter((n) => !(n.kind === 'literal' && n.value.includes('PAGE_ROUTES.')));
  const imports = [...code.matchAll(/from\s+'(@\/[^']+)'/g)].map((m) => m[1]);
  return {
    lines: src.split('\n').length,
    testIdCount: testIds.length,
    testIds,
    localizedTextCount: localized,
    rawTextCount: rawText,
    navigations,
    imports,
  };
}

/**
 * 收集全部页面事实。纯函数式（只读文件系统），供本文件与 `page-inventory.mjs` 共用。
 */
export function collect() {
  const pageRoutes = readPageRoutes();
  const routeByPath = new Map(pageRoutes.map((r) => [r.path, r]));

  // ① 路由壳
  const shellFiles = walk(join(APP_DIR, 'app'), '.tsx').filter(
    (p) => !NON_PAGE_SHELLS.has(p.split(sep).pop()),
  );
  const shells = shellFiles.map((abs) => {
    const relApp = relative(join(APP_DIR, 'app'), abs).split(sep).join('/');
    const url = shellToUrl(relApp);
    const src = readFileSync(abs, 'utf8');
    const target = parseShellTarget(src);
    return {
      file: rel(abs),
      fileUnderApp: relApp,
      url,
      routeKey: routeByPath.get(url)?.key ?? null,
      exportName: target?.exportName ?? null,
      modulePath: target?.modulePath ?? null,
      page: target ? resolveAlias(target.modulePath) : null,
      // 路由壳只允许一行导出，带逻辑就是违规（AGENTS §3.1）
      shellIsPureReexport: Boolean(target) && src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '').trim().split('\n').filter((l) => l.trim() && !l.trim().startsWith('export')).length === 0,
    };
  });

  // ② 页面实现
  const pageFiles = walk(join(APP_DIR, 'features'), 'Page.tsx');
  const pages = pageFiles.map((abs) => {
    const src = readFileSync(abs, 'utf8');
    const file = rel(abs);
    const shell = shells.find((s) => s.page === file) ?? null;
    const isDevProbe = /(Probe)Page\.tsx$/.test(file);
    return {
      file,
      name: file.split('/').pop().replace(/\.tsx$/, ''),
      domain: file.split('/')[2] ?? null,
      route: shell?.url ?? null,
      routeKey: shell?.routeKey ?? null,
      isDevProbe,
      ...inspectPage(abs, src),
    };
  });

  // ③ 设计稿
  const designDir = join(REPO, 'design', 'ui');
  const designHtml = existsSync(designDir)
    ? readdirSync(designDir)
        .filter((f) => f.endsWith('.html'))
        .sort()
    : [];
  const supplementScreens = designHtml.filter((f) => SUPPLEMENT_SCREEN_RE.test(f));
  const mainScreens = designHtml.filter((f) => !SUPPLEMENT_SCREEN_RE.test(f));

  const adminDir = join(REPO, 'design', 'admin');
  const adminHtml = existsSync(adminDir)
    ? readdirSync(adminDir)
        .filter((f) => f.endsWith('.html'))
        .sort()
    : [];

  // ④ 集合差集
  const routePaths = pageRoutes.map((r) => r.path);
  const shellUrls = shells.map((s) => s.url);
  const prodRoutes = pageRoutes.filter((r) => !DEV_ROUTE_KEYS.has(r.key));
  const devRoutes = pageRoutes.filter((r) => DEV_ROUTE_KEYS.has(r.key));

  return {
    pageRoutes,
    prodRoutes,
    devRoutes,
    shells,
    pages,
    design: { html: designHtml, mainScreens, supplementScreens, adminHtml },
    diffs: {
      routeWithoutShell: routePaths.filter((p) => !shellUrls.includes(p)),
      shellWithoutRoute: shellUrls.filter((p) => !routePaths.includes(p)),
      pageWithoutShell: pages.filter((p) => !p.route).map((p) => p.file),
      shellWithoutPage: shells.filter((s) => !s.page).map((s) => s.file),
      impureShells: shells.filter((s) => !s.shellIsPureReexport).map((s) => s.file),
      pagesWithoutTestId: pages.filter((p) => p.testIdCount === 0 && !p.isDevProbe).map((p) => p.file),
      pagesWithRawText: pages.filter((p) => p.rawTextCount > 0).map((p) => ({ file: p.file, count: p.rawTextCount })),
      literalNavigations: pages
        .filter((p) => p.navigations.some((n) => n.kind === 'literal'))
        .map((p) => ({
          file: p.file,
          targets: p.navigations.filter((n) => n.kind === 'literal').map((n) => n.value),
        })),
    },
    counts: {
      productPages: prodRoutes.length,
      pageRoutes: pageRoutes.length,
      devRoutes: devRoutes.length,
      shells: shells.length,
      pageImpls: pages.length,
      designMainScreens: mainScreens.length,
      designHtml: designHtml.length,
      designSupplementScreens: supplementScreens.length,
      adminScreens: adminHtml.length,
    },
  };
}

/** 源指纹：对全部输入文件做内容哈希，替代生成时间。 */
function computeDigest(c) {
  const h = createHash('sha256');
  const inputs = [ROUTES_FILE, ...c.shells.map((s) => join(REPO, s.file)), ...c.pages.map((p) => join(REPO, p.file))];
  for (const f of inputs.sort()) {
    h.update(rel(f));
    h.update(readFileSync(f));
  }
  for (const f of c.design.html) h.update('design/ui/' + f);
  for (const f of c.design.adminHtml) h.update('design/admin/' + f);
  return h.digest('hex').slice(0, 16);
}

/** 独立运行时执行：写 pages.json 并打印摘要。被 import 时不执行。 */
function main() {
  const c = collect();
  const sourceDigest = computeDigest(c);

  const payload = {
    _comment:
      '本文件由 scripts/facts/extract-pages.mjs 从 app/ + features/ + design/ 生成，禁止手改。口径见 docs/standards/说辞一致与真相源规范.md §四。与设计稿的映射关系另见 design/PAGE-MAP.json。',
    sourceDigest,
    sources: {
      routes: 'apps/mobile-app/shared/navigation/routes.ts',
      shells: 'apps/mobile-app/app/**/*.tsx',
      pages: 'apps/mobile-app/features/**/*Page.tsx',
      designUi: 'design/ui/*.html',
      designAdmin: 'design/admin/*.html',
    },
    counts: c.counts,
    countNote:
      '四个数都对但构成不同：产品页=路由常量−开发探测；路由常量含开发探测；页面实现含探测页；设计稿主屏=HTML−b/c 补充屏。引用时必须连口径一起引。',
    diffs: c.diffs,
    routes: c.pageRoutes.map((r) => ({
      key: r.key,
      path: r.path,
      isDev: DEV_ROUTE_KEYS.has(r.key),
      shell: c.shells.find((s) => s.url === r.path)?.file ?? null,
      page: c.shells.find((s) => s.url === r.path)?.page ?? null,
    })),
    pages: c.pages,
    shells: c.shells,
    design: {
      mainScreens: c.design.mainScreens,
      supplementScreens: c.design.supplementScreens,
      adminScreens: c.design.adminHtml,
    },
  };

  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2) + '\n', 'utf8');

  const k = c.counts;
  console.log('=== 页面事实（生成自 app/ + features/ + design/）===\n');
  console.log('产品页面数            ', k.productPages, '（路由常量 ' + k.pageRoutes + ' − 开发探测 ' + k.devRoutes + '）');
  console.log('路由常量总数          ', k.pageRoutes);
  console.log('路由壳文件数          ', k.shells);
  console.log('页面实现数            ', k.pageImpls);
  console.log('设计稿主屏            ', k.designMainScreens, '（HTML ' + k.designHtml + ' − 补充屏 ' + k.designSupplementScreens + '）');
  console.log('PC 后台设计稿          ', k.adminScreens);

  const d = c.diffs;
  const named = [
    ['路由常量有、路由壳没有', d.routeWithoutShell],
    ['路由壳有、路由常量没有', d.shellWithoutRoute],
    ['页面实现有、无路由壳', d.pageWithoutShell],
    ['路由壳指向的实现不存在', d.shellWithoutPage],
    ['路由壳不是纯再导出', d.impureShells],
    ['页面无 testID 锚点', d.pagesWithoutTestId],
    ['页面含裸 <Text>', d.pagesWithRawText],
    ['页面写死路由字符串', d.literalNavigations],
  ];
  console.log('\n[差集] 差异不是缺陷，处置见 docs/facts/DRIFT.md');
  for (const [label, list] of named) console.log(`  ${label.padEnd(24)} ${list.length}`);

  console.log(`\n源指纹 ${sourceDigest}`);
  console.log('已写 docs/facts/pages.json');
}

if (import.meta.filename === process.argv[1]) main();
