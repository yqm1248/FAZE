# -*- coding: utf-8 -*-
"""
法泽 FAZE · 设计稿本地静态服务器
================================

为什么不直接用 `python -m http.server`：

1. **不发 Cache-Control**。Python 自带的 http.server 不设任何缓存头，
   浏览器就按启发式规则缓存，结果是改完 HTML/CSS 刷新看不到变化 ——
   第 4 批走查时实际踩到：改了三处间距，项目负责人那边看到的还是旧版，
   以为是没改，实际是浏览器拿了缓存。后来又被迫手动加 ?v=N 破缓存。
   本脚本给所有响应加 no-store，改完保存刷新即可，不需要版本号。

2. **woff2 的 MIME 是 octet-stream**。浏览器多数能认，但不规范；
   HTTP/2 推送和某些 CDN 会按 MIME 拒绝。这里显式给 font/woff2。

3. **文本类型补 charset**。不写 charset 时浏览器靠 meta 标签猜，
   在一些工具（curl / 内置预览）里中文会乱码。

用法：
    python scripts/serve-design.py
    python scripts/serve-design.py --port 8124 --dir design/ui
    python scripts/serve-design.py --verbose      # 打开访问日志

注意：这个脚本只服务设计稿（design/ui），不是生产服务器。
"""
import http.server
import os
import sys
import argparse

DEFAULT_DIR = os.path.join("design", "ui")

# 扩展名 -> Content-Type（带 charset，避免中文乱码）
MIME_OVERRIDES = {
    ".html": "text/html; charset=utf-8",
    ".htm": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".woff2": "font/woff2",
    ".woff": "font/woff",
    ".ttf": "font/ttf",
    ".md": "text/markdown; charset=utf-8",
}


class DesignHandler(http.server.SimpleHTTPRequestHandler):
    """静态文件处理器：禁缓存 + 正确的 Content-Type。"""

    server_version = "FAZEDesignPreview/1.0"

    def guess_type(self, path):
        ext = os.path.splitext(path)[1].lower()
        if ext in MIME_OVERRIDES:
            return MIME_OVERRIDES[ext]
        return super().guess_type(path)

    def end_headers(self):
        # 关键：三个头一起给，覆盖老浏览器与代理的各种缓存判断
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def log_message(self, fmt, *args):
        if self.server.verbose:
            super().log_message(fmt, *args)

    def list_directory(self, path):
        """目录列表也禁缓存（画布偶尔会直接开目录）"""
        f = super().list_directory(path)
        return f


def main():
    ap = argparse.ArgumentParser(description="法泽设计稿本地服务器（禁缓存）")
    ap.add_argument("--port", type=int, default=8123, help="端口，默认 8123")
    ap.add_argument("--bind", default="127.0.0.1", help="绑定地址，默认仅本机")
    ap.add_argument("--dir", default=None, help="要服务的目录，默认 design/ui")
    ap.add_argument("--verbose", action="store_true", help="打印访问日志")
    args = ap.parse_args()

    # 目录解析：相对路径先按仓库根找，找不到再按当前目录找
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target = args.dir or DEFAULT_DIR
    if not os.path.isabs(target):
        candidate = os.path.join(root, target)
        target = candidate if os.path.isdir(candidate) else os.path.abspath(target)

    if not os.path.isdir(target):
        sys.stderr.write("目录不存在: %s\n" % target)
        return 1

    os.chdir(target)

    # 线程版：画布会并发请求 19 个页面 + 字体 + css
    handler = DesignHandler
    with http.server.ThreadingHTTPServer((args.bind, args.port), handler) as httpd:
        httpd.verbose = args.verbose
        sys.stdout.write("法泽设计稿服务器\n")
        sys.stdout.write("  目录: %s\n" % target)
        sys.stdout.write("  地址: http://%s:%d/\n" % (args.bind, args.port))
        sys.stdout.write("  缓存: 已禁用（no-store）\n")
        sys.stdout.flush()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            sys.stdout.write("\n已停止\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
