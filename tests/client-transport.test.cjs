const { test } = require('node:test'),
  assert = require('node:assert/strict'),
  fs = require('node:fs'),
  path = require('node:path'),
  Module = require('node:module'),
  ts = require('../apps/web/node_modules/typescript');
const filename = path.resolve(__dirname, '../apps/web/src/data.ts'),
  compiled = new Module(filename, module);
compiled._compile(
  ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText,
  filename
);
const d = compiled.exports;
test('API sends credentials and exact JSON mutation body', async () => {
  let captured;
  const original = global.fetch;
  global.fetch = async (url, options) => {
    captured = { url, options };
    return { ok: true, json: async () => ({ id: 'saved' }) };
  };
  try {
    assert.deepEqual(await d.api('/org/profile', 'PUT', { name: 'Company' }), { id: 'saved' });
    assert.equal(captured.url, '/api/v1/org/profile');
    assert.equal(captured.options.credentials, 'include');
    assert.equal(captured.options.body, '{"name":"Company"}');
    assert.equal(captured.options.headers['Content-Type'], 'application/json');
  } finally {
    global.fetch = original;
  }
});
test('API denial stays an error rather than creating local records', async () => {
  const original = global.fetch;
  global.fetch = async () => ({
    ok: false,
    status: 403,
    json: async () => ({ message: 'Permission denied' }),
  });
  try {
    await assert.rejects(
      d.api('/org/users'),
      (e) => e.message === 'Permission denied' && e.status === 403
    );
  } finally {
    global.fetch = original;
  }
});
test('failed loads return empty collections with errors and no generated data', async () => {
  const original = global.fetch;
  global.fetch = async () => {
    throw new Error('Offline');
  };
  try {
    const loaded = await d.loadLive({
      userId: 'u',
      tenantId: 't',
      name: 'Owner',
      email: 'o@example.test',
      role: 'ADMIN',
    });
    assert.ok(loaded.errors.length > 0);
    for (const value of Object.values(loaded.snapshot)) assert.deepEqual(value, []);
  } finally {
    global.fetch = original;
  }
});
test('fresh snapshots are separate empty server collections', () => {
  const a = d.emptySnapshot(),
    b = d.emptySnapshot();
  a.users.push({ id: 'test' });
  assert.equal(b.users.length, 0);
  assert.equal(b.tasks.length, 0);
  assert.equal(b.tenants.length, 0);
});
test('HR loads do not request restricted organization audit', async () => {
  const calls = [],
    original = global.fetch;
  global.fetch = async (url) => {
    calls.push(url);
    return { ok: true, json: async () => [] };
  };
  try {
    await d.loadLive({
      userId: 'h',
      tenantId: 't',
      name: 'HR',
      email: 'h@example.test',
      role: 'HR',
    });
    assert.ok(!calls.some((url) => url.includes('audit-events')));
  } finally {
    global.fetch = original;
  }
});

test('custom permission matching enforces method and route templates without sibling-route access', () => {
  const p = {
    userId: 'm',
    tenantId: 't',
    name: 'Member',
    email: 'm@example.test',
    role: 'MEMBER',
    customRoleId: 'narrow',
    permissions: ['GET /api/v1/org/records/{kind}', 'PUT /api/v1/org/tasks/{id}/status'],
  };
  assert.equal(d.canAccess(p, 'GET', '/org/records/employees'), true);
  assert.equal(d.canAccess(p, 'POST', '/org/records/employees'), false);
  assert.equal(d.canAccess(p, 'PUT', '/org/tasks/task-1/status'), true);
  assert.equal(d.canAccess(p, 'PUT', '/org/tasks/task-1/status/history'), false);
  assert.equal(d.canAccess(p, 'GET', '/org/users'), false);
  assert.equal(d.canAccess(p, 'GET', '/org/my-permissions'), true);
});

test('custom-role bootstrap requests only allowed organization routes and preserves its scope', async () => {
  const calls = [],
    original = global.fetch;
  global.fetch = async (url) => {
    calls.push(url);
    return {
      ok: true,
      json: async () =>
        url.endsWith('/org/my-permissions')
          ? {
              customRoleId: 'role-1',
              name: 'People reader',
              baseRole: 'MEMBER',
              permissions: ['GET /api/v1/org/records/{kind}'],
            }
          : [],
    };
  };
  try {
    const loaded = await d.loadLive({
      userId: 'm',
      tenantId: 't',
      name: 'Member',
      email: 'm@example.test',
      role: 'MEMBER',
    });
    assert.equal(loaded.snapshot.myPermissions[0].customRoleId, 'role-1');
    assert.deepEqual(loaded.errors, []);
    assert.ok(calls.includes('/api/v1/org/records/employees'));
    assert.ok(!calls.includes('/api/v1/org/users'));
    assert.ok(!calls.includes('/api/v1/org/attendance'));
    assert.ok(!calls.includes('/api/v1/org/profile'));
  } finally {
    global.fetch = original;
  }
});
