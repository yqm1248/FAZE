#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
职责（第 7 段 G3 / 7-A8 的唯一合法降级证据）：
    本机 8088 端口的「假钉钉机器人」接收端。任意 POST 进来就把**完整 headers +
    完整 body 原文**追加写进日志，并返回钉钉格式的 200 JSON。
    用途：没有钉钉群时，证明「告警系统真的自动发出了 HTTP POST」——
    注意：手 curl 一次**不算**证据，必须是 AlertDispatcher 轮询 Prometheus 后自动发出。

调用方：
    .\\scripts\\alert\\mock-receiver.py --port 8088
    （由运维/Lead 在告警演练期间起在后台；停止用 Ctrl+C）

数据来源：HTTP 请求本身。
    日志默认写 <仓库根>\\logs\\alert-mock.log（已 gitignore）。

注意事项：
    1. 返回体必须是钉钉的形状 {"errcode":0,"errmsg":"ok"}，否则有些推送器会判失败重试。
    2. headers 与 body 是**原文落盘**：body 按 UTF-8 解码，解不开就按 latin-1 兜底并标注；
       同时打印字节数，便于核对中文没被转义坏。
    3. 只监听 127.0.0.1，不对外；不校验来源；仅用于开发/验收。
    4. 不依赖任何第三方库（标准库 http.server），Python 3.8+ 可跑（本机 3.14.5）。
    5. 日志无轮转（一次性演练用）；长期跑要接 logrotate 或改用 logging.handlers。
