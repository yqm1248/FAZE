# A3 验收：数据库侧（第 6/7/8/13 条）
#
# 断言：
#   6. SHOW TABLES FROM faze → 恰好 app_user / law_item / flyway_schema_history（业务表 2 张）
#   7. SELECT COUNT(*) FROM law_item → 5
#   8. SHOW CREATE TABLE law_item 含 WITH PARSER ngram；SHOW CREATE DATABASE faze 含 utf8mb4_0900_ai_ci
#   13. SHOW VARIABLES LIKE 'ngram_token_size' → 2
import re
import subprocess
from datetime import datetime

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
USER = "faze_app"
PASSWORD = "123456"
DB = "faze"

lines = []


def emit(text=""):
    lines.append(text)
    print(text)


def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u" + USER, "-p" + PASSWORD, "--default-character-set=utf8mb4", DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        return None, result.stderr
    return result.stdout, None


def check(name, ok, detail):
    emit(("PASS " if ok else "FAIL ") + name)
    emit("     " + str(detail).replace("\n", "\n     "))
    return ok


results = []
emit("=" * 72)
emit("A3 数据库验收 —— " + datetime.now().isoformat(timespec="seconds"))
emit("=" * 72)

# ── 6. 表清单 ──────────────────────────────────────────────────────────────
out, err = run_sql("SHOW TABLES;")
tables = [t.strip() for t in (out or "").splitlines()[1:] if t.strip()]
expected = sorted(["app_user", "law_item", "flyway_schema_history"])
business = [t for t in tables if t != "flyway_schema_history"]
results.append(check(
    "6. SHOW TABLES：恰好 2 张业务表 + 1 张 Flyway 元数据表",
    sorted(tables) == expected,
    f"实际={tables} 业务表数={len(business)}"))

# ── 7. 法条条数 ────────────────────────────────────────────────────────────
out, err = run_sql("SELECT COUNT(*) AS c FROM law_item;")
count = None
for line in (out or "").splitlines():
    if line.strip().isdigit():
        count = int(line.strip())
results.append(check("7. COUNT(law_item) = 5", count == 5, f"实际={count}"))

# ── 8. ngram 索引与库排序规则 ──────────────────────────────────────────────
out_table, _ = run_sql("SHOW CREATE TABLE law_item;")
# 注意：MySQL 8.0 输出的是 /*!50100 WITH PARSER `ngram` */，parser 名**带反引号**，
# 按 "WITH PARSER ngram" 做字面匹配会假失败（2026-11-03 实测踩过）。
has_ngram = bool(re.search(r"WITH\s+PARSER\s+`?ngram`?", out_table or "", re.IGNORECASE))
has_collate = "utf8mb4_0900_ai_ci" in (out_table or "")
out_db, _ = run_sql("SHOW CREATE DATABASE faze;")
db_collate = "utf8mb4_0900_ai_ci" in (out_db or "")
results.append(check(
    "8a. SHOW CREATE TABLE law_item 含 WITH PARSER ngram + utf8mb4_0900_ai_ci",
    has_ngram and has_collate, f"ngram={has_ngram} collate={has_collate}"))
results.append(check(
    "8b. SHOW CREATE DATABASE faze 含 utf8mb4_0900_ai_ci",
    db_collate, (out_db or "").strip().replace("\n", " ")))

# ── 13. ngram_token_size ───────────────────────────────────────────────────
out_var, _ = run_sql("SHOW VARIABLES LIKE 'ngram_token_size';")
token_size_ok = "ngram_token_size\t2" in (out_var or "") or "ngram_token_size 2" in (out_var or "")
results.append(check(
    "13. ngram_token_size = 2",
    token_size_ok, (out_var or "").strip().replace("\n", " ")))

# ── 附：ngram 真命中（双字命中、单字不命中）────────────────────────────────
out_match, _ = run_sql(
    "SELECT COUNT(*) AS c FROM law_item WHERE MATCH(content) AGAINST ('借款');")
hit_double = None
for line in (out_match or "").splitlines():
    if line.strip().isdigit():
        hit_double = int(line.strip())
out_single, _ = run_sql(
    "SELECT COUNT(*) AS c FROM law_item WHERE MATCH(content) AGAINST ('婚');")
hit_single = None
for line in (out_single or "").splitlines():
    if line.strip().isdigit():
        hit_single = int(line.strip())
results.append(check(
    "附. ngram 语义：'借款' 命中 >=1、'婚'（单字）命中 0",
    (hit_double or 0) >= 1 and hit_single == 0,
    f"'借款'={hit_double} '婚'={hit_single}"))

emit("=" * 72)
passed = sum(1 for r in results if r)
emit(f"合计 {len(results)} 项：PASS {passed} / FAIL {len(results) - passed}")
emit("=" * 72)

ts = datetime.now().strftime("%Y%m%d-%H%M%S")
out_path = f"E:/dshcode/FAZE/docs/acceptance/seg0/_artifacts/A3-db-{ts}.txt"
with open(out_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("written:", out_path)
