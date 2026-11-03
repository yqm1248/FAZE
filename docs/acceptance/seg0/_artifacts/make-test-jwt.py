# 生成测试用 JWT：一个已过期（验 402002），一个签名错（验 402003）
# 只用标准库，不引依赖；密钥取 application-dev.yml 里的 dev 默认值
import base64
import hashlib
import hmac
import json
import time

SECRET = b"faze-local-dev-only-secret-key-0123456789abcdef"


def b64url(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def make_jwt(payload: dict, tamper: bool = False) -> str:
    header = b64url(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    body = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = header + b"." + body
    signature = b64url(hmac.new(SECRET, signing_input, hashlib.sha256).digest())
    if tamper:
        # 改最后一位：签名与内容不匹配 → 402003
        last = "A" if signature[-1:] != b"A" else "B"
        signature = signature[:-1] + last.encode()
    return (signing_input + b"." + signature).decode()


now = int(time.time())
expired = make_jwt({
    "sub": "1",
    "type": "access",
    "role": "user",
    "iat": now - 7200,
    "exp": now - 3600,
    "jti": "seg0-expired",
})
bad_sig = make_jwt({
    "sub": "1",
    "type": "access",
    "role": "user",
    "iat": now,
    "exp": now + 3600,
    "jti": "seg0-badsig",
}, tamper=True)

print("EXPIRED=" + expired)
print("BADSIG=" + bad_sig)
