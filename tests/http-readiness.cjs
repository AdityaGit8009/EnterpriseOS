// Read-only live deployment checks. Does not provision users or modify business records.
const assert = require('node:assert/strict'),
  fs = require('node:fs'),
  path = require('node:path'),
  crypto = require('node:crypto');
const base = process.env.EOS_BASE_URL || 'http://localhost:5173';
async function main() {
  let count = 0;
  const checks = [];
  async function check(name, fn) {
    await fn();
    count++;
    checks.push(name);
  }
  await check('frontend HTML', async () => {
    const r = await fetch(base);
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/html/);
    assert.match(await r.text(), /id="root"/);
  });
  for (const file of ['app.js', 'app.css'])
    await check('exact production ' + file, async () => {
      const r = await fetch(base + '/assets/' + file);
      assert.equal(r.status, 200);
      const actual = Buffer.from(await r.arrayBuffer()),
        expected = fs.readFileSync(path.resolve(__dirname, '../apps/web/dist/assets', file));
      assert.equal(
        crypto.createHash('sha256').update(actual).digest('hex'),
        crypto.createHash('sha256').update(expected).digest('hex')
      );
    });
  await check('first-run setup status', async () => {
    const r = await fetch(base + '/api/v1/setup');
    assert.equal(r.status, 200);
    assert.equal(typeof (await r.json()).required, 'boolean');
    assert.equal(r.headers.get('cache-control'), 'no-store');
    assert.ok(r.headers.get('x-request-id'));
  });
  for (const endpoint of [
    '/me',
    '/org/profile',
    '/org/records/employees',
    '/org/business/records/leads',
    '/org/business/inventory/stock',
    '/org/business/records/invoices',
    '/org/business/payroll/payslips',
    '/org/workflows/definitions',
    '/org/documents',
    '/org/reports',
    '/platform/plans',
    '/platform/runtime-metrics',
  ])
    await check('anonymous blocked ' + endpoint, async () => {
      const r = await fetch(base + '/api/v1' + endpoint);
      assert.equal(r.status, 401);
      assert.equal((await r.json()).code, 'UNAUTHENTICATED');
    });
  await check('untrusted origin blocked', async () => {
    const r = await fetch(base + '/api/v1/auth/login', {
      method: 'POST',
      headers: { Origin: 'https://untrusted.example', 'Content-Type': 'application/json' },
      body: '{}',
    });
    assert.equal(r.status, 403);
  });
  await check('invalid login validation', async () => {
    const r = await fetch(base + '/api/v1/auth/login', {
      method: 'POST',
      headers: { Origin: base, 'Content-Type': 'application/json' },
      body: '{}',
    });
    assert.equal(r.status, 400);
  });
  const result = { base, checkedAt: new Date().toISOString(), passed: count, checks };
  fs.mkdirSync(path.resolve(__dirname, '../tmp'), { recursive: true });
  fs.writeFileSync(
    path.resolve(__dirname, '../tmp/http-readiness.json'),
    JSON.stringify(result, null, 2)
  );
  console.log(count + ' live HTTP readiness checks passed.');
}
main().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
