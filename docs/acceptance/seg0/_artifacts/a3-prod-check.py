# A3 验收：生产 profile 下三个探针必须 404（第 9 条）
#
# 为什么必须验：`probe/ai` 无鉴权且会真实消耗豆包配额，留在生产是典型线上事故源。
# 《接口规范》§3.1 裁决：三个探针接口全部 @Profile("!prod")，生产下不存在。
#
# 同时反向确认：prod 下**登录仍可用**（它不该被 @Profile 误伤），
# 且 404 必须是 JSON 体（code=401004），不是 Tomcat 的 HTML 错误页。
import json
import urllib.error
import urllib.request
from datetime import datetime

BASE = "http://127.0.0.1:8082"
lines = []


def emit(text=""):
    lines.append(text)
    print(text)


def http(method, path, body=None, headers=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else (b"" if method == "POST" else None)
    hdrs = {"Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    if body is not None:
        hdrs["Content-Type"] = "application/json; charset=UTF-8"
    req = urllib.request.Request(BASE + path, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def check(name, ok, detail):
    emit(("PASS " if ok else "FAIL ") + name)
    emit("     " + str(detail))
    return ok


results = []
emit("=" * 72)
emit("A3 生产 profile 验收（8082）—— " + datetime.now().isoformat(timespec="seconds"))
emit("=" * 72)

# ── 三个探针必须 404，且是 JSON ────────────────────────────────────────────
for label, method, path in [
    ("ping（@Profile 排除）", "GET", "/api/v1/pub/ping"),
    ("probe/law（@Profile 排除）", "GET", "/api/v1/pub/probe/law?kw=%E5%90%88%E5%90%8C"),
    ("probe/ai（@Profile 排除）", "POST", "/api/v1/pub/probe/ai"),
]:
    status, headers, raw = http(method, path)
    body = None
    try:
        body = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        pass
    ctype = headers.get("Content-Type", "")
    results.append(check(
        f"9. {label} → 404 + JSON 体",
        status == 404 and body is not None and body.get("code") == 401004 and "json" in ctype,
        f"HTTP={status} Content-Type={ctype} body={json.dumps(body, ensure_ascii=False) if body is not None else raw[:120]!r}"))

# ── 反向：登录接口在 prod 下必须还活着（不许被 @Profile 误伤）─────────────
status, _, raw = http("POST", "/api/v1/pub/auth/login/password",
                      {"phone": "13800000001", "password": "123456", "deviceId": "seg0-prod"},
                      {"X-Device-Id": "seg0-prod"})
login = json.loads(raw.decode("utf-8")) if raw else {}
results.append(check(
    "9b. 反向确认：prod 下 login 仍可用（200 + code 200）",
    status == 200 and login.get("code") == 200,
    f"HTTP={status} code={login.get('code')}"))

status, _, raw = http("GET", "/api/v1/app/account/profile")
profile = json.loads(raw.decode("utf-8")) if raw else {}
results.append(check(
    "9c. prod 下受保护资源仍回 401 + 402003",
    status == 401 and profile.get("code") == 402003,
    f"HTTP={status} code={profile.get('code')}"))

emit("=" * 72)
passed = sum(1 for r in results if r)
emit(f"合计 {len(results)} 项：PASS {passed} / FAIL {len(results) - passed}")
emit("=" * 72)

ts = datetime.now().strftime("%Y%m%d-%H%M%S")
out = f"E:/dshcode/FAZE/docs/acceptance/seg0/_artifacts/A3-prod404-{ts}.txt"
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("written:", out)
