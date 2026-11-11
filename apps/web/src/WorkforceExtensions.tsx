import React, { useEffect, useState } from 'react';
import { api, Principal, Row, Snapshot, today, canAccess } from './data';
import { Badge, Button, Col, Dialog, Empty, Field, label, opts, prettyDate, Table } from './ui';

type Props = {
  mode: 'self-service' | 'projects';
  tool?: string;
  projectId?: string;
  principal: Principal;
  snapshot: Snapshot;
  search: string;
  setDialog: (dialog: Dialog | null) => void;
  notify: (message: string, error?: boolean) => void;
  onChanged: () => Promise<void>;
};
const names: Record<string, string> = {
  exits: 'Exit requests',
  encashments: 'Leave encashment eligibility',
  compOff: 'Compensatory time',
  encashmentPolicy: 'Encashment policies',
  compOffPolicy: 'Compensatory time policies',
  risks: 'Risk register',
  recurring: 'Recurring tasks',
  analytics: 'Task aging & throughput',
};
const f = (
  key: string,
  title: string,
  type = 'text',
  required = true,
  extra: Partial<Field> = {}
): Field => ({ key, label: title, type, required, ...extra });
export function WorkforceExtensions({
  mode,
  tool,
  projectId,
  principal,
  snapshot,
  search,
  setDialog,
  notify,
  onChanged,
}: Props) {
  const managing = ['ADMIN', 'HR'].includes(principal.role),
    projectManager = ['ADMIN', 'MANAGER'].includes(principal.role),
    write = principal.role !== 'AUDITOR';
  const rawChoices =
    mode === 'self-service'
      ? [
          'exits',
          'encashments',
          'compOff',
          ...(managing ? ['encashmentPolicy', 'compOffPolicy'] : []),
        ]
      : [tool || 'risks'];
  const [tab, setTab] = useState(rawChoices[0]),
    [selected, setSelected] = useState(
      projectId && projectId !== 'all' ? projectId : snapshot.projects[0]?.id || ''
    ),
    [rows, setRows] = useState<Record<string, Row[]>>({}),
    [analytics, setAnalytics] = useState<any>(null),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    if (projectId && projectId !== 'all') setSelected(projectId);
  }, [projectId]);
  const path = (kind: string) =>
    kind === 'encashmentPolicy'
      ? '/org/workforce/policies/encashment'
      : kind === 'compOffPolicy'
        ? '/org/workforce/policies/compOff'
        : kind === 'risks'
          ? '/org/workforce/projects/' + selected + '/risks'
          : kind === 'recurring'
            ? '/org/workforce/recurring-tasks'
            : '/org/workforce/requests/' + kind;
  const choices = rawChoices.filter((kind) =>
    canAccess(
      principal,
      'GET',
      kind === 'analytics' ? '/org/workforce/analytics/tasks' : path(kind)
    )
  );
  const desired = mode === 'projects' ? tool || 'risks' : tab;
  const current = choices.includes(desired) ? desired : choices[0];
  const reference = (key: string, title: string, kind: string, required = true) =>
    f(key, title, 'select', required, {
      options: snapshot[kind]
        .filter((r) => kind !== 'users' || r.status === 'ACTIVE')
        .map((r) => ({ value: r.id, label: r.name })),
    });
  const load = async () => {
    setBusy(true);
    const next: Record<string, Row[]> = {},
      failures: Record<string, string> = {};
    await Promise.all(
      choices.map(async (kind) => {
        try {
          if (kind === 'risks' && !selected) {
            next[kind] = [];
            return;
          }
          const data = await api(
            kind === 'analytics' ? '/org/workforce/analytics/tasks' : path(kind)
          );
          if (kind === 'analytics') {
            setAnalytics(data);
            return;
          }
          next[kind] = Array.isArray(data) ? data : [];
        } catch (e) {
          failures[kind] = (e as Error).message;
          next[kind] = [];
        }
      })
    );
    setRows(next);
    setErrors(failures);
    setBusy(false);
  };
  useEffect(() => {
    void load();
  }, [mode, tool, selected, principal.userId]);
  const mutate = async (url: string, payload: any, method = 'POST') => {
    await api(url, method, payload);
    await load();
    await onChanged();
    notify('Changes saved');
  };
  function policy(kind: string, row?: Row) {
    const enc = kind === 'encashmentPolicy';
    const fields = [
      f('name', 'Policy name'),
      f('leaveType', 'Leave type', 'select', true, {
        options: snapshot.leavePolicies.map((p) => ({ value: p.type || p.name, label: p.name })),
      }),
      ...(enc
        ? [
            f('eligibleAfterMonths', 'Minimum service months', 'number', true, {
              min: 0,
              max: 600,
            }),
            f('minRemainingDays', 'Required remaining leave days', 'number', true, { min: 0 }),
            f('maxDays', 'Maximum eligible days', 'number', true, { min: 0.1, max: 366 }),
          ]
        : [
            f('hoursPerDay', 'Hours for one leave day', 'number', true, { min: 0.1, max: 24 }),
            f('minimumHours', 'Minimum eligible hours', 'number', true, { min: 0.1, max: 24 }),
            f('maximumHours', 'Maximum eligible hours', 'number', true, { min: 0.1, max: 24 }),
            f('validForDays', 'Submit within days of work', 'number', true, { min: 1, max: 366 }),
            f('requireAttendance', 'Require completed attendance', 'select', true, {
              options: opts(['true', 'false']),
            }),
            f('nonWorkingOnly', 'Non-working days only', 'select', true, {
              options: opts(['true', 'false']),
            }),
          ]),
    ];
    setDialog({
      title: (row ? 'Edit ' : 'Create ') + names[kind],
      fields,
      values: {
        eligibleAfterMonths: 0,
        minRemainingDays: 0,
        maxDays: 30,
        hoursPerDay: 8,
        minimumHours: 1,
        maximumHours: 24,
        validForDays: 90,
        ...row,
        requireAttendance: String(row?.requireAttendance ?? true),
        nonWorkingOnly: String(row?.nonWorkingOnly ?? true),
      },
      onSubmit: (v) =>
        mutate(
          path(kind) + (row ? '/' + row.id : ''),
          {
            ...v,
            ...(!enc
              ? {
                  requireAttendance: v.requireAttendance === 'true',
                  nonWorkingOnly: v.nonWorkingOnly === 'true',
                }
              : {}),
          },
          row ? 'PUT' : 'POST'
        ),
    });
  }
  function request(kind: string) {
    const fields =
      kind === 'exits'
        ? [f('exitDate', 'Requested exit date', 'date'), f('reason', 'Reason', 'textarea')]
        : kind === 'encashments'
          ? [
              f('days', 'Days requested', 'number', true, { min: 0.1, max: 366 }),
              f('reason', 'Reason', 'textarea'),
            ]
          : [
              f('workDate', 'Date worked', 'date'),
              f('hours', 'Hours worked', 'number', true, { min: 0.1, max: 24 }),
              f('reason', 'Reason', 'textarea'),
            ];
    setDialog({
      title: 'Submit ' + names[kind].toLowerCase(),
      description:
        kind === 'encashments'
          ? 'This requests policy eligibility only. Approval does not calculate tax, run payroll or transfer funds.'
          : kind === 'exits'
            ? 'An independent HR reviewer must approve the request. Access is removed only when the approved exit takes effect.'
            : 'Approved eligible hours credit the configured leave type once.',
      fields,
      values: { exitDate: today(), workDate: today(), days: 1, hours: 8 },
      onSubmit: (v) => mutate(path(kind), v),
    });
  }
  function review(row: Row) {
    setDialog({
      title: 'Review employee request',
      fields: [
        f('decision', 'Decision', 'select', true, { options: opts(['APPROVED', 'REJECTED']) }),
        f('reason', 'Review reason', 'textarea'),
      ],
      values: { decision: 'APPROVED' },
      onSubmit: (v) => mutate('/org/workforce/requests/' + row.id + '/decision', v),
    });
  }
  function risk(row?: Row) {
    setDialog({
      title: row ? 'Edit project risk' : 'Register project risk',
      fields: [
        f('name', 'Risk name'),
        f('description', 'Description', 'textarea'),
        f('probability', 'Probability (1–5)', 'number', true, { min: 1, max: 5 }),
        f('impact', 'Impact (1–5)', 'number', true, { min: 1, max: 5 }),
        f('mitigation', 'Mitigation plan', 'textarea'),
        reference('ownerId', 'Risk owner', 'users'),
        f('status', 'Status', 'select', true, { options: opts(['OPEN', 'MITIGATING', 'CLOSED']) }),
      ],
      values: { probability: 1, impact: 1, status: 'OPEN', ownerId: principal.userId, ...row },
      onSubmit: (v) =>
        mutate(row ? '/org/workforce/risks/' + row.id : path('risks'), v, row ? 'PUT' : 'POST'),
    });
  }
  function recurring(row?: Row) {
    setDialog({
      title: row ? 'Edit recurring task' : 'Configure recurring task',
      description:
        'Enabled schedules generate real tasks once per due date. Paused schedules do not create work.',
      fields: [
        f('name', 'Task title'),
        reference('projectId', 'Project', 'projects'),
        reference('assigneeId', 'Assignee', 'users', false),
        f('priority', 'Priority', 'select', true, {
          options: opts(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']),
        }),
        f('intervalDays', 'Repeat every days', 'number', true, { min: 1, max: 366 }),
        f('nextDate', 'Next occurrence', 'date'),
        f('endDate', 'End date', 'date', false),
        f('enabled', 'Schedule enabled', 'select', true, {
          options: [
            { value: 'true', label: 'Enabled' },
            { value: 'false', label: 'Paused' },
          ],
        }),
      ],
      values: {
        projectId: selected,
        priority: 'MEDIUM',
        intervalDays: 7,
        nextDate: today(),
        enabled: 'false',
        ...row,
        ...(row ? { enabled: String(row.enabled) } : {}),
      },
      onSubmit: (v) =>
        mutate(
          path('recurring') + (row ? '/' + row.id : ''),
          { ...v, enabled: v.enabled === 'true' },
          row ? 'PUT' : 'POST'
        ),
    });
  }
  const list = (rows[current] || []).filter(
    (r) => current !== 'recurring' || !selected || r.projectId === selected
  );
  const columns: Col[] =
    current === 'risks'
      ? [
          { key: 'name', title: 'Risk' },
          { key: 'probability', title: 'Probability' },
          { key: 'impact', title: 'Impact' },
          {
            key: 'score',
            title: 'Risk score',
            render: (r) => Number(r.probability) * Number(r.impact),
          },
          {
            key: 'ownerId',
            title: 'Owner',
            render: (r) => snapshot.users.find((u) => u.id === r.ownerId)?.name || 'Unassigned',
          },
          { key: 'status', title: 'Status', render: (r) => <Badge value={r.status} /> },
        ]
      : current === 'recurring'
        ? [
            { key: 'name', title: 'Task' },
            { key: 'intervalDays', title: 'Every days' },
            { key: 'nextDate', title: 'Next occurrence', render: (r) => prettyDate(r.nextDate) },
            { key: 'endDate', title: 'Ends', render: (r) => prettyDate(r.endDate) },
            {
              key: 'enabled',
              title: 'Schedule',
              render: (r) => <Badge value={r.enabled ? 'ACTIVE' : 'PAUSED'} />,
            },
            { key: 'lastError', title: 'Last run issue', render: (r) => r.lastError || 'None' },
          ]
        : current.endsWith('Policy')
          ? [
              { key: 'name', title: 'Policy' },
              { key: 'leaveType', title: 'Leave type' },
              ...(current === 'encashmentPolicy'
                ? [
                    { key: 'eligibleAfterMonths', title: 'Service months' },
                    { key: 'minRemainingDays', title: 'Remaining days minimum' },
                    { key: 'maxDays', title: 'Maximum eligible days' },
                  ]
                : [
                    { key: 'hoursPerDay', title: 'Hours per day' },
                    { key: 'minimumHours', title: 'Minimum hours' },
                    { key: 'validForDays', title: 'Submission window (days)' },
                  ]),
            ]
          : [
              {
                key: 'userId',
                title: 'Requester',
                render: (r) =>
                  snapshot.users.find((u) => u.id === (r.userId || r.requesterId))?.name ||
                  r.name ||
                  'Your request',
              },
              ...(current === 'exits'
                ? [
                    {
                      key: 'exitDate',
                      title: 'Exit date',
                      render: (r: Row) => prettyDate(r.exitDate),
                    },
                  ]
                : current === 'encashments'
                  ? [{ key: 'days', title: 'Requested days' }]
                  : [
                      {
                        key: 'workDate',
                        title: 'Work date',
                        render: (r: Row) => prettyDate(r.workDate),
                      },
                      { key: 'hours', title: 'Worked hours' },
                    ]),
              { key: 'reason', title: 'Reason' },
              { key: 'status', title: 'Status', render: (r) => <Badge value={r.status} /> },
            ];
  if (!choices.length)
    return (
      <section className="panel">
        <Empty
          title="This section is outside your assigned permissions"
          text="Your company administrator can review the read actions in your custom role."
        />
      </section>
    );
  if (mode === 'projects' && !snapshot.projects.length)
    return (
      <section className="panel">
        <Empty
          title="Create a project first"
          text="Risk registers and recurring work belong to an authorized company project."
        />
      </section>
    );
  return (
    <>
      {mode === 'self-service' && (
        <div className="tabs" role="tablist">
          {choices.map((kind) => (
            <button
              key={kind}
              role="tab"
              aria-selected={current === kind}
              className={current === kind ? 'active' : ''}
              onClick={() => setTab(kind)}
            >
              {names[kind]}
            </button>
          ))}
        </div>
      )}
      {mode === 'projects' && (
        <div className="toolbar">
          <label>
            Project{' '}
            <select value={selected} onChange={(e) => setSelected(e.target.value)}>
              {snapshot.projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      {errors[current] && (
        <p className="inline-error" role="alert">
          {errors[current]}
        </p>
      )}
      {current === 'analytics' ? (
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Task aging & throughput</h2>
              <p>Current task measures from authorized projects</p>
            </div>
            <Button onClick={() => void load()}>Refresh</Button>
          </div>
          {analytics ? (
            <>
              <div className="stats-grid module-stats">
                {Object.entries(analytics)
                  .filter(([, v]) => typeof v === 'number')
                  .map(([key, value]) => (
                    <div className="stat-card" key={key}>
                      <span>{label(key)}</span>
                      <strong className="stat-value">{String(value)}</strong>
                    </div>
                  ))}
              </div>
              {Object.entries(analytics)
                .filter(([, value]) => value && typeof value === 'object' && !Array.isArray(value))
                .map(([key, value]) => (
                  <section className="analytics-section" key={key}>
                    <h3>{label(key.replace(/([a-z])([A-Z])/g, '$1 $2'))}</h3>
                    <div className="widget-chart">
                      {Object.entries(value as Record<string, number>).map(([title, count]) => (
                        <div className="widget-chart-row" key={title}>
                          <span>{title}</span>
                          <div>
                            <i
                              style={{
                                width:
                                  (Number(count) /
                                    Math.max(
                                      1,
                                      ...Object.values(value as Record<string, number>).map(Number)
                                    )) *
                                    100 +
                                  '%',
                              }}
                            />
                          </div>
                          <strong>{count}</strong>
                        </div>
                      ))}
                      {!Object.keys(value as object).length && (
                        <p>No completed task history in this period.</p>
                      )}
                    </div>
                  </section>
                ))}
              {Object.entries(analytics)
                .filter(([, value]) => Array.isArray(value))
                .map(([key, value]) => (
                  <section key={key}>
                    <h3 className="analytics-heading">{label(key)}</h3>
                    <Table
                      rows={(value as any[]).map((r, i) => ({ id: String(i), ...r }))}
                      columns={Object.keys((value as any[])[0] || {})
                        .filter((k) => typeof (value as any[])[0][k] !== 'object')
                        .map((key) => ({ key, title: label(key) }))}
                    />
                  </section>
                ))}
            </>
          ) : (
            <Empty
              title={busy ? 'Loading analytics...' : 'No task measures yet'}
              text="Create dated tasks to track their aging and completion."
            />
          )}
        </section>
      ) : (
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>{names[current]}</h2>
              <p>{busy ? 'Loading server records...' : list.length + ' records'}</p>
            </div>
            <div className="row-actions">
              <Button icon="refresh" disabled={busy} onClick={() => void load()}>
                Refresh
              </Button>
              {current === 'risks' &&
                projectManager &&
                canAccess(principal, 'POST', path('risks')) && (
                  <Button icon="plus" kind="primary" onClick={() => risk()}>
                    Register risk
                  </Button>
                )}
              {current === 'recurring' &&
                projectManager &&
                canAccess(principal, 'POST', path('recurring')) && (
                  <>
                    <Button
                      onClick={() =>
                        setDialog({
                          title: 'Generate due recurring tasks',
                          description:
                            'Run due schedules now. The server prevents duplicate occurrences for the same schedule and date.',
                          fields: [],
                          onSubmit: () => mutate('/org/workforce/recurring-tasks/run-due', {}),
                        })
                      }
                    >
                      Generate due tasks
                    </Button>
                    <Button icon="plus" kind="primary" onClick={() => recurring()}>
                      New schedule
                    </Button>
                  </>
                )}
              {current.endsWith('Policy') &&
                managing &&
                canAccess(principal, 'POST', path(current)) && (
                  <Button icon="plus" kind="primary" onClick={() => policy(current)}>
                    New policy
                  </Button>
                )}
              {mode === 'self-service' &&
                !current.endsWith('Policy') &&
                write &&
                canAccess(principal, 'POST', path(current)) && (
                  <Button icon="plus" kind="primary" onClick={() => request(current)}>
                    Submit request
                  </Button>
                )}
            </div>
          </div>
          <Table
            rows={list}
            search={search}
            columns={columns}
            actions={(r) =>
              current === 'risks' ? (
                projectManager ? (
                  <Button onClick={() => risk(r)}>Edit</Button>
                ) : null
              ) : current === 'recurring' ? (
                <>
                  {projectManager && <Button onClick={() => recurring(r)}>Edit schedule</Button>}
                  <Button
                    onClick={async () => {
                      try {
                        const occurrences = await api(
                          '/org/workforce/recurring-tasks/' + r.id + '/occurrences'
                        );
                        setDialog({
                          title: 'Generated task occurrences',
                          fields: [],
                          submitLabel: 'Done',
                          content: (
                            <Table
                              rows={occurrences}
                              columns={[
                                {
                                  key: 'occurrenceDate',
                                  title: 'Scheduled date',
                                  render: (o) => prettyDate(o.occurrenceDate || o.dueDate),
                                },
                                { key: 'taskId', title: 'Generated task' },
                              ]}
                            />
                          ),
                          onSubmit: () => {},
                        });
                      } catch (e) {
                        notify((e as Error).message, true);
                      }
                    }}
                  >
                    Occurrences
                  </Button>
                </>
              ) : current.endsWith('Policy') ? (
                managing ? (
                  <Button onClick={() => policy(current, r)}>Edit</Button>
                ) : null
              ) : (
                <>
                  {managing &&
                    r.status === 'PENDING' &&
                    (r.userId || r.requesterId) !== principal.userId && (
                      <Button kind="primary" onClick={() => review(r)}>
                        Review
                      </Button>
                    )}
                  {managing &&
                    current === 'exits' &&
                    r.status === 'APPROVED' &&
                    r.exitDate <= today() && (
                      <Button
                        onClick={() =>
                          setDialog({
                            title: 'Complete approved employee exit',
                            description:
                              'The approved effective date has arrived. Completing offboarding removes linked user access.',
                            fields: [],
                            onSubmit: () =>
                              mutate('/org/workforce/requests/' + r.id + '/complete', {}),
                          })
                        }
                      >
                        Complete exit
                      </Button>
                    )}
                </>
              )
            }
          />
        </section>
      )}
    </>
  );
}