"""

import argparse
import json
import os
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 仓库根：本文件在 <repo>/scripts/alert/mock-receiver.py
_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(os.path.dirname(_HERE))
_DEFAULT_LOG = os.path.join(_REPO_ROOT, "logs", "alert-mock.log")

_LOCK = threading.Lock()
_LOG_PATH = _DEFAULT_LOG
_QUIET = False
_COUNTER = {"total": 0}


def _now() -> str:
    """本地时间 ISO 串（带时区偏移），日志行首用。"""
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _append_log(text: str) -> None:
    """线程安全地把一段文本追加到日志文件，并同步打印到 stdout。

    Windows 控制台默认 GBK（cp936）：钉钉 markdown 里的 ✅ 等字符会让
    sys.stdout.write 抛 UnicodeEncodeError，进而把请求处理线程打断（客户端收到
    连接重置、推送器记「发送失败」）。所以 stdout/stderr 在 main() 里已
    reconfigure 成 utf-8 + errors=replace，这里再兜一层 try/except，保证
    **日志文件先写成功**，控制台打印失败不影响 200 响应。
    """
    with _LOCK:
        os.makedirs(os.path.dirname(_LOG_PATH), exist_ok=True)
        with open(_LOG_PATH, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
            if not text.endswith("\n"):
                fh.write("\n")
        if not _QUIET:
            try:
                sys.stdout.write(text if text.endswith("\n") else text + "\n")
                sys.stdout.flush()
            except Exception:
                # 控制台编码问题不能影响接收端可用性（日志文件已落盘）
                pass


def _decode_body(raw: bytes):
    """把 body 解成文本：优先 UTF-8，失败用 latin-1 兜底并标记。"""
    try:
        return raw.decode("utf-8"), "utf-8"
    except UnicodeDecodeError:
        return raw.decode("latin-1"), "latin-1(兜底，说明不是合法 UTF-8)"


def _format_request(handler: BaseHTTPRequestHandler, method: str, raw: bytes) -> str:
    """把一次请求格式化成可读的证据块（headers 原文 + body 原文）。"""
    body_text, enc = _decode_body(raw)
    lines = []
    lines.append("=" * 100)
    lines.append(
        "[%s] %s %s  from %s  body_bytes=%d  body_encoding=%s"
        % (
            _now(),
            method,
            handler.path,
            handler.client_address[0] if handler.client_address else "?",
            len(raw),
            enc,
        )
    )
    lines.append("--- headers ---")
    for key, value in handler.headers.items():
        lines.append("%s: %s" % (key, value))
    lines.append("--- body (原文) ---")
    lines.append(body_text if body_text else "(空 body)")
    lines.append("--- body hex 前 64 字节 ---")
    lines.append(raw[:64].hex() if raw else "(空)")
    lines.append("=" * 100)
    return "\n".join(lines)


class MockAlertHandler(BaseHTTPRequestHandler):
    """接收端 handler：POST 落日志 + 回钉钉格式 200；GET 提供 /health 探活。"""

    server_version = "FazeMockAlert/1.0"
    protocol_version = "HTTP/1.1"  # 必须带 Content-Length，否则客户端会挂住

    def _read_body(self) -> bytes:
        """按 Content-Length 读完整 body（告警 JSON 很小，一次读完即可）。"""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _respond_json(self, status: int, payload: dict) -> None:
        """回一个 JSON 响应（钉钉 webhook 的期望形状）。"""
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 - http.server 约定命名
        """任意路径的 POST：落日志，然后回 200 钉钉形状。"""
        raw = self._read_body()
        with _LOCK:
            _COUNTER["total"] += 1
            seq = _COUNTER["total"]
        _append_log(_format_request(self, "POST", raw))
        _append_log("[received #%d] 已记录，返回 200" % seq)
        self._respond_json(200, {"errcode": 0, "errmsg": "ok"})

    def do_PUT(self) -> None:  # noqa: N802
        """PUT 也按 POST 处理：有些推送器/测试工具用 PUT。"""
        raw = self._read_body()
        with _LOCK:
            _COUNTER["total"] += 1
            seq = _COUNTER["total"]
        _append_log(_format_request(self, "PUT", raw))
        _append_log("[received #%d] 已记录，返回 200" % seq)
        self._respond_json(200, {"errcode": 0, "errmsg": "ok"})

    def do_GET(self) -> None:  # noqa: N802
        """GET /health 探活；其他 GET 返回用法。"""
        if self.path.startswith("/health"):
            self._respond_json(200, {"status": "ok", "received": _COUNTER["total"]})
            return
        self._respond_json(
            200,
            {
                "service": "faze-mock-alert-receiver",
                "usage": "POST 任意路径；完整 headers+body 落 logs/alert-mock.log",
                "log": _LOG_PATH,
                "received": _COUNTER["total"],
            },
        )

    def log_message(self, fmt: str, *args) -> None:
        """屏蔽 http.server 默认的 stderr 访问日志（我们自己落盘）。"""
        if not _QUIET:
            sys.stderr.write("[mock-receiver http] %s - %s\n" % (self.client_address[0], fmt % args))


def main() -> int:
    """解析参数并启动 HTTP 服务（阻塞）。"""
    global _LOG_PATH, _QUIET
    parser = argparse.ArgumentParser(description="FAZE 假钉钉告警接收端（7-A8 降级证据用）")
    parser.add_argument("--port", type=int, default=8088, help="监听端口，默认 8088")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址，默认 127.0.0.1（只本机）")
    parser.add_argument("--log", default=_DEFAULT_LOG, help="日志文件，默认 <仓库根>\\logs\\alert-mock.log")
    parser.add_argument("--quiet", action="store_true", help="不在 stdout 打印请求内容（仍写日志）")
    args = parser.parse_args()

    _LOG_PATH = os.path.abspath(args.log)
    _QUIET = bool(args.quiet)
    os.makedirs(os.path.dirname(_LOG_PATH), exist_ok=True)

    server = ThreadingHTTPServer((args.host, args.port), MockAlertHandler)
    _append_log(
        "[%s] mock-receiver 启动：http://%s:%d  日志=%s"
        % (_now(), args.host, args.port, _LOG_PATH)
    )
    print("[mock-receiver] listening on http://%s:%d" % (args.host, args.port))
    print("[mock-receiver] log => %s" % _LOG_PATH)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-receiver] 收到 Ctrl+C，退出")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
