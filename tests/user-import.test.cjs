const { test } = require('node:test'),
  assert = require('node:assert/strict'),
  fs = require('node:fs'),
  path = require('node:path'),
  Module = require('node:module'),
  ts = require('../apps/web/node_modules/typescript');
const filename = path.resolve(__dirname, '../apps/web/src/import-users.ts'),
  compiled = new Module(filename, module);
compiled._compile(
  ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText,
  filename
);
const { parseUserImport } = compiled.exports;
test('bulk import accepts header, CRLF and quoted CSV', () =>
  assert.deepEqual(
    parseUserImport('email,role\r\n"one@example.test",member\r\ntwo@example.test,HR'),
    [
      { email: 'one@example.test', role: 'MEMBER' },
      { email: 'two@example.test', role: 'HR' },
    ]
  ));
test('malformed, empty and oversized imports fail before submission', () => {
  assert.throws(() => parseUserImport('email,role\n'), /at least one/);
  assert.throws(() => parseUserImport('one@example.test,MEMBER,extra'), /exactly two/);
  assert.throws(() => parseUserImport('"one@example.test,MEMBER'), /not closed/);
  assert.throws(
    () => parseUserImport(Array(201).fill('one@example.test,MEMBER').join('\n')),
    /at most 200/
  );
});
