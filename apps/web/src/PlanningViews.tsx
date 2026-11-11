import React, { useMemo, useState } from 'react';
import { Row } from './data';
import { Badge, Button, Empty, initials, prettyDate } from './ui';

export function OrgChart({
  employees,
  departments,
  company,
  onSelect,
}: {
  employees: Row[];
  departments: Row[];
  company: string;
  onSelect: (row: Row) => void;
}) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const byId = new Map(employees.map((e) => [e.id, e]));
  const children = new Map<string, Row[]>();
  for (const e of employees) {
    const parent = e.managerId && byId.has(e.managerId) && e.managerId !== e.id ? e.managerId : '';
    children.set(parent, [...(children.get(parent) || []), e]);
  }
  const visited = new Set<string>();
  const branch = (e: Row, ancestors: Set<string>): React.ReactNode => {
    if (ancestors.has(e.id))
      return (
        <li key={e.id} className="chart-cycle">
          Reporting relationship requires review.
        </li>
      );
    visited.add(e.id);
    const next = new Set(ancestors).add(e.id),
      reports = children.get(e.id) || [],
      closed = collapsed.has(e.id);
    return (
      <li key={e.id}>
        <div className="org-node">
          <button className="org-person" onClick={() => onSelect(e)}>
            <span className="avatar">{initials(e.name)}</span>
            <span>
              <strong>{e.name}</strong>
              <small>
                {departments.find((d) => d.id === e.departmentId)?.name ||
                  'Department not assigned'}
              </small>
              <small>{e.employeeCode}</small>
            </span>
          </button>
          {reports.length > 0 && (
            <button
              className="org-collapse"
              aria-expanded={!closed}
              aria-label={(closed ? 'Show' : 'Hide') + ' reports for ' + e.name}
              onClick={() =>
                setCollapsed((old) => {
                  const s = new Set(old);
                  s.has(e.id) ? s.delete(e.id) : s.add(e.id);
                  return s;
                })
              }
            >
              {closed ? '+' : '−'} {reports.length}
            </button>
          )}
        </div>
        {!closed && reports.length > 0 && <ul>{reports.map((r) => branch(r, next))}</ul>}
      </li>
    );
  };
  if (!employees.length)
    return (
      <Empty
        title="Build your organization"
        text="Create employee profiles and assign reporting managers to see your organization chart."
      />
    );
  const roots = children.get('') || [],
    connected = new Set<string>();
  const mark = (e: Row) => {
    if (connected.has(e.id)) return;
    connected.add(e.id);
    for (const child of children.get(e.id) || []) mark(child);
  };
  roots.forEach(mark);
  const disconnected: Row[] = [];
  for (const employee of employees)
    if (!connected.has(employee.id)) {
      disconnected.push(employee);
      mark(employee);
    }
  const trees = roots.map((e) => branch(e, new Set()));
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Organization chart</h2>
          <p>Current employee reporting relationships · {employees.length} profiles</p>
        </div>
        <Button onClick={() => setCollapsed(new Set())}>Expand all</Button>
      </div>
      <div className="org-chart">
        <div className="org-company">{company}</div>
        <ul className="org-roots">
          {trees}
          {disconnected.map((e) => branch(e, new Set()))}
        </ul>
      </div>
    </section>
  );
}

const day = (value: any) =>
  typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)
    ? Date.parse(value.slice(0, 10) + 'T00:00:00Z') / 86400000
    : NaN;
