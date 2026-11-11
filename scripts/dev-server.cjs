const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../apps/web/dist');
const port = Number(process.env.WEB_PORT || 5173);
if (process.env.EOS_WEB_PID_FILE)
  fs.writeFileSync(process.env.EOS_WEB_PID_FILE, String(process.pid));
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
};
http
  .createServer((req, res) => {
    if (req.url.startsWith('/api/')) {
      const target = new URL(process.env.API_BASE_URL || 'http://127.0.0.1:8080');
      const proxy = http.request(
        {
          host: target.hostname,
          port: target.port || 80,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: target.host },
        },
        (up) => {
          res.writeHead(up.statusCode, up.headers);
          up.pipe(res);
        }
      );
      proxy.on('error', () => {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            message: 'API is not running. Start the full application with Start-EnterpriseOS.ps1.',
            code: 'API_UNAVAILABLE',
          })
        );
      });
      req.pipe(proxy);
      return;
    }
    let file;
    try {
      file = path.resolve(
        root,
        '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
      );
    } catch {
      res.writeHead(400);
      res.end();
      return;
    }
    if (!file.startsWith(root + path.sep) && file !== root) {
      res.writeHead(403);
      res.end();
      return;
    }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory())
      file = path.join(root, 'index.html');
    res.writeHead(200, {
      'Content-Type': types[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'same-origin',
    });
    fs.createReadStream(file).pipe(res);
  })
  .listen(port, '127.0.0.1', () => console.log(`EnterpriseOS preview: http://localhost:${port}`));
