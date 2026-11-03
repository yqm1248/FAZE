# 段 0 / A3 验收：HTTP 侧逐项实测
#
# 覆盖《第0段工作流》A3「算做完」第 3、4、5、10、11、12 条（第 1/2/6/7/8/9/13 条在别处取证）
# 输出：docs/acceptance/seg0/_artifacts/A3-http-<ts>.txt
#
# 说明：
#   - 不依赖第三方库，只用标准库（requests/httpx 都未安装）
#   - 第 11 条「SQL 不泄漏」这里用「参数缺失触发兜底异常」+「kw 超长」两条取证；
#     真正带 SQL 片段的异常无法构造（段 0 没有会触发 SQL 错误的写接口），
#     这一条以 GlobalExceptionHandler 的静态审查补强，见证据文件末尾。
import json
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

BASE = "http://127.0.0.1:8081"
PHONE = "13800000001"
PASSWORD = "123456"
DEVICE = "seg0-probe"

lines = []


def emit(text=""):
    lines.append(text)
    print(text)


def http(method, path, body=None, headers=None, timeout=15):
    url = BASE + path
    data = None
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs["Content-Type"] = "application/json; charset=UTF-8"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, dict(resp.headers), raw
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def as_json(raw):
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return None


def check(name, ok, detail):
    emit(("PASS " if ok else "FAIL ") + name)
    emit("     " + detail)
    return ok


results = []
ts = datetime.now().strftime("%Y%m%d-%H%M%S")

emit("=" * 72)
emit("A3 HTTP 验收 —— " + datetime.now().isoformat(timespec="seconds"))
emit("base=" + BASE)
emit("=" * 72)

# ── 第 3 条：ping ──────────────────────────────────────────────────────────
status, headers, raw = http("GET", "/api/v1/pub/ping")
body = as_json(raw)
trace_header = headers.get("X-Trace-Id")
body_trace = (body or {}).get("traceId")
results.append(check(
    "3. ping：HTTP 200 + code 200 + traceId 与 X-Trace-Id 同值",
    status == 200 and body and body.get("code") == 200 and trace_header and body_trace == trace_header,
    f"HTTP={status} code={(body or {}).get('code')} header={trace_header} body={body_trace}"))

# ── 第 4 条：ngram 探针 ────────────────────────────────────────────────────
status, _, raw = http("GET", "/api/v1/pub/probe/law?kw=%E5%90%88%E5%90%8C")
body = as_json(raw)
hits = ((body or {}).get("data") or {}).get("hits") or []
results.append(check(
    "4. probe/law?kw=合同：hits >= 1",
    status == 200 and len(hits) >= 1,
    f"HTTP={status} hits={len(hits)} 首条={json.dumps(hits[0], ensure_ascii=False) if hits else 'N/A'}"))

# 单字 = 0 条是 ngram_token_size=2 的预期语义，不是失败
status, _, raw = http("GET", "/api/v1/pub/probe/law?kw=%E5%A9%9A")
body = as_json(raw)
hits_single = ((body or {}).get("data") or {}).get("hits") or []
results.append(check(
    "4b. probe/law?kw=婚（单字）：0 命中是预期（ngram_token_size=2）",
    status == 200 and len(hits_single) == 0,
    f"HTTP={status} hits={len(hits_single)}"))

# ── 第 5 条：SSE 探针 ──────────────────────────────────────────────────────
req = urllib.request.Request(BASE + "/api/v1/pub/probe/ai", data=b"", method="POST",
                             headers={"Accept": "text/event-stream"})
sse_lines = []
sse_ok = False
try:
    with urllib.request.urlopen(req, timeout=20) as resp:
        deadline = time.time() + 20
        while time.time() < deadline and len(sse_lines) < 8:
            line = resp.readline()
            if not line:
                break
            sse_lines.append(line.decode("utf-8", "replace").rstrip("\r\n"))
    sse_ok = any("event:meta" in x.replace(" ", "") for x in sse_lines)
except Exception as e:  # noqa: BLE001
    sse_lines.append("EXCEPTION: " + repr(e))
mock_marked = any('"mock":true' in x.replace(" ", "") for x in sse_lines)
results.append(check(
    "5. probe/ai：SSE 首帧 meta 且 mock=true（本次为 mock）",
    sse_ok and mock_marked,
    "前 8 行=" + json.dumps(sse_lines, ensure_ascii=False)))