export type ScheduleItem = {
  record: Row;
  start: number;
  end: number;
  milestone: boolean;
  basis: string;
  kind: 'task' | 'milestone';
};
export function buildSchedule(
  tasks: Row[],
  projects: Row[],
  sprints: Row[],
  milestones: Row[]
): ScheduleItem[] {
  const items: ScheduleItem[] = [];
  for (const task of tasks) {
    const sprint = sprints.find((s) => s.id === task.sprintId),
      project = projects.find((p) => p.id === task.projectId);
    let start = day(task.startDate),
      end = day(task.dueDate),
      basis = 'Task dates';
    if (!Number.isFinite(start) && sprint) {
      start = day(sprint.startDate);
      basis = 'Sprint start';
    }
    if (!Number.isFinite(end) && sprint) {
      end = day(sprint.endDate);
      basis = basis === 'Sprint start' ? 'Sprint dates' : 'Sprint end';
    }
    if (!Number.isFinite(start) && !Number.isFinite(end) && project) {
      start = day(project.startDate);
      end = day(project.dueDate);
      basis = 'Project dates';
    }
    if (!Number.isFinite(start) && !Number.isFinite(end)) continue;
    const milestone = !Number.isFinite(start) || !Number.isFinite(end);
    if (!Number.isFinite(start)) start = end;
    if (!Number.isFinite(end)) end = start;
    if (end < start) continue;
    items.push({ record: task, start, end, milestone, basis, kind: 'task' });
  }
  for (const record of milestones) {
    const date = day(record.dueDate || record.endDate);
    if (Number.isFinite(date))
      items.push({
        record,
        start: date,
        end: date,
        milestone: true,
        basis: 'Milestone date',
        kind: 'milestone',
      });
  }
  return items.sort((a, b) => a.start - b.start || a.record.name.localeCompare(b.record.name));
}
export function ProjectTimeline({
  tasks,
  projects,
  sprints,
  milestones,
  onTask,
}: {
  tasks: Row[];
  projects: Row[];
  sprints: Row[];
  milestones: Row[];
  onTask: (row: Row) => void;
}) {
  const [zoom, setZoom] = useState('fit');
  const items = useMemo(
    () => buildSchedule(tasks, projects, sprints, milestones),
    [tasks, projects, sprints, milestones]
  );
  if (!items.length)
    return (
      <section className="panel">
        <Empty
          title="Plan your project timeline"
          text="Add actual task start/due dates, sprint dates or milestones. Undated work is not assigned an invented schedule."
        />
      </section>
    );
  const first = Math.min(...items.map((i) => i.start)),
    last = Math.max(...items.map((i) => i.end)),
    days = Math.max(1, last - first + 1),
    width =
      zoom === 'day' ? Math.max(780, days * 28) : zoom === 'week' ? Math.max(780, days * 9) : 900;
  const step = Math.max(1, Math.ceil(days / 12)),
    ticks = Array.from({ length: Math.ceil(days / step) }, (_, i) => first + i * step),
    iso = (d: number) => new Date(d * 86400000).toISOString().slice(0, 10);
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Project timeline</h2>
          <p>
            Bars use recorded dates. Single-date work appears as a milestone; inherited
            sprint/project dates are labeled.
          </p>
        </div>
        <div className="row-actions">
          {[
            ['fit', 'Fit'],
            ['day', 'Days'],
            ['week', 'Weeks'],
          ].map(([id, name]) => (
            <Button key={id} kind={zoom === id ? 'primary' : ''} onClick={() => setZoom(id)}>
              {name}
            </Button>
          ))}
        </div>
      </div>
      <div className="timeline-scroll">
        <div className="timeline" style={{ width: width + 280 }}>
          <div className="timeline-header">
            <div>Scheduled work</div>
            <div className="timeline-axis" style={{ width }}>
              {ticks.map((t) => (
                <span key={t} style={{ left: ((t - first) / days) * 100 + '%' }}>
                  {prettyDate(iso(t))}
                </span>
              ))}
            </div>
          </div>
          {items.map((item) => (
            <div className="timeline-row" key={item.kind + item.record.id}>
              <div className="timeline-label">
                <button
                  className="text-button"
                  onClick={() => item.kind === 'task' && onTask(item.record)}
                  disabled={item.kind !== 'task'}
                >
                  {item.record.name}
                </button>
                <small>
                  {item.kind === 'milestone' ? 'Project milestone' : item.basis} ·{' '}
                  {projects.find((p) => p.id === item.record.projectId)?.name}
                </small>
              </div>
              <div
                className="timeline-track"
                style={{ width, backgroundSize: (100 * step) / days + '% 100%' }}
              >
                <button
                  className={
                    'timeline-bar ' +
                    (item.milestone ? 'milestone ' : '') +
                    String(item.record.status || '').toLowerCase()
                  }
                  style={{
                    left: ((item.start - first) / days) * 100 + '%',
                    width: item.milestone
                      ? 12
                      : Math.max(1, ((item.end - item.start + 1) / days) * 100) + '%',
                  }}
                  onClick={() => item.kind === 'task' && onTask(item.record)}
                  title={
                    item.record.name +
                    ': ' +
                    iso(item.start) +
                    (item.milestone ? '' : ' to ' + iso(item.end))
                  }
                  aria-label={item.record.name + ' ' + iso(item.start) + ' ' + iso(item.end)}
                >
                  {!item.milestone && <span>{item.record.name}</span>}
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="scope-note timeline-note">
        <Badge value="RECORDED SCHEDULE" />
        <span>
          {tasks.length - items.filter((i) => i.kind === 'task').length} tasks have no valid
          recorded schedule.
        </span>
      </div>
    </section>
  );
}
