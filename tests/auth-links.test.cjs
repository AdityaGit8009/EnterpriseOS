const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('../apps/web/node_modules/typescript');
const filename = path.resolve(__dirname, '../apps/web/src/auth-links.ts');
const compiled = new Module(filename, module);
compiled._compile(
  ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText,
  filename
);
const { consumeAuthLink } = compiled.exports;

test('invitation email links open acceptance and strip credentials while preserving other URL state', () => {
  const calls = [];
  const result = consumeAuthLink(
    {
      href: 'https://eos.example/?flow=invite&token=single-use-token&campaign=onboarding#dashboard',
    },
    { replaceState: (...args) => calls.push(args) }
  );
  assert.deepEqual(result, { flow: 'accept', token: 'single-use-token' });
  assert.equal(calls[0][2], '/?campaign=onboarding#dashboard');
  assert.equal(calls[0][0], null);
  assert.ok(!JSON.stringify(calls).includes('single-use-token'));
});

test('reset email links prefill reset forms and are consumed only once', () => {
  const location = { href: 'https://eos.example/?flow=reset&token=reset-secret' };
  const history = {
    replaceState: (_state, _title, url) => {
      location.href = new URL(url, location.href).href;
    },
  };
  assert.deepEqual(consumeAuthLink(location, history), { flow: 'reset', token: 'reset-secret' });
  assert.equal(location.href, 'https://eos.example/');
  assert.equal(consumeAuthLink(location, history), null);
});

test('unknown flows never become auth actions and their token is still removed', () => {
  let rewritten = '';
  assert.equal(
    consumeAuthLink(
      { href: 'https://eos.example/?flow=other&token=secret&view=people' },
      {
        replaceState: (_s, _t, url) => {
          rewritten = url;
        },
      }
    ),
    null
  );
  assert.equal(rewritten, '/?flow=other&view=people');
});

test('oversized email tokens are not passed into the form', () => {
  let rewritten = '';
  const result = consumeAuthLink(
    { href: 'https://eos.example/?flow=reset&token=' + 'x'.repeat(257) },
    {
      replaceState: (_s, _t, url) => {
        rewritten = url;
      },
    }
  );
  assert.deepEqual(result, { flow: 'reset', token: '' });
  assert.equal(rewritten, '/');
});

test('company verification links retain their distinct flow and remove tokens from history', () => {
  let rewritten = '';
  const result = consumeAuthLink(
    { href: 'https://eos.example/?flow=verify-company&token=company-verification#dashboard' },
    {
      replaceState: (_s, _t, url) => {
        rewritten = url;
      },
    }
  );
  assert.deepEqual(result, { flow: 'verify-company', token: 'company-verification' });
  assert.equal(rewritten, '/#dashboard');
});