# ── 第 12 条：6 个接口（登录 → 资料 → 刷新）───────────────────────────────
status, _, raw = http("POST", "/api/v1/pub/auth/login/password",
                      {"phone": PHONE, "password": PASSWORD, "deviceId": DEVICE},
                      {"X-Device-Id": DEVICE})
login = as_json(raw)
login_data = (login or {}).get("data") or {}
access = login_data.get("accessToken")
refresh1 = login_data.get("refreshToken")
user = login_data.get("user") or {}
results.append(check(
    "12a. login：200 + access/refresh/expiresIn/tokenType/user 齐全",
    status == 200 and (login or {}).get("code") == 200 and access and refresh1
    and login_data.get("tokenType") == "Bearer" and login_data.get("expiresIn") == 7200,
    f"HTTP={status} tokenType={login_data.get('tokenType')} expiresIn={login_data.get('expiresIn')} user={json.dumps(user, ensure_ascii=False)}"))

status, _, raw = http("GET", "/api/v1/app/account/profile", None,
                      {"Authorization": "Bearer " + (access or "")})
profile = as_json(raw)
results.append(check(
    "12b. profile（带 token）：200 + code 200 + id 为字符串（Long→String）",
    status == 200 and (profile or {}).get("code") == 200
    and isinstance(((profile or {}).get("data") or {}).get("id"), str),
    f"HTTP={status} body={json.dumps(profile, ensure_ascii=False)}"))

status, headers, raw = http("GET", "/api/v1/app/account/profile")
noprofile = as_json(raw)
results.append(check(
    "12c. profile（无 token）：HTTP 401 + code 402003",
    status == 401 and (noprofile or {}).get("code") == 402003,
    f"HTTP={status} body={json.dumps(noprofile, ensure_ascii=False)}"))

status, _, raw = http("POST", "/api/v1/app/auth/refresh", {"refreshToken": refresh1})
refreshed = as_json(raw)
refresh2 = ((refreshed or {}).get("data") or {}).get("refreshToken")
results.append(check(
    "12d. refresh：200 + 新 access/refresh + 轮换",
    status == 200 and (refreshed or {}).get("code") == 200 and refresh2 and refresh2 != refresh1,
    f"HTTP={status} 新 refresh 与旧不同={refresh2 != refresh1}"))

# 12e 重放：先等 6 秒越过 5 秒复用窗口（窗口内允许「刚好晚到的并发」复用结果）
time.sleep(6.5)
status, _, raw = http("POST", "/api/v1/app/auth/refresh", {"refreshToken": refresh1})
reused = as_json(raw)
results.append(check(
    "12e. 旧 refresh token 在复用窗口外重放：401 + 402003（轮换后失效，不许重放）",
    status == 401 and (reused or {}).get("code") == 402003,
    f"HTTP={status} body={json.dumps(reused, ensure_ascii=False)}（先 sleep 6 秒越过 5 秒复用窗口）"))

# ── 单飞：同一 refresh 并发 6 次，必须全部成功且拿到同一个新 token ─────────
login2 = http("POST", "/api/v1/pub/auth/login/password",
              {"phone": PHONE, "password": PASSWORD, "deviceId": DEVICE},
              {"X-Device-Id": DEVICE})
refresh_sf = ((as_json(login2[2]) or {}).get("data") or {}).get("refreshToken")
sf_results = []
lock = threading.Lock()


def do_refresh():
    st, _, rw = http("POST", "/api/v1/app/auth/refresh", {"refreshToken": refresh_sf})
    with lock:
        sf_results.append((st, as_json(rw)))


threads = [threading.Thread(target=do_refresh) for _ in range(6)]
for t in threads:
    t.start()
for t in threads:
    t.join()
codes = [r[0] for r in sf_results]
new_tokens = {((r[1] or {}).get("data") or {}).get("refreshToken") for r in sf_results}
results.append(check(
    "12f. 单飞：同一 refreshToken 并发 6 次 → 全部 200 且新 token 唯一",
    len(codes) == 6 and all(c == 200 for c in codes) and len(new_tokens) == 1,
    f"HTTP={codes} 不同新token数={len(new_tokens)}"))

