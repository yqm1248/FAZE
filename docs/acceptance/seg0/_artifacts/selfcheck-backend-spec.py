# 对照《后端代码规范 v1.0》自查 faze-api 代码（返工后复验）
# 只输出可核对的数字与文件清单
import io
import os
import re

ROOT = r"E:\dshcode\FAZE\services\faze-api\src\main\java\com\faze"
RES = r"E:\dshcode\FAZE\services\faze-api\src\main\resources"

files = []
for dirpath, _, names in os.walk(ROOT):
    for n in sorted(names):
        if n.endswith(".java"):
            files.append(os.path.join(dirpath, n))

print(f"Java 文件总数: {len(files)}")

# ── 1. 包结构（§2.1 / §3.5 / §3.4）───────────────────────────────────────
print("\n=== 包结构 ===")
pkgs = {}
for p in files:
    rel = os.path.relpath(p, ROOT).replace("\\", "/")
    pkgs.setdefault(os.path.dirname(rel), []).append(os.path.basename(rel))
for pkg in sorted(pkgs):
    print(f"  {pkg or '(root)'}/ : {len(pkgs[pkg])}")

VIOLATIONS = []
if os.path.exists(os.path.join(ROOT, "common", "ErrorCode.java")):
    VIOLATIONS.append("common/ 根仍有 ErrorCode（应为 common/exception/）")
if os.path.exists(os.path.join(ROOT, "common", "GlobalExceptionHandler.java")):
    VIOLATIONS.append("common/ 根仍有 GlobalExceptionHandler")
if os.path.exists(os.path.join(ROOT, "modules", "account", "dto")):
    VIOLATIONS.append("modules/account/dto 仍存在（应为 bo/ + vo/）")
if not os.path.exists(os.path.join(ROOT, "modules", "account", "README.md")):
    VIOLATIONS.append("modules/account/README.md 缺失（§2.6）")
if not os.path.exists(os.path.join(ROOT, "filter", "TraceIdFilter.java")):
    VIOLATIONS.append("filter/TraceIdFilter 缺失（§2.1）")
xml_root = os.path.join(RES, "mapper", "LawItemMapper.xml")
if os.path.exists(xml_root):
    VIOLATIONS.append("Mapper XML 仍在 mapper/ 根（应为 mapper/<域>/）")
print("包结构违规:", VIOLATIONS if VIOLATIONS else "无")

# ── 2. 类 Javadoc（§4.1：<=3 行；record 的 @param 属契约，不计入）─────────
print("\n=== 类 Javadoc（§4.1）===")
BLOCK = re.compile(r"/\*\*.*?\*/", re.S)
over = []
records = []
for p in files:
    text = io.open(p, encoding="utf-8").read()
    # 只在「顶层」声明 record 时豁免（AuthService / RefreshTokenStore 有嵌套 private record，不算）
    if re.search(r"(?m)^(public\s+)?record\s+\w+\s*\(", text):
        records.append(os.path.relpath(p, ROOT))
        continue
    m = BLOCK.search(text)
    if not m:
        over.append((os.path.relpath(p, ROOT), "无类 javadoc"))
        continue
    n = m.group(0).count("\n") + 1
    if n > 3:
        over.append((os.path.relpath(p, ROOT), f"{n} 行"))
print(f"  超三行: {len(over)}/{len(files) - len(records)}（record 豁免 {len(records)} 个: {records}）")
for f, d in over:
    print(f"    {d:>8}  {f}")

# ── 3. 文件头块注释（AGENTS §4.1）────────────────────────────────────────
print("\n=== 文件头块注释 ===")
no_header = []
for p in files:
    lines = io.open(p, encoding="utf-8").read().splitlines()
    if not lines or not lines[0].strip().startswith("/*"):
        no_header.append(os.path.relpath(p, ROOT))
print(f"  缺失: {len(no_header)} {no_header if no_header else ''}")

# ── 4. public 方法注释（§4.2）────────────────────────────────────────────
print("\n=== public 方法 javadoc（§4.2）===")
METHOD = re.compile(r"^\s{4}public\s+(?!class\b|interface\b|enum\b|record\b)[\w<>\[\], .?]+\s+\w+\s*\(")
total = documented = 0
missing = []
for p in files:
    lines = io.open(p, encoding="utf-8").read().splitlines()
    for i, line in enumerate(lines):
        if METHOD.match(line):
            total += 1
            j = i - 1
            while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip() == ""):
                j -= 1
            if j >= 0 and lines[j].strip().endswith("*/"):
                documented += 1
            else:
                missing.append(f"{os.path.relpath(p, ROOT)}:{i + 1}")
print(f"  {documented}/{total} 有 javadoc；缺失: {missing if missing else '无'}")

# ── 5. 任务分段（§4.4）───────────────────────────────────────────────────
print("\n=== `// 任务 n：` 分段（§4.4）===")
task_files = [os.path.relpath(p, ROOT) for p in files
              if re.search(r"//\s*任务\s*\d+\s*[:：]", io.open(p, encoding="utf-8").read())]
print(f"  {len(task_files)} 个文件: {task_files}")

# ── 6. ErrorCode 静态查找（§12.1）────────────────────────────────────────
ec = io.open(os.path.join(ROOT, "common", "exception", "ErrorCode.java"), encoding="utf-8").read()
print("\n=== ErrorCode（§12.1）===")
print("  of():", bool(re.search(r"public\s+static\s+ErrorCode\s+of\s*\(", ec)), "| BY_CODE:", "BY_CODE" in ec)

# ── 7. 配置属性（§12.3）─────────────────────────────────────────────────
print("\n=== @ConfigurationProperties（§12.3）===")
props = [os.path.relpath(p, ROOT) for p in files
         if "@ConfigurationProperties" in io.open(p, encoding="utf-8").read()]
value_hits = sum(len(re.findall(r"@Value\(", io.open(p, encoding="utf-8").read())) for p in files)
print(f"  属性类 {len(props)} 个: {props}")
print(f"  剩余 @Value 注入点: {value_hits}")
for p in files:
    for i, line in enumerate(io.open(p, encoding="utf-8").read().splitlines(), 1):
        if "@Value(" in line:
            print(f"    {os.path.relpath(p, ROOT)}:{i}  {line.strip()}")

# ── 8. logback 三 appender（§10.5）──────────────────────────────────────
print("\n=== logback（§10.5）===")
lg = io.open(os.path.join(RES, "logback-spring.xml"), encoding="utf-8").read()
for name in ["CONSOLE", "FILE", "ERROR", "AsyncAppender", "discardingThreshold"]:
    print(f"  {name}: {'有' if name in lg else '缺'}")

# ── 9. Controller 分层禁令（§2.2/§3.2）─────────────────────────────────
print("\n=== Controller 分层禁令 ===")
bad = []
for p in files:
    rel = os.path.relpath(p, ROOT)
    if "controller" not in rel.lower():
        continue
    lines = io.open(p, encoding="utf-8").read().splitlines()
    # 只看代码行：排除注释与空行（注释里写「不注入 Mapper」不算违规）
    code = "\n".join(
        ln for ln in lines
        if ln.strip() and not ln.strip().startswith(("*", "//", "/*"))
    )
    if "Mapper" in code:
        bad.append(f"{rel}: 代码行出现 Mapper")
    if "@Transactional" in code:
        bad.append(f"{rel}: 有 @Transactional")
print("  违规:", bad if bad else "无")
