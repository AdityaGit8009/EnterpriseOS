const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const web = path.join(root, 'apps/web');
const output = path.join(web, 'dist');
fs.mkdirSync(path.join(output, 'assets'), { recursive: true });
// Bundle an IIFE so the supplied HTML also works directly over file://.
const esbuild = require.resolve('esbuild/bin/esbuild', { paths: [web] });
const args = [
  path.join(web, 'src/main.tsx'),
  '--bundle',
  '--format=iife',
  '--target=es2022',
  '--minify',
  '--define:process.env.NODE_ENV="production"',
  `--outfile=${path.join(output, 'assets/app.js')}`,
];
if (process.platform === 'win32') {
  const executable = path.join(web, 'node_modules/@esbuild/win32-x64/esbuild.exe');
  cp.execFileSync(executable, args, { stdio: 'inherit', cwd: web });
} else cp.execFileSync(esbuild, args, { stdio: 'inherit', cwd: web });
const html = `<!doctype html><html lang="en" data-theme="ocean" data-mode="light"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#101e3e"><meta name="description" content="EnterpriseOS company administration, workforce and project operations"><title>EnterpriseOS — Your work, connected</title><link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'%3E%3Crect width='40' height='40' rx='10' fill='%232563eb'/%3E%3Cpath d='M11 10h19v5H17v4h11v5H17v4h13v5H11z' fill='white'/%3E%3C/svg%3E"><link rel="stylesheet" href="./assets/app.css"></head><body><div id="root"></div><noscript>Enable JavaScript to use EnterpriseOS.</noscript><script src="./assets/app.js"></script></body></html>`;
fs.writeFileSync(path.join(output, 'index.html'), html);
// Retain the original theme reference once, then turn the user's exact filename
// into the built application with self-contained assets for direct opening.
const entry = path.join(root, 'erp-home (1).html');
const original = path.join(root, 'references', 'erp-home.original.html');
fs.mkdirSync(path.dirname(original), { recursive: true });
if (!fs.existsSync(original) && fs.existsSync(entry)) fs.copyFileSync(entry, original);
let standalone = html
  .replace(
    '<link rel="stylesheet" href="./assets/app.css">',
    `<style>${fs.readFileSync(path.join(output, 'assets/app.css'), 'utf8').replace(/<\/style/gi, '<\\/style')}</style>`
  )
  .replace(
    '<script src="./assets/app.js"></script>',
    () =>
      `<script>${fs.readFileSync(path.join(output, 'assets/app.js'), 'utf8').replace(/<\/script/gi, '<\\/script')}</script>`
  );
fs.writeFileSync(entry, standalone);
console.log('Built apps/web/dist and erp-home (1).html');
