"""B站 API 代理 — 多线程，从宿主机 IP 转发到 B站，绕过 Docker IP 限制"""
import http.server
import socketserver
import urllib.request
import urllib.parse
import sys

PORT = 18888
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class ProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        target_url = params.get("url", [None])[0]
        if not target_url:
            self.send_error(400, "Missing ?url=")
            return
        target_url = urllib.parse.quote(target_url, safe=':/?&=%')
        try:
            req = urllib.request.Request(target_url)
            req.add_header("User-Agent", UA)
            req.add_header("Referer", "https://www.bilibili.com/")
            req.add_header("Origin", "https://www.bilibili.com")
            req.add_header("Accept", "application/json, text/plain, */*")
            req.add_header("Accept-Language", "zh-CN,zh;q=0.9")
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = resp.read()
                self.send_response(resp.status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(body)
        except Exception as e:
            self.send_error(502, str(e))

    def log_message(self, format, *args):
        print(f"[proxy] {args[0]}", flush=True)

if __name__ == "__main__":
    server = socketserver.ThreadingTCPServer(("0.0.0.0", PORT), ProxyHandler)
    print(f"Bilibili proxy (threaded) listening on port {PORT}", flush=True)
    server.serve_forever()
