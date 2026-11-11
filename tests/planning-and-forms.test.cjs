const { test } = require('node:test'),
  assert = require('node:assert/strict'),
  fs = require('node:fs'),
  path = require('node:path');
const web = path.resolve(__dirname, '../apps/web'),
  ts = require(path.join(web, 'node_modules/typescript')),
  React = require(path.join(web, 'node_modules/react')),
  { renderToString } = require(path.join(web, 'node_modules/react-dom/server'));
const compile = (module, file) =>
  module._compile(
    ts.transpileModule(fs.readFileSync(file, 'utf8'), {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        jsx: ts.JsxEmit.ReactJSX,
        target: ts.ScriptTarget.ES2022,
        esModuleInterop: true,
      },
    }).outputText,
    file
  );
require.extensions['.ts'] = compile;
require.extensions['.tsx'] = compile;
const { buildSchedule, OrgChart } = require(path.join(web, 'src/PlanningViews.tsx')),
  { workflowInputs, workflowValues, workflowInitial, normalizeFormEditor } = require(
    path.join(web, 'src/workflow-forms.ts')
  );

test('due-only tasks are milestones, with no invented task duration', () => {
  const schedule = buildSchedule(
    [{ id: 't', name: 'Dated work', projectId: 'p', dueDate: '2026-11-20' }],
    [{ id: 'p', name: 'Project' }],
    [],
    []
  );
  assert.equal(schedule.length, 1);
  assert.equal(schedule[0].start, schedule[0].end);
  assert.equal(schedule[0].milestone, true);
  assert.equal(schedule[0].basis, 'Task dates');
});
test('timeline labels inherited sprint dates and omits invalid/undated ranges', () => {
  const schedule = buildSchedule(
    [
      { id: 't', name: 'Sprint work', projectId: 'p', sprintId: 's' },
      { id: 'bad', name: 'Invalid', startDate: '2026-12-20', dueDate: '2026-12-01' },
      { id: 'none', name: 'Undated' },
    ],
    [{ id: 'p', name: 'Project' }],
    [{ id: 's', name: 'Sprint', startDate: '2026-11-01', endDate: '2026-11-14' }],
    [{ id: 'm', name: 'Handoff', projectId: 'p', dueDate: '2026-11-15' }]
  );
  assert.equal(schedule.length, 2);
  assert.equal(schedule[0].basis, 'Sprint dates');
  assert.equal(schedule[1].kind, 'milestone');
});
test('organization chart shows actual reporting names and handles cyclic imported relationships safely', () => {
  const employees = [
    { id: 'a', name: 'Manager', departmentId: 'd' },
    { id: 'b', name: 'Report', managerId: 'a', departmentId: 'd' },
    { id: 'c', name: 'Cycle one', managerId: 'z' },
    { id: 'z', name: 'Cycle two', managerId: 'c' },
  ];
  const html = renderToString(
    React.createElement(OrgChart, {
      employees,
      departments: [{ id: 'd', name: 'Engineering' }],
      company: 'Test Company',
      onSelect() {},
    })
  );
  assert.match(html, /Test Company/);
  assert.match(html, /Engineering/);
  assert.match(html, /Manager/);
  assert.match(html, /Report/);
  assert.match(html, /requires review/);
});
test('dynamic forms retain numeric zero, ignore unknown client fields and use schema labels', () => {
  const schema = [
    { key: 'amount', label: 'Requested amount', type: 'NUMBER', required: true, min: 0, max: 10 },
    { key: 'reason', label: 'Reason', type: 'TEXT' },
  ];
  assert.deepEqual(
    workflowValues(schema, {
      form__amount: 0,
      form__reason: 'Approved purpose',
      form__userId: 'unauthorized-field',
    }),
    { amount: 0, reason: 'Approved purpose' }
  );
  const inputs = workflowInputs(schema);
  assert.equal(inputs[0].type, 'number');
  assert.equal(inputs[0].label, 'Requested amount');
  assert.equal(inputs[0].min, 0);
  assert.equal(inputs[0].required, true);
});
test('resubmission restores only fields captured in the original schema', () => {
  const schema = [{ key: 'reason', label: 'Reason', type: 'TEXT' }];
  assert.deepEqual(
    workflowInitial(schema, { reason: 'Revised', newDraftOnlyField: 'not in published version' }),
    { form__reason: 'Revised' }
  );
});
test('form editor normalizes booleans, option lists and zero numeric bounds', () => {
  const fields = normalizeFormEditor([
    {
      key: 'choice',
      label: 'Choice',
      type: 'SELECT',
      required: 'false',
      optionsText: 'First\nSecond\n',
    },
    { key: 'value', label: 'Value', type: 'NUMBER', required: 'true', min: 0, max: 0 },
  ]);
  assert.equal(fields[0].required, false);
  assert.deepEqual(fields[0].options, ['First', 'Second']);
  assert.equal(fields[1].required, true);
  assert.equal(fields[1].min, 0);
  assert.equal(fields[1].max, 0);
});