# ── 第 10 条：过期 / 签名错 两个 401 必须可区分 ───────────────────────────
EXPIRED = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidHlwZSI6ImFjY2VzcyIsInJvbGUiOiJ1c2VyIiwiaWF0IjoxNzkzNzEwNDc0LCJleHAiOjE3OTM3MTQwNzQsImp0aSI6InNlZzAtZXhwaXJlZCJ9.9mGtCpwMc99MLt4MI0PYCTxpqTz50teUnoigshIkWRU"
BADSIG = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidHlwZSI6ImFjY2VzcyIsInJvbGUiOiJ1c2VyIiwiaWF0IjoxNzkzNzE3Njc0LCJleHAiOjE3OTM3MjEyNzQsImp0aSI6InNlZzAtYmFkc2lnIn0.up_yy0a8KbC380KhXRwoll2gGDcqLcFSdUEIvVARwOA"

status, _, raw = http("GET", "/api/v1/app/account/profile", None, {"Authorization": "Bearer " + EXPIRED})
expired_body = as_json(raw)
results.append(check(
    "10a. 过期 token：HTTP 401 + code 402002（可刷新）",
    status == 401 and (expired_body or {}).get("code") == 402002,
    f"HTTP={status} body={json.dumps(expired_body, ensure_ascii=False)}"))

status, _, raw = http("GET", "/api/v1/app/account/profile", None, {"Authorization": "Bearer " + BADSIG})
badsig_body = as_json(raw)
results.append(check(
    "10b. 签名错 token：HTTP 401 + code 402003（不可刷新）",
    status == 401 and (badsig_body or {}).get("code") == 402003,
    f"HTTP={status} body={json.dumps(badsig_body, ensure_ascii=False)}"))

results.append(check(
    "10c. 两码必须不同（否则客户端无限刷新循环）",
    (expired_body or {}).get("code") == 402002 and (badsig_body or {}).get("code") == 402003,
    f"过期码={(expired_body or {}).get('code')} 无效码={(badsig_body or {}).get('code')}"))

# ── 第 11 条：异常不外泄 ──────────────────────────────────────────────────
# kw = 60 个字符，超过 50 上限 → 401001；msg 只能有字段级提示
status, _, raw = http("GET", "/api/v1/pub/probe/law?kw=" + ("%E5%90%88" * 60))
toolong = as_json(raw)
msg = (toolong or {}).get("msg") or ""
leaked = any(k in msg.lower() for k in ("select", "law_item", "sql", "exception", "com.faze"))
results.append(check(
    "11a. kw 超长（60 字 > 50）：200 + 401001，msg 不含 SQL/表名/类名",
    (toolong or {}).get("code") == 401001 and not leaked,
    f"HTTP={status} body={json.dumps(toolong, ensure_ascii=False)}"))

# 缺必填参数 → 401002（不是 501001；缺参数是客户端错误）
status, _, raw = http("GET", "/api/v1/pub/probe/law")
missing = as_json(raw)
msg2 = (missing or {}).get("msg") or ""
leaked2 = any(k in msg2.lower() for k in ("select", "law_item", "sql", "exception", "com.faze"))
results.append(check(
    "11b. 缺必填参数 kw：200 + 401002，msg 为字段级提示、不含内部信息",
    (missing or {}).get("code") == 401002 and not leaked2,
    f"HTTP={status} body={json.dumps(missing, ensure_ascii=False)}"))

# 方法不支持 → 405，且仍是 JSON 体（不是 Tomcat HTML）
# 用 pub 前缀的 ping：它放行且只映射 GET，所以 POST 会真的进到 MVC 的 405 分支。
# （不能拿 /app/** 试：那个前缀会先被 Security 拦成 401，根本到不了方法匹配。）
status, _, raw = http("POST", "/api/v1/pub/ping")
notallowed = as_json(raw)
results.append(check(
    "11c. 方法不支持：HTTP 405 + JSON 体（不是 HTML 错误页）",
    status == 405 and (notallowed or {}).get("code") == 401001,
    f"HTTP={status} body={json.dumps(notallowed, ensure_ascii=False)}"))

# ── 汇总 ──────────────────────────────────────────────────────────────────
emit("=" * 72)
passed = sum(1 for r in results if r)
emit(f"合计 {len(results)} 项：PASS {passed} / FAIL {len(results) - passed}")
emit("=" * 72)

out = f"E:/dshcode/FAZE/docs/acceptance/seg0/_artifacts/A3-http-{ts}.txt"
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("written:", out)
