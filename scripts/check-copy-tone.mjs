/**
 * 用户可见文案的「内部口吻」检查（回归清单 R-11）。
 *
 * 职责：
 *   扫四种语言的 i18n 资源（`apps/mobile-app/shared/i18n/resources/<lang>/*.json`），
 *   把用户会看到的文案里出现的**内部话术**检出来，例如「演示」「目标用户」「示例数据」
 *   「TODO」——这些词是写给开发者/验收者看的，不该出现在界面上。
 *
 * 调用方：
 *   - 人工：`npm run check:copy-tone`
 *   - CI：`mobile` job
 *
 * 数据来源：i18n JSON 的 value（key 不查——key 本来就可以是英文/内部命名）。
 *
 * 为什么值得单独一道检查：
 *   这一条是 G9 落地时**实测抓到 2 处真缺陷**的规则，不是理论风险：
 *   ① `trade.payPassword.verifyDesc` 原文「**用来演示**锁定与剩余次数」——用户看不懂什么叫「演示」；
 *   ② `profile.settings.fontSizeNote` 原文「**目标用户**里有不少中老年人」——「目标用户」是产品内部词。
 *   两处都已改成用户口吻。
 *
 * 已知盲区（**不要以为这道检查能兜住全部语言**）：
 *   关键词表只有中文。bo / ug-Arab-CN / kk-Arab-CN 三种语言的「内部口吻」直译
 *   （例如维吾尔文里「目标用户」直译成 نىشان ئىشلەتكۈچىلەر）用中文关键词**检不出来**。
 *   那三种语言的措辞质量依赖**人工核对**，属段八「四语言译文人工核对」的挂账项。
 *
 * 豁免：
 *   `scripts/copy-tone-known.json` 里登记「为什么这条先不改」，每条必须带 `closeWhen`。
 *   没有关闭条件的豁免等于永久放弃这条检查。
 *
 * 退出码：0 无新增命中 / 1 有命中且不在台账 / 2 资源目录缺失或解析失败
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..');
const RES_ROOT = join(REPO, 'apps', 'mobile-app', 'shared', 'i18n', 'resources');
const KNOWN_FILE = join(REPO, 'scripts', 'copy-tone-known.json');

/** 内部口吻关键词。只放高置信度的词，宁可漏报也不要制造噪音。 */
const KEYWORDS = [
  '演示',
  '目标用户',
  '内部定位',
  '内部使用',
  '内部测试',
  '示例数据',
  '占位符',
  'TODO',
  'FIXME',
  'lorem ipsum',
  'dummy',
  '待补充',
];

if (!existsSync(RES_ROOT)) {
  console.error(`[FAIL] 找不到 i18n 资源目录：${RES_ROOT}`);
  process.exit(2);
}

const known = existsSync(KNOWN_FILE) ? JSON.parse(readFileSync(KNOWN_FILE, 'utf8')) : { allowed: [] };
const allowed = new Set((known.allowed ?? []).map((x) => x.key));

/** 收集一条语言目录下所有 json 的 {namespace.key → 文案}。 */
function collectLang(lang) {
  const dir = join(RES_ROOT, lang);
  const out = [];
  for (const file of readdirSync(dir)) {
    if (!file.endsWith('.json')) continue;
    const ns = file.replace(/\.json$/, '');
    const walk = (obj, prefix) => {
      for (const [k, v] of Object.entries(obj)) {
        if (v && typeof v === 'object') {
          walk(v, [...prefix, k]);
          continue;
        }
        if (typeof v !== 'string') continue;
        out.push({ lang, key: `${ns}.${[...prefix, k].join('.')}`, text: v });
      }
    };
    walk(JSON.parse(readFileSync(join(dir, file), 'utf8')), []);
  }
  return out;
}

const langs = readdirSync(RES_ROOT).filter((d) => statSync(join(RES_ROOT, d)).isDirectory());
if (langs.length === 0) {
  console.error('[FAIL] 资源目录下没有任何语言子目录');
  process.exit(2);
}

const hits = [];
for (const lang of langs) {
  for (const entry of collectLang(lang)) {
    for (const kw of KEYWORDS) {
      if (!entry.text.includes(kw)) continue;
      hits.push({ ...entry, keyword: kw });
      break;
    }
  }
}

console.log(`=== 文案内部口吻检查（R-11）===`);
console.log(`语言目录 ${langs.length} 个：${langs.join(', ')}`);
console.log(`关键词 ${KEYWORDS.length} 个：${KEYWORDS.join(' / ')}`);
console.log(`命中 ${hits.length} 条，台账豁免 ${allowed.size} 条`);

const unallowed = hits.filter((h) => !allowed.has(h.key));
for (const h of hits) {
  const mark = allowed.has(h.key) ? '[台账]' : '[新增]';
  console.log(`  ${mark} [${h.lang}] ${h.key} ←「${h.keyword}」`);
  console.log(`         ${h.text.slice(0, 80)}`);
}

if (unallowed.length > 0) {
  console.error(`\n[FAIL] ${unallowed.length} 条用户可见文案含内部口吻，且不在台账内。`);
  console.error('处理：把文案改成用户口吻（四语言同义），或登记到 scripts/copy-tone-known.json 并写 closeWhen。');
  process.exit(1);
}

console.log('\n[OK] 用户可见文案没有未登记的「内部口吻」。');
