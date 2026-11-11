import React, { useEffect, useRef, useState } from 'react';
import { Icon } from './icons';
import { consumeAuthLink } from './auth-links';
import { AuthGate } from './AuthGate';
import { EnterpriseModules } from './EnterpriseModules';
import { parseUserImport } from './import-users';
import { OrgChart, ProjectTimeline } from './PlanningViews';
import { WorkforceExtensions } from './WorkforceExtensions';
import { IdentitySettings } from './IdentitySettings';
import { DashboardWidgets } from './DashboardWidgets';
import { FeatureFlags } from './FeatureFlags';
import {
  Row,
  Principal,
  Snapshot,
  roles,
  kinds,
  uid,
  today,
  emptySnapshot,
  api,
  loadLive,
  canManage,
  canApprove,
  isAdmin,
  storageGet,
  storageSet,
  storageRemove,
  canAccess,
} from './data';

const palettes = [
  ['ocean', '#2563eb', '#06b6d4', '#f59e0b', '#101e3e', '#eaf1ff'],
  ['emerald', '#047857', '#34d399', '#f59e0b', '#102e29', '#e5f7ef'],
  ['royal', '#7c3aed', '#c084fc', '#fbbf24', '#281849', '#f1ebff'],
  ['sunset', '#c2410c', '#fb923c', '#a78bfa', '#3b241d', '#fff0e6'],
  ['rose', '#be123c', '#fb7185', '#2dd4bf', '#3b1626', '#fff0f3'],
  ['teal', '#0f766e', '#22d3ee', '#fb923c', '#102f34', '#e6f6f5'],
  ['slate', '#475569', '#94a3b8', '#38bdf8', '#1a2434', '#edf1f5'],
  ['gold', '#92400e', '#f59e0b', '#38bdf8', '#35291c', '#fdf3e2'],
  ['berry', '#9d174d', '#d946ef', '#fbbf24', '#33182e', '#fcebf4'],
  ['indigo', '#4338ca', '#818cf8', '#2dd4bf', '#1b204a', '#eeefff'],
];
import {
  Field,
  Dialog,
  Col,
  label,
  prettyDate,
  prettyTime,
  initials,
  opts,
  Badge,
  Button,
  Empty,
  FieldInput,
  FormDialog,
  Table,
} from './ui';

export default function App({
  initialPrincipal,
  initialSnapshot,
}: { initialPrincipal?: Principal; initialSnapshot?: Snapshot } = {}) {
  const [snapshot, setSnapshot] = useState<Snapshot>(() => initialSnapshot || emptySnapshot());
  const [authenticated, setAuthenticated] = useState(!!initialPrincipal);
  const [authChecking, setAuthChecking] = useState(!initialPrincipal);
  const [principal, setPrincipal] = useState<Principal>(
    () => initialPrincipal || { userId: '', tenantId: '', name: '', email: '', role: '' }
  );
  const [page, setPage] = useState(location.hash.slice(1) || 'dashboard');
  const [tab, setTab] = useState('');
  const [search, setSearch] = useState('');
  const [settings, setSettings] = useState(false);
  const [mobile, setMobile] = useState(false);
  const [theme, setTheme] = useState(
    () => storageGet('erp-theme') || storageGet('eos-theme') || 'ocean'
  );
  const [colorMode, setColorMode] = useState(() =>
    storageGet('erp-dark') === '1' ? 'dark' : storageGet('eos-color-mode') || 'light'
  );
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [toast, setToast] = useState<{ message: string; error?: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const drawerRef = useRef<HTMLElement>(null);
  const settingsTriggerRef = useRef<HTMLButtonElement>(null);
  const [projectId, setProjectId] = useState('all');
  const [taskDetail, setTaskDetail] = useState<Row | null>(null);
  const [employeeDetail, setEmployeeDetail] = useState<Row | null>(null);
  const [comment, setComment] = useState('');
  const [commentMentions, setCommentMentions] = useState<string[]>([]);
  const [loadErrors, setLoadErrors] = useState<string[]>([]);
  const [sessions, setSessions] = useState<Row[]>([]);
  const [mfa, setMfa] = useState<{ enabled: boolean; configured: boolean }>({
    enabled: false,
    configured: false,
  });
  const [mfaSecret, setMfaSecret] = useState<any>(null);
  const [bulkUsers, setBulkUsers] = useState<{ email: string; role: string }[] | null>(null);
  const platform = principal.role === 'PLATFORM_ADMIN';
  const writable =
    principal.role !== 'AUDITOR' &&
    (!principal.customRoleId || (principal.permissions || []).some((p) => !p.startsWith('GET ')));
  useEffect(() => {
    if (!settings) return;
    const previous = document.activeElement as HTMLElement;
    drawerRef.current?.querySelector<HTMLElement>('button')?.focus();
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setSettings(false);
        return;
      }
      if (event.key === 'Tab') {
        const elements = drawerRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]),input,select,textarea,a[href]'
        );
        if (!elements?.length) return;
        const first = elements[0],
          last = elements[elements.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('keydown', key);
      (previous || settingsTriggerRef.current)?.focus();
    };
  }, [settings]);
  useEffect(() => {
    const link = consumeAuthLink(window.location, window.history);
    if (link) publicFlow(link.flow, link.token);
  }, []);
  useEffect(() => {
    if (initialPrincipal) return;
    api('/me')
      .then(async (p) => {
        setPrincipal(p);
        setAuthenticated(true);
        await refresh(p);
      })
      .catch(() => setAuthenticated(false))
      .finally(() => setAuthChecking(false));
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.dataset.mode = colorMode;
    storageSet('erp-theme', theme);
    storageSet('erp-dark', colorMode === 'dark' ? '1' : '0');
  }, [theme, colorMode]);
  useEffect(() => {
    const listener = () => {
      setPage(location.hash.slice(1) || 'dashboard');
      setTab('');
      setSearch('');
      setTaskDetail(null);
    };
    window.addEventListener('hashchange', listener);
    return () => window.removeEventListener('hashchange', listener);
  }, []);
  useEffect(() => {
    if ((!taskDetail && !employeeDetail) || dialog) return;
    const old = document.activeElement as HTMLElement;
    const modal = document.querySelector<HTMLElement>('.modal');
    modal?.querySelector<HTMLElement>('button,input,select,textarea')?.focus();
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setTaskDetail(null);
        setEmployeeDetail(null);
      }
      if (e.key === 'Tab') {
        const elements = modal?.querySelectorAll<HTMLElement>(
          'button:not([disabled]),input,select,textarea'
        );
        if (!elements?.length) return;
        const first = elements[0],
          last = elements[elements.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('keydown', key);
      old?.focus();
    };
  }, [!!taskDetail, !!employeeDetail, !!dialog]);
  useEffect(() => {
    if (page === 'account' && authenticated) {
      api('/me/sessions')
        .then(setSessions)
        .catch((e) => notify(e.message, true));
      api('/me/mfa')
        .then(setMfa)
        .catch((e) => notify(e.message, true));
    }
  }, [page, authenticated]);
  useEffect(() => {
    if (!authenticated) setMfaSecret(null);
  }, [authenticated]);
  useEffect(() => {
    if (!authenticated) return;
    let pending = false;
    const timer = setInterval(
      async () => {
        if (pending) return;
        pending = true;
        try {
          await api('/auth/refresh', 'POST', {});
        } catch (e) {
          if ((e as any).status === 401) {
            setAuthenticated(false);
            setSnapshot(emptySnapshot());
            notify('Please sign in again to continue.', true);
          }
        } finally {
          pending = false;
        }
      },
      30 * 60 * 1000
    );
    return () => clearInterval(timer);
  }, [authenticated]);
  useEffect(() => {
    if (toast) {
      const t = setTimeout(() => setToast(null), 5500);
      return () => clearTimeout(t);
    }
  }, [toast]);
  const notify = (message: string, error = false) => setToast({ message, error });
  const go = (p: string) => {
    setPage(p);
    setTab('');
    setSearch('');
    setMobile(false);
    location.hash = p;
  };
  const refresh = async (_p = principal) => {
    setBusy(true);
    try {
      const authenticated = await api('/me');
      const loaded = await loadLive(authenticated);
      const scope = loaded.snapshot.myPermissions?.[0];
      setPrincipal({
        ...authenticated,
        customRoleId: scope?.customRoleId || '',
        customRoleName: scope?.name,
        permissions: scope?.permissions || [],
      });
      setSnapshot(loaded.snapshot);
      setTaskDetail((old) =>
        old ? loaded.snapshot.tasks.find((t) => t.id === old.id) || null : null
      );
      setEmployeeDetail((old) =>
        old ? loaded.snapshot.employees.find((e) => e.id === old.id) || null : null
      );
      setLoadErrors(loaded.errors);
    } catch (e) {
      if ((e as any).status === 401) {
        setAuthenticated(false);
        setSnapshot(emptySnapshot());
        go('dashboard');
        notify('Your session expired. Please sign in again.', true);
      } else notify((e as Error).message, true);
    } finally {
      setBusy(false);
    }
  };
  const run = async (fn: () => Promise<void> | void, message = 'Changes saved') => {
    try {
      await fn();
      notify(message);
    } catch (e) {
      notify((e as Error).message, true);
    }
  };
  const commit = async (kind: string, values: any, id?: string) => {
    await api('/org/records/' + kind + (id ? '/' + id : ''), id ? 'PUT' : 'POST', values);
    await refresh();
    notify(id ? 'Record updated' : 'Record created');
  };
  const mutate = async (action: string, payload: any = {}) => {
    const paths: Record<string, [string, string]> = {
      checkIn: ['/org/attendance/check-in', 'POST'],
      checkOut: ['/org/attendance/check-out', 'POST'],
      correction: ['/org/attendance/corrections', 'POST'],
      correctionDecision: [`/org/attendance/corrections/${payload.id}/decision`, 'POST'],
      leaveRequest: ['/org/leave/requests', 'POST'],
      leaveAdjustment: ['/org/leave/adjustments', 'POST'],
      leaveDecision: [`/org/leave/requests/${payload.id}/decision`, 'POST'],
      leaveCancel: [`/org/leave/requests/${payload.id}/cancel`, 'POST'],
      comment: [`/org/tasks/${payload.taskId}/comments`, 'POST'],
      invite: ['/org/invitations', 'POST'],
      userStatus: [`/org/users/${payload.id}/status`, 'PATCH'],
      userRole: [`/org/users/${payload.id}/role`, 'PATCH'],
      tenantCreate: ['/platform/tenants', 'POST'],
      tenantStatus: [`/platform/tenants/${payload.id}/status`, 'PATCH'],
      profile: ['/org/profile', 'PUT'],
    };
    const [path, method] = paths[action];
    await api(path, method, payload);
    await refresh();
    if (action === 'comment') {
      const comments = await api(`/org/tasks/${payload.taskId}/comments`);
      setSnapshot((s) => ({
        ...s,
        comments: comments.map((c: Row) => ({ ...c, taskId: payload.taskId })),
      }));
    }
  };
  const lookup = (table: string, id: string) =>
    snapshot[table]?.find((r) => r.id === id)?.name || 'Unassigned';
  const userOptions = snapshot.users
    .filter((u) => u.status === 'ACTIVE')
    .map((u) => ({ value: u.id, label: u.name }));
  const options = (kind: string) =>
    snapshot[kind]?.map((r) => ({ value: r.id, label: r.name || r.employeeCode || r.id })) || [];
  const projects = platform
    ? []
    : snapshot.projects.filter(
        (p) =>
          ['ADMIN', 'HR', 'MANAGER', 'AUDITOR'].includes(principal.role) ||
          p.memberIds?.includes(principal.userId)
      );
  const tasks = snapshot.tasks.filter(
    (t) =>
      projects.some((p) => p.id === t.projectId) &&
      (['ADMIN', 'HR', 'MANAGER', 'AUDITOR'].includes(principal.role) ||
        t.assigneeId === principal.userId)
  );
  const visibleEmployees = snapshot.employees;
  const ownBalances = snapshot.balances || [];
  const leaveRequests =
    canApprove(principal) || principal.role === 'AUDITOR'
      ? snapshot.leaveRequests
      : snapshot.leaveRequests.filter((l) => l.userId === principal.userId);
  const attendance =
    canApprove(principal) || principal.role === 'AUDITOR'
      ? snapshot.attendance
      : snapshot.attendance.filter((r) => r.userId === principal.userId);
  const openAttendance = snapshot.attendance.find((r) => r.userId === principal.userId && !r.outAt);
  const field = (
    key: string,
    title: string,
    type = 'text',
    required = true,
    extra: Partial<Field> = {}
  ): Field => ({ key, label: title, type, required, ...extra });
  function fields(kind: string): Field[] {
    switch (kind) {
      case 'employees':
        return [
          field('name', 'Full name'),
          field('employeeCode', 'Employee code', 'text', false, {
            hint: 'Leave blank to generate a unique employee code.',
          }),
          field('email', 'Work email', 'email'),
          field('userId', 'Linked user', 'select', false, { options: userOptions }),
          field('departmentId', 'Department', 'select', true, { options: options('departments') }),
          field('managerId', 'Reporting manager', 'select', false, {
            options: options('employees'),
          }),
          field('branchId', 'Office branch', 'select', false, { options: options('branches') }),
          field('teamId', 'Team', 'select', false, { options: options('teams') }),
          field('designationId', 'Designation', 'select', false, {
            options: options('designations'),
          }),
          field('employmentType', 'Employment type', 'select', true, {
            options: opts(['FULL_TIME', 'PART_TIME', 'CONTRACT']),
          }),
          field('status', 'Employment status', 'select', true, {
            options: opts(['ONBOARDING', 'ACTIVE', 'OFFBOARDED']),
          }),
          field('joinDate', 'Joining date', 'date'),
          field('phone', 'Phone', 'tel', false),
          field('emergencyContact', 'Emergency contact', 'text', false),
          field('effectiveDate', 'Change effective date', 'date', false),
          field('probationEndDate', 'Probation ends', 'date', false),
          field('contractEndDate', 'Contract ends', 'date', false),
          field('education', 'Education', 'repeater', false, {
            fields: [
              field('institution', 'Institution'),
              field('qualification', 'Qualification'),
              field('year', 'Completion year', 'number', true, { min: 1900, max: 2200 }),
            ],
            defaultRow: { year: new Date().getFullYear() },
          }),
          field('workHistory', 'Previous employment', 'repeater', false, {
            fields: [
              field('employer', 'Employer'),
              field('title', 'Job title'),
              field('fromDate', 'From', 'date'),
              field('toDate', 'Until', 'date', false),
            ],
            defaultRow: {},
          }),
          field('onboardingChecklist', 'Onboarding checklist', 'repeater', false, {
            fields: [
              field('title', 'Task'),
              field('status', 'Status', 'select', true, { options: opts(['PENDING', 'DONE']) }),
            ],
            defaultRow: { status: 'PENDING' },
          }),
          field('offboardingChecklist', 'Offboarding checklist', 'repeater', false, {
            fields: [
              field('title', 'Task'),
              field('status', 'Status', 'select', true, { options: opts(['PENDING', 'DONE']) }),
            ],
            defaultRow: { status: 'PENDING' },
          }),
        ];
      case 'departments':
        return [
          field('name', 'Department name'),
          field('description', 'Description', 'textarea', false),
          field('parentId', 'Parent department', 'select', false, {
            options: options('departments'),
          }),
        ];
      case 'branches':
        return [
          field('name', 'Branch name'),
          field('address', 'Address', 'textarea', false),
          field('timezone', 'Timezone', 'text', false),
          field('parentId', 'Parent branch', 'select', false, { options: options('branches') }),
        ];
      case 'teams':
        return [
          field('name', 'Team name'),
          field('description', 'Description', 'textarea', false),
          field('departmentId', 'Department', 'select', false, { options: options('departments') }),
        ];
      case 'shifts':
        return [
          field('name', 'Shift name'),
          field('startTime', 'Start time', 'time'),
          field('endTime', 'End time', 'time'),
          field('graceMinutes', 'Grace period in minutes', 'number', true, { min: 0, max: 120 }),
          field('workingDays', 'Working days', 'multi', true, {
            options: [
              'Sunday',
              'Monday',
              'Tuesday',
              'Wednesday',
              'Thursday',
              'Friday',
              'Saturday',
            ].map((label, i) => ({ value: String(i === 0 ? 7 : i), label })),
          }),
        ];
      case 'shiftAssignments':
        return [
          field('employeeId', 'Employee', 'select', true, { options: options('employees') }),
          field('shiftId', 'Shift', 'select', true, { options: options('shifts') }),
          field('effectiveFrom', 'Effective from', 'date'),
          field('effectiveTo', 'Effective until', 'date', false),
        ];
      case 'leavePolicies':
        return [
          field('name', 'Policy name'),
          field('type', 'Leave type code'),
          field('year', 'Policy year', 'number', false, { min: 2000, max: 2200 }),
          field('annualDays', 'Annual allowance in days', 'number', true, { min: 0, max: 366 }),
          field('carryForwardDays', 'Carry-forward limit', 'number', true, { min: 0, max: 366 }),
          field('accrualMode', 'Accrual schedule', 'select', true, {
            options: opts(['ANNUAL', 'MONTHLY']),
          }),
          field('approvalLevels', 'Approval levels', 'select', true, {
            options: [
              { value: '1', label: '1 | Manager approval' },
              { value: '2', label: '2 | Manager then HR' },
            ],
          }),
          field('escalationDays', 'Escalate after days (0 disables)', 'number', true, {
            min: 0,
            max: 365,
          }),
          field('holidaysText', 'Holiday dates', 'textarea', false, {
            hint: 'One ISO date per line, e.g. 2026-12-25.',
          }),
          field('delegations', 'Approval delegations', 'repeater', false, {
            fields: [
              field('fromUserId', 'Original approver', 'select', true, {
                options: snapshot.users
                  .filter(
                    (u) => ['ADMIN', 'HR', 'MANAGER'].includes(u.role) && u.status === 'ACTIVE'
                  )
                  .map((u) => ({ value: u.id, label: u.name })),
              }),
              field('toUserId', 'Delegate approver', 'select', true, {
                options: snapshot.users
                  .filter(
                    (u) => ['ADMIN', 'HR', 'MANAGER'].includes(u.role) && u.status === 'ACTIVE'
                  )
                  .map((u) => ({ value: u.id, label: u.name })),
              }),
              field('startDate', 'From', 'date'),
              field('endDate', 'Until', 'date'),
            ],
            defaultRow: { startDate: today(), endDate: today() },
          }),
          field('workingDays', 'Working days', 'multi', true, {
            options: [
              'Sunday',
              'Monday',
              'Tuesday',
              'Wednesday',
              'Thursday',
              'Friday',
              'Saturday',
            ].map((label, i) => ({ value: String(i === 0 ? 7 : i), label })),
            hint: 'Hold Ctrl or Cmd to select multiple days.',
          }),
        ];
      case 'projects':
        return [
          field('name', 'Project name'),
          field('description', 'Description', 'textarea', false),
          field('ownerId', 'Project owner', 'select', true, { options: userOptions }),
          field('memberIds', 'Project members', 'multi', true, {
            options: userOptions,
            hint: 'Hold Ctrl or Cmd to select multiple members.',
          }),
          field('startDate', 'Planned start', 'date', false),
          field('dueDate', 'Target date', 'date', false),
          field('status', 'Status', 'select', true, { options: opts(['ACTIVE', 'ARCHIVED']) }),
        ];
      case 'sprints':
        return [
          field('name', 'Sprint name'),
          field('projectId', 'Project', 'select', true, { options: options('projects') }),
          field('startDate', 'Start date', 'date'),
          field('endDate', 'End date', 'date'),
          field('status', 'Status', 'select', true, {
            options: opts(['PLANNED', 'ACTIVE', 'CLOSED']),
          }),
        ];
      case 'milestones':
        return [
          field('name', 'Milestone name'),
          field('projectId', 'Project', 'select', true, { options: options('projects') }),
          field('dueDate', 'Due date', 'date'),
          field('status', 'Status', 'select', true, { options: opts(['OPEN', 'COMPLETE']) }),
        ];
      case 'tasks':
        return [
          field('name', 'Task title'),
          field('description', 'Description', 'textarea', false),
          field('projectId', 'Project', 'select', true, {
            options: projects
              .filter((p) => p.status !== 'ARCHIVED')
              .map((p) => ({ value: p.id, label: p.name })),
          }),
          field('assigneeId', 'Assignee', 'select', false, { options: userOptions }),
          field('sprintId', 'Sprint', 'select', false, { options: options('sprints') }),
          field('status', 'Status', 'select', true, {
            options: opts(['TODO', 'IN_PROGRESS', 'REVIEW', 'DONE']),
          }),
          field('priority', 'Priority', 'select', true, {
            options: opts(['LOW', 'MEDIUM', 'HIGH']),
          }),
          field('startDate', 'Planned start', 'date', false),
          field('dueDate', 'Due date', 'date', false),
          field('labels', 'Labels, separated by commas', 'text', false),
          field('parentId', 'Parent task', 'select', false, { options: options('tasks') }),
          field('dependencyIds', 'Depends on', 'multi', false, {
            options: options('tasks'),
            hint: 'Select tasks in the same project. Cyclic dependencies are rejected.',
          }),
        ];
      case 'timeEntries':
        return [
          field('taskId', 'Task', 'select', true, {
            options: tasks.map((t) => ({ value: t.id, label: t.name })),
          }),
          field('date', 'Work date', 'date'),
          field('hours', 'Hours', 'number', true, { min: 0.1, max: 24 }),
          field('note', 'Work description', 'textarea', false),
        ];
      default:
        return [field('name', 'Name'), field('description', 'Description', 'textarea', false)];
    }
  }
  const editRecord = (kind: string, row?: Row, defaults: any = {}) => {
    let values: any = {
      status: 'ACTIVE',
      employmentType: 'FULL_TIME',
      joinDate: today(),
      startDate: today(),
      endDate: today(),
      effectiveFrom: today(),
      date: today(),
      graceMinutes: 15,
      annualDays: 20,
      carryForwardDays: 0,
      accrualMode: 'ANNUAL',
      approvalLevels: '1',
      escalationDays: 0,
      workingDays: ['1', '2', '3', '4', '5'],
      priority: 'MEDIUM',
      memberIds: [],
      dependencyIds: [],
      ...defaults,
      ...row,
    };
    if (kind === 'tasks' && !row) values.status = 'TODO';
    if (kind === 'milestones' && !row) values.status = 'OPEN';
    if (kind === 'leavePolicies' && !row) values.year = new Date().getFullYear();
    if (row?.workingDays) values.workingDays = row.workingDays.map(String);
    if (kind === 'leavePolicies') {
      values.holidaysText = (row?.holidays || []).join('\n');
      values.delegations = row?.delegations || [];
      values.approvalLevels = String(row?.approvalLevels || 1);
    }
    setDialog({
      title:
        (row ? 'Edit ' : 'Create ') +
        ({
          shiftAssignments: 'shift assignment',
          leavePolicies: 'leave policy',
          timeEntries: 'time entry',
          employees: 'employee',
          tasks: 'task',
          sprints: 'sprint',
          milestones: 'milestone',
          projects: 'project',
        }[kind] || kind.replace(/s$/, '')),
      description: 'Changes are recorded in the audit history.',
      fields: fields(kind),
      values,
      onSubmit: async (v) => {
        if (v.workingDays) v.workingDays = v.workingDays.map(Number);
        if (kind === 'leavePolicies') {
          v.approvalLevels = Number(v.approvalLevels);
          v.holidays = String(v.holidaysText || '')
            .split(/[\s,]+/)
            .filter(Boolean);
          if (v.holidays.some((d: string) => !/^\d{4}-\d{2}-\d{2}$/.test(d)))
            throw new Error('Use ISO dates such as 2026-12-25 for holidays.');
          if (!Array.isArray(v.delegations)) v.delegations = [];
          delete v.holidaysText;
        }
        await commit(kind, v, row?.id);
      },
    });
  };
  const confirm = (title: string, description: string, action: () => Promise<void>) =>
    setDialog({
      title,
      description,
      fields: [],
      submitLabel: 'Confirm',
      onSubmit: async () => {
        await action();
        notify('Action completed');
      },
    });
  const invite = () =>
    setDialog({
      title: 'Invite a teammate',
      description: 'An expiring activation link will be sent by the configured mail provider.',
      fields: [
        field('email', 'Email address', 'email'),
        field('role', 'Role', 'select', true, { options: opts(roles) }),
      ],
      values: { role: 'MEMBER' },
      submitLabel: 'Send invitation',
      onSubmit: async (v) => {
        await mutate('invite', v);
        notify('Invitation queued for delivery');
      },
    });
  const bulkInvite = () =>
    setDialog({
      title: 'Bulk invite users',
      description:
        'Paste CSV with email,role columns. The server validates all rows before any invitations are created. Maximum 200 rows.',
      fields: [
        field('csv', 'Email and role CSV', 'textarea', true, {
          hint: 'Header: email,role. Roles: ' + roles.join(', '),
        }),
      ],
      values: { csv: 'email,role\n' },
      submitLabel: 'Validate import',
      onSubmit: async (v) => {
        const rows = parseUserImport(v.csv);
        const preview = await api('/org/user-import/preview', 'POST', { rows });
        if (!preview.valid)
          throw new Error(
            (preview.errors || []).map((e: any) => 'Row ' + e.row + ': ' + e.message).join(' | ') ||
              'Import contains invalid rows.'
          );
        setBulkUsers(rows);
        notify('All rows are valid. Review and confirm the invitation batch.');
      },
    });
  const createTenant = () =>
    setDialog({
      title: 'Create a company',
      description: 'The initial owner will receive an activation invitation.',
      fields: [
        field('name', 'Company name'),
        field('slug', 'Unique slug', 'text', true, {
          hint: 'Lowercase letters, digits and hyphens.',
        }),
        field('ownerName', 'Owner name'),
        field('ownerEmail', 'Owner email', 'email'),
        field('timezone', 'Timezone'),
      ],
      values: { timezone: 'Asia/Kolkata' },
      onSubmit: async (v) => {
        if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(v.slug))
          throw new Error('Use lowercase letters, digits and single hyphens for the slug.');
        await mutate('tenantCreate', v);
        notify('Company created');
      },
    });
  const requestLeave = () =>
    setDialog({
      title: 'Request time off',
      description:
        'Working days follow the selected policy. Balance is deducted only after approval.',
      fields: [
        field('type', 'Leave type', 'select', true, {
          options: snapshot.leavePolicies.map((p) => ({ value: p.type || p.name, label: p.name })),
        }),
        field('startDate', 'From', 'date'),
        field('endDate', 'Until', 'date'),
        field('reason', 'Reason', 'textarea'),
      ],
      values: {
        type: snapshot.leavePolicies[0]?.type || snapshot.leavePolicies[0]?.name,
        startDate: today(),
        endDate: today(),
      },
      submitLabel: 'Submit request',
      onSubmit: async (v) => {
        await mutate('leaveRequest', v);
        notify('Leave request submitted');
      },
    });
  const adjustLeave = () =>
    setDialog({
      title: 'Adjust leave entitlement',
      description:
        'A positive adjustment adds days; a negative adjustment removes days. Every change needs a reason and remains in the balance history.',
      fields: [
        field('userId', 'Employee account', 'select', true, { options: userOptions }),
        field('type', 'Leave type', 'select', true, {
          options: snapshot.leavePolicies.map((p) => ({ value: p.type || p.name, label: p.name })),
        }),
        field('year', 'Entitlement year', 'number', true, { min: 2000, max: 2200 }),
        field('days', 'Adjustment in days', 'number', true, { min: -366, max: 366 }),
        field('reason', 'Reason', 'textarea'),
      ],
      values: { year: new Date().getFullYear(), days: 1, type: snapshot.leavePolicies[0]?.type },
      submitLabel: 'Record adjustment',
      onSubmit: async (v) => {
        await mutate('leaveAdjustment', v);
        notify('Leave adjustment recorded');
      },
    });
  const requestCorrection = (row?: Row) =>
    setDialog({
      title: 'Request attendance correction',
      description: 'Your original time entry is preserved and a different supervisor must decide.',
      fields: [
        field('attendanceId', 'Attendance entry', 'select', true, {
          options: snapshot.attendance
            .filter((r) => r.userId === principal.userId)
            .map((r) => ({ value: r.id, label: prettyDate(r.inAt) + ' · ' + prettyTime(r.inAt) })),
        }),
        field('inAt', 'Corrected check-in', 'datetime-local'),
        field('outAt', 'Corrected check-out', 'datetime-local', false),
        field('reason', 'Reason for correction', 'textarea'),
      ],
      values: {
        attendanceId: row?.id,
        inAt: row?.inAt
          ? new Date(new Date(row.inAt).getTime() - new Date(row.inAt).getTimezoneOffset() * 60000)
              .toISOString()
              .slice(0, 16)
          : '',
        outAt: '',
      },
      submitLabel: 'Submit for review',
      onSubmit: async (v) => {
        await mutate('correction', {
          ...v,
          inAt: new Date(v.inAt).toISOString(),
          outAt: v.outAt ? new Date(v.outAt).toISOString() : null,
        });
        notify('Correction submitted');
      },
    });
  const publicFlow = (flow: string, token = '') => {
    const forms: Record<string, Dialog> = {
      register: {
        title: 'Register your company',
        description: 'Verify your email before the company and its owner account are activated.',
        fields: [
          field('companyName', 'Company name'),
          field('ownerName', 'Your full name'),
          field('email', 'Work email', 'email'),
          field('password', 'Password', 'password', true, { hint: 'At least 12 characters.' }),
          field('slug', 'Unique company slug', 'text', true, {
            hint: 'Lowercase letters, digits and hyphens.',
          }),
          field('timezone', 'Company timezone'),
        ],
        values: { timezone: 'Asia/Kolkata' },
        submitLabel: 'Send verification email',
        onSubmit: async (v) => {
          await api('/auth/register-company', 'POST', v);
          v.password = '';
          notify('Check your email to verify and activate your company.');
        },
      },
      'verify-company': {
        title: 'Verify your company email',
        description:
          'Confirm your single-use verification link to activate the company owner account.',
        fields: token ? [] : [field('token', 'Email verification token')],
        values: { token },
        submitLabel: 'Verify company email',
        onSubmit: async (v) => {
          await api('/auth/verify-company', 'POST', v);
          v.token = '';
          notify('Company email verified. Sign in to continue.');
        },
      },
      forgot: {
        title: 'Reset your password',
        description:
          'If an account exists, a reset message will be queued. For local development, inspect the mail outbox.',
        fields: [field('email', 'Email address', 'email')],
        submitLabel: 'Request reset',
        onSubmit: async (v) => {
          await api('/auth/forgot-password', 'POST', v);
          notify('If that account exists, a reset message has been queued.');
        },
      },
      reset: {
        title: 'Choose a new password',
        description: token
          ? 'Choose a new password for your account. This reset link can only be used once.'
          : 'Use the single-use token from your reset email.',
        values: { token },
        fields: [
          ...(!token ? [field('token', 'Reset token')] : []),
          field('password', 'New password', 'password'),
        ],
        submitLabel: 'Set password',
        onSubmit: async (v) => {
          await api('/auth/reset-password', 'POST', v);
          v.token = '';
          setAuthenticated(false);
          setSnapshot(emptySnapshot());
          go('dashboard');
          notify('Password updated. Sign in again to continue.');
        },
      },
      accept: {
        title: 'Accept your invitation',
        description: token
          ? 'Complete your invitation. New accounts choose a password; existing accounts must use their current password.'
          : 'Use the single-use token from your invitation email. Existing accounts must enter their current password.',
        values: { token },
        fields: [
          ...(!token ? [field('token', 'Invitation token')] : []),
          field('name', 'Full name'),
          field('password', 'Password', 'password', true, {
            hint: 'New account: choose a password. Existing account: enter your current password.',
          }),
        ],
        submitLabel: 'Activate account',
        onSubmit: async (v) => {
          await api('/invitations/accept', 'POST', v);
          v.token = '';
          notify('Account activated. Sign in to continue.');
        },
      },
    };
    setDialog(forms[flow]);
  };
  const exportRows = (rows: Row[], name: string) => {
    if (!rows.length) {
      notify('No records to export', true);
      return;
    }
    const keys = Object.keys(rows[0]);
    const csv = [
      keys,
      ...rows.map((r) =>
        keys.map((k) => (typeof r[k] === 'object' ? JSON.stringify(r[k]) : (r[k] ?? '')))
      ),
    ]
      .map((r) => r.map((v) => '"' + String(v).replaceAll('"', '""') + '"').join(','))
      .join('\r\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8;' }));
    a.download = `enterpriseos-${name}-live-${today()}.csv`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    notify('Export downloaded');
  };
  const person = (name: string, secondary?: string) => (
    <div className="person">
      <span className="avatar small">{initials(name)}</span>
      <span>
        <strong>{name}</strong>
        {secondary && <small>{secondary}</small>}
      </span>
    </div>
  );
  const nameCol: Col = { key: 'name', title: 'Name', render: (r) => <strong>{r.name}</strong> };
  const statusCol: Col = {
    key: 'status',
    title: 'Status',
    render: (r) => <Badge value={r.status} />,
  };
  const tabs = (values: [string, string][], fallback: string) => (
    <div className="tabs" role="tablist">
      {values.map(([id, name]) => (
        <button
          role="tab"
          aria-selected={(tab || fallback) === id}
          key={id}
          className={(tab || fallback) === id ? 'active' : ''}
          onClick={() => setTab(id)}
        >
          {name}
        </button>
      ))}
    </div>
  );
  const viewChanges = async (kind: string, id: string) => {
    try {
      const history = await api(`/org/records/${kind}/${id}/history`);
      setDialog({
        title: 'Record change history',
        description: 'Server-recorded revisions. Only authorized fields are included.',
        fields: [],
        submitLabel: 'Done',
        content: (
          <Table
            rows={history}
            columns={[
              { key: 'version', title: 'Version' },
              { key: 'actorId', title: 'Changed by', render: (r) => lookup('users', r.actorId) },
              { key: 'action', title: 'Action' },
              {
                key: 'changedAt',
                title: 'Recorded',
                render: (r) => prettyDate(r.changedAt) + ' ' + prettyTime(r.changedAt),
              },
              {
                key: 'changes',
                title: 'Changes',
                render: (r) =>
                  Object.keys(r.after || {})
                    .filter(
                      (k) =>
                        !['id', 'version', 'history', 'comments'].includes(k) &&
                        JSON.stringify(r.before?.[k]) !== JSON.stringify(r.after?.[k])
                    )
                    .map(
                      (k) =>
                        label(k.replace(/([a-z])([A-Z])/g, '$1 $2')) +
                        ': ' +
                        String(r.before?.[k] ?? 'Not set') +
                        ' -> ' +
                        String(r.after?.[k] ?? 'Not set')
                    )
                    .join('; '),
              },
            ]}
          />
        ),
        onSubmit: () => {},
      });
    } catch (e) {
      notify((e as Error).message, true);
    }
  };
  const submitTime = async (r: Row) => {
    await api('/org/time-entries/' + r.id + '/submit', 'POST', {});
    await refresh();
  };
  const reviewTime = (r: Row) =>
    setDialog({
      title: 'Review timesheet entry',
      fields: [
        field('decision', 'Decision', 'select', true, { options: opts(['APPROVED', 'REJECTED']) }),
        field('reason', 'Review reason', 'textarea'),
      ],
      values: { decision: 'APPROVED' },
      onSubmit: async (v) => {
        await api('/org/time-entries/' + r.id + '/decision', 'POST', v);
        await refresh();
        notify('Time entry reviewed');
      },
    });
  const resource = (
    kind: string,
    columns: Col[],
    defaults: any = {},
    rows = snapshot[kind],
    add = true
  ) => (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>{label(kind.replace(/([a-z])([A-Z])/g, '$1 $2'))}</h2>
          <p>{rows.length} records · Live workspace</p>
        </div>
        {add && writable && canAccess(principal, 'POST', '/org/records/' + kind) && (
          <Button kind="primary" icon="plus" onClick={() => editRecord(kind, undefined, defaults)}>
            Add{' '}
            {kind === 'employees'
              ? 'employee'
              : kind === 'shiftAssignments'
                ? 'assignment'
                : kind === 'leavePolicies'
                  ? 'policy'
                  : 'record'}
          </Button>
        )}
      </div>
      <Table
        rows={rows}
        columns={columns}
        search={search}
        actions={
          writable && add
            ? (r) => (
                <>
                  {(kind !== 'timeEntries' || !['PENDING', 'APPROVED'].includes(r.status)) &&
                    canAccess(principal, 'PUT', '/org/records/' + kind + '/' + r.id) && (
                      <Button kind="ghost" icon="edit" onClick={() => editRecord(kind, r)}>
                        Edit
                      </Button>
                    )}
                  {kind === 'timeEntries' &&
                    r.userId === principal.userId &&
                    (r.status || 'DRAFT') === 'DRAFT' && (
                      <Button
                        kind="primary"
                        onClick={() => run(() => submitTime(r), 'Time entry submitted')}
                      >
                        Submit
                      </Button>
                    )}
                  {kind === 'timeEntries' &&
                    r.status === 'PENDING' &&
                    ['ADMIN', 'MANAGER'].includes(principal.role) &&
                    r.userId !== principal.userId && (
                      <Button onClick={() => reviewTime(r)}>Review</Button>
                    )}
                </>
              )
            : undefined
        }
      />
    </section>
  );
  const showTask = async (task: Row) => {
    setTaskDetail(task);
    setComment('');
    setCommentMentions([]);
    try {
      const rows = await api(`/org/tasks/${task.id}/comments`);
      setSnapshot((s) => ({ ...s, comments: rows.map((c: Row) => ({ ...c, taskId: task.id })) }));
    } catch (e) {
      notify((e as Error).message, true);
    }
  };
  const taskStatus = async (task: Row, status: string) => {
    await run(() => commit('tasks', { ...task, status }, task.id), 'Task moved');
  };
  const pageReads: Record<string, string[]> = {
    employees: ['/org/records/employees'],
    attendance: ['/org/attendance'],
    leave: ['/org/leave/requests', '/org/leave/balances'],
    requests: [
      '/org/workforce/requests/exits',
      '/org/workforce/requests/encashments',
      '/org/workforce/requests/compOff',
    ],
    projects: ['/org/records/projects', '/org/records/tasks'],
    organization: ['/org/profile', '/org/records/departments'],
    audit: ['/org/audit-events'],
    crm: ['/org/business/records/leads', '/org/business/records/accounts'],
    inventory: ['/org/business/inventory/stock', '/org/business/records/items'],
    procurement: ['/org/business/records/purchaseOrders', '/org/business/records/supplierBills'],
    finance: ['/org/business/records/invoices', '/org/business/records/expenses'],
    payroll: ['/org/business/records/payrollRuns', '/org/business/payroll/payslips'],
    workflows: ['/org/workflows/instances', '/org/workflows/definitions'],
    documents: ['/org/documents'],
    notifications: [
      '/org/notifications',
      '/org/notifications/mentions',
      '/org/notifications/digest',
    ],
    reports: ['/org/reports', '/org/reports/exports', '/org/reports/definitions'],
  };
  const canViewPage = (name: string) =>
    !pageReads[name] || pageReads[name].some((path) => canAccess(principal, 'GET', path));
  const commercialNav = [
    ...(['ADMIN', 'MANAGER', 'SALES', 'AUDITOR'].includes(principal.role)
      ? [['crm', 'Customers & sales', 'briefcase']]
      : []),
    ...(['ADMIN', 'MANAGER', 'PROCUREMENT', 'AUDITOR'].includes(principal.role)
      ? [
          ['inventory', 'Inventory', 'building'],
          ['procurement', 'Procurement', 'file'],
        ]
      : principal.role === 'FINANCE'
        ? [['procurement', 'Supplier bills', 'file']]
        : []),
    ['finance', 'Finance & expenses', 'file'],
    ['payroll', 'Payroll & payslips', 'users'],
  ];
  const collaborationNav = [
    ['workflows', 'Approvals', 'shield'],
    ['documents', 'Documents', 'file'],
    ['notifications', 'Notifications', 'bell'],
    ['reports', 'Reports', 'activity'],
    ...(isAdmin(principal)
      ? [
          ['integrations', 'Integrations', 'link'],
          ['subscriptions', 'Plan & usage', 'briefcase'],
        ]
      : []),
  ];
  const rawNavGroups = platform
    ? [
        {
          title: 'PLATFORM',
          items: [
            ['dashboard', 'Overview', 'dashboard'],
            ['tenants', 'Companies', 'building'],
            ['subscriptions', 'Plans & subscriptions', 'briefcase'],
            ['operations', 'Operations & governance', 'activity'],
            ['account', 'My account', 'users'],
            ['settings', 'Appearance', 'settings'],
          ],
        },
      ]
    : [
        {
          title: 'WORKSPACE',
          items: [
            ['dashboard', 'Overview', 'dashboard'],
            ['employees', 'People', 'users'],
            ['attendance', 'Attendance', 'clock'],
            ['leave', 'Time off', 'leaf'],
            ['requests', 'Employee requests', 'file'],
            ['projects', 'Projects', 'board'],
          ],
        },
        { title: 'BUSINESS OPERATIONS', items: commercialNav },
        { title: 'COLLABORATION', items: collaborationNav },
        {
          title: 'ADMINISTRATION',
          items: [
            ...(isAdmin(principal) ? [['users', 'Users & access', 'shield']] : []),
            ...(canManage(principal) ? [['organization', 'Organization', 'building']] : []),
            ...(['ADMIN', 'AUDITOR'].includes(principal.role)
              ? [['audit', 'Audit history', 'activity']]
              : []),
            ['account', 'My account', 'users'],
            ['settings', 'Appearance', 'settings'],
          ],
        },
      ];
  const navGroups = rawNavGroups
    .map((group) => ({ ...group, items: group.items.filter(([name]) => canViewPage(name)) }))
    .filter((group) => group.items.length);
  const heading: Record<string, [string, string]> = {
    dashboard: ['Workspace overview', 'A little clarity for your workday.'],
    tenants: ['Companies', 'Provision and manage your company tenants.'],
    employees: ['People', 'A connected team, with everything in the right place.'],
    attendance: ['Attendance', 'Time, schedules and a clear record of every workday.'],
    leave: ['Time off', 'Make room for rest. Keep your team in sync.'],
    projects: ['Projects', 'Good work starts with a clear next step.'],
    organization: ['Organization', 'The foundations of your company workspace.'],
    users: ['Users & access', 'The right people, with the right permissions.'],
    audit: ['Audit history', 'A traceable history of important workspace changes.'],
    account: ['My account', 'Your identity, password and active sessions.'],
    requests: [
      'Employee requests',
      'Exit planning, leave encashment eligibility and compensatory time.',
    ],
    crm: ['Customers & sales', 'Turn conversations into lasting customer relationships.'],
    inventory: ['Inventory', 'A reliable stock ledger across your warehouses.'],
    procurement: ['Procurement', 'From purchase request to verified goods receipt.'],
    finance: ['Finance & expenses', 'Trace invoices, payments, expenses and budgets.'],
    payroll: ['Payroll & payslips', 'Effective-dated compensation and controlled payroll.'],
    workflows: ['Approvals', 'Versioned workflows and clear decision history.'],
    documents: ['Documents', 'Controlled files with access and review history.'],
    notifications: ['Notifications', 'Your company updates and action reminders.'],
    reports: ['Reports', 'Permission-aware summaries and exports.'],
    integrations: ['Integrations', 'Signed webhooks and observable delivery.'],
    subscriptions: ['Plans & usage', 'Subscription entitlements and company usage.'],
    operations: ['Operations & governance', 'Release, recovery and operational evidence.'],
    settings: ['Make it yours', 'A workspace that feels right for you.'],
  };
  function Dashboard() {
    const activeUsers = canAccess(principal, 'GET', '/org/users')
        ? snapshot.users.filter((u) => u.status === 'ACTIVE').length
        : '?',
      finished = tasks.filter((t) => t.status === 'DONE').length,
      pending = leaveRequests.filter((l) => l.status === 'PENDING').length;
    return (
      <>
        <section className="hero">
          <div className="hero-copy">
            <span className="eyebrow">YOUR WORKSPACE, CONNECTED</span>
            <h2>
              Welcome back, {principal.name.split(' ')[0]} <span aria-hidden="true">✦</span>
            </h2>
            <p>
              {platform
                ? 'A clear view of your companies and platform.'
                : 'Your people, projects and everyday work. All in one place.'}
            </p>
            <Button icon="arrow" onClick={() => (platform ? createTenant() : go('projects'))}>
              {platform ? 'Create a company' : 'Explore your projects'}
            </Button>
          </div>
          <div className="hero-illustration" aria-hidden="true">
            <div className="orbit one" />
            <div className="orbit two" />
            <div className="hero-mark">
              <Icon name="dashboard" size={46} />
            </div>
            <span className="floating-chip">
              <Icon name="check" size={15} /> Everything in its place
            </span>
          </div>
        </section>
        <div className="stats-grid">
          {(platform
            ? [
                [snapshot.tenants.length, 'Companies', 'building', 'Registered on your platform'],
                [
                  snapshot.tenants.filter((t) => t.status === 'ACTIVE').length,
                  'Active tenants',
                  'check',
                  'Ready to operate',
                ],
                [
                  snapshot.tenants.filter((t) => t.status === 'SUSPENDED').length,
                  'Suspended',
                  'shield',
                  'Access restricted',
                ],
                ['1-5', 'Delivery phases', 'briefcase', 'Complete enterprise workspace'],
              ]
            : [
                [
                  activeUsers,
                  'Active teammates',
                  'users',
                  snapshot.employees.length + ' employee profiles',
                ],
                [
                  canAccess(principal, 'GET', '/org/records/projects')
                    ? projects.filter((p) => p.status === 'ACTIVE').length
                    : '?',
                  'Active projects',
                  'briefcase',
                  'Move good work forward',
                ],
                [
                  canAccess(principal, 'GET', '/org/leave/requests') ? pending : '?',
                  'Pending leave requests',
                  'leaf',
                  'Awaiting a decision',
                ],
                [
                  canAccess(principal, 'GET', '/org/records/tasks')
                    ? finished + '/' + tasks.length
                    : '?',
                  'Tasks completed',
                  'check',
                  tasks.length
                    ? Math.round((finished / tasks.length) * 100) + '% of visible work'
                    : 'Your next chapter starts here',
                ],
              ]
          ).map(([value, title, icon, detail]) => (
            <div className="stat-card" key={String(title)}>
              <div className="stat-top">
                <span>{title}</span>
                <span className="stat-icon">
                  <Icon name={String(icon)} />
                </span>
              </div>
              <strong className="stat-value">{value}</strong>
              <small>{detail}</small>
            </div>
          ))}
        </div>
        {platform ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Company directory</h2>
                <p>Tenant lifecycle at a glance</p>
              </div>
              <Button onClick={() => go('tenants')}>
                View all <Icon name="arrow" size={16} />
              </Button>
            </div>
            <Table
              rows={snapshot.tenants}
              columns={[nameCol, { key: 'slug', title: 'Workspace slug' }, statusCol]}
            />
          </section>
        ) : (
          <>
            <div className="content-grid dashboard-grid">
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Project pulse</h2>
                    <p>Progress across your active projects</p>
                  </div>
                  <Button kind="ghost" onClick={() => go('projects')}>
                    View projects <Icon name="arrow" size={16} />
                  </Button>
                </div>
                <div className="project-pulse">
                  {projects.slice(0, 4).map((p, i) => {
                    const ts = tasks.filter((t) => t.projectId === p.id),
                      done = ts.filter((t) => t.status === 'DONE').length,
                      progress = ts.length ? Math.round((done / ts.length) * 100) : 0;
                    return (
                      <div className="pulse-row" key={p.id}>
                        <span className="project-icon">{['✧', '◈', '◎', '▧'][i % 4]}</span>
                        <div className="pulse-copy">
                          <div>
                            <strong>{p.name}</strong>
                            <span>{progress}%</span>
                          </div>
                          <div className="progress">
                            <span style={{ width: progress + '%' }} />
                          </div>
                          <small>
                            {done} of {ts.length} tasks complete · {prettyDate(p.dueDate)}
                          </small>
                        </div>
                      </div>
                    );
                  })}
                  {!projects.length && <Empty text="Create a project to see its progress here." />}
                </div>
              </section>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Your day, at a glance</h2>
                    <p>
                      {new Date().toLocaleDateString(undefined, {
                        weekday: 'long',
                        month: 'long',
                        day: 'numeric',
                      })}
                    </p>
                  </div>
                  <span className="stat-icon">
                    <Icon name="sun" />
                  </span>
                </div>
                <div className="today-card">
                  <div className="today-time">
                    {openAttendance ? 'You’re checked in' : 'Ready for a good day?'}
                  </div>
                  <p>
                    {openAttendance
                      ? 'Since ' + prettyTime(openAttendance.inAt)
                      : 'Keep a reliable record of your working hours.'}
                  </p>
                  <Button
                    kind="primary"
                    icon="clock"
                    disabled={!writable}
                    onClick={() =>
                      run(
                        () => mutate(openAttendance ? 'checkOut' : 'checkIn'),
                        openAttendance ? 'Checked out' : 'Checked in'
                      )
                    }
                  >
                    {openAttendance ? 'Check out' : 'Check in'}
                  </Button>
                </div>
                <div className="balance-mini">
                  <span>Annual leave available</span>
                  <strong>
                    {ownBalances.find((b) => b.type === 'ANNUAL')?.remaining ?? 0}{' '}
                    <small>days</small>
                  </strong>
                  <Button kind="ghost" onClick={() => go('leave')}>
                    Plan time off <Icon name="arrow" size={15} />
                  </Button>
                </div>
              </section>
            </div>
            <div className="content-grid dashboard-grid">
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>On your radar</h2>
                    <p>Upcoming tasks that need your attention</p>
                  </div>
                  <Button kind="ghost" onClick={() => go('projects')}>
                    View all
                  </Button>
                </div>
                <Table
                  rows={tasks.filter((t) => t.status !== 'DONE').slice(0, 5)}
                  columns={[
                    {
                      key: 'name',
                      title: 'Task',
                      render: (t) => (
                        <button className="text-button" onClick={() => showTask(t)}>
                          {t.name}
                        </button>
                      ),
                    },
                    {
                      key: 'projectId',
                      title: 'Project',
                      render: (t) => lookup('projects', t.projectId),
                    },
                    statusCol,
                    { key: 'dueDate', title: 'Due', render: (t) => prettyDate(t.dueDate) },
                  ]}
                />
              </section>
              <section className="panel">
                <div className="panel-heading">
                  <div>
                    <h2>Recent activity</h2>
                    <p>The latest in your workspace</p>
                  </div>
                  <Icon name="activity" />
                </div>
                <div className="activity-list">
                  {snapshot.audit.slice(0, 5).map((a) => (
                    <div className="activity-item" key={a.id}>
                      <span className="activity-dot" />
                      <div>
                        <strong>{a.actorName || a.actor_name || 'Team member'}</strong>
                        <p>
                          {label(a.action)} · {label(a.entity)}
                        </p>
                        <small>{prettyDate(a.createdAt || a.created_at)}</small>
                      </div>
                    </div>
                  ))}
                  {!snapshot.audit.length && (
                    <Empty title="A fresh start" text="Your activity will appear here." />
                  )}
                </div>
              </section>
            </div>
          </>
        )}
        {!platform && canAccess(principal, 'GET', '/org/dashboard/widgets') && (
          <DashboardWidgets principal={principal} setDialog={setDialog} notify={notify} />
        )}
        <div className="scope-note">
          <Icon name="shield" size={16} />
          <span>Enterprise workspace · Foundation, people and project operations</span>
          <span>Live data | server authorization</span>
        </div>
      </>
    );
  }

  function Users() {
    const current = tab || 'directory';
    return (
      <>
        {bulkUsers && (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Validated invitation batch</h2>
                <p>
                  {bulkUsers.length} users are ready to invite. All rows are committed together.
                </p>
              </div>
              <div className="row-actions">
                <Button onClick={() => setBulkUsers(null)}>Cancel</Button>
                <Button
                  kind="primary"
                  onClick={() =>
                    run(async () => {
                      await api('/org/user-import', 'POST', { rows: bulkUsers });
                      setBulkUsers(null);
                      await refresh();
                    }, 'Invitation batch created')
                  }
                >
                  Confirm invitations
                </Button>
              </div>
            </div>
            <Table
              rows={bulkUsers.map((u, i) => ({ id: String(i), ...u }))}
              columns={[
                { key: 'email', title: 'Email' },
                { key: 'role', title: 'Role' },
              ]}
            />
          </section>
        )}
        {tabs(
          [
            ['directory', 'Directory'],
            ['invitations', 'Invitations'],
            ['roles', 'Roles & permissions'],
            ['customRoles', 'Custom roles'],
          ],
          'directory'
        )}
        {current === 'directory' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Your teammates</h2>
                <p>Manage membership and predefined roles</p>
              </div>
              <div className="row-actions">
                <Button icon="file" onClick={bulkInvite}>
                  Bulk invite
                </Button>
                <Button kind="primary" icon="plus" onClick={invite}>
                  Invite teammate
                </Button>
              </div>
            </div>
            <Table
              rows={snapshot.users}
              search={search}
              columns={[
                { key: 'name', title: 'Teammate', render: (r) => person(r.name, r.email) },
                { key: 'role', title: 'Role', render: (r) => <Badge value={r.role} /> },
                statusCol,
              ]}
              actions={(r) => (
                <>
                  <Button
                    kind="ghost"
                    onClick={() =>
                      setDialog({
                        title: 'Change role',
                        fields: [field('role', 'Role', 'select', true, { options: opts(roles) })],
                        values: r,
                        onSubmit: (v) => mutate('userRole', { id: r.id, role: v.role }),
                      })
                    }
                  >
                    Role
                  </Button>
                  <Button
                    kind="ghost"
                    onClick={() =>
                      confirm(
                        r.status === 'ACTIVE' ? 'Deactivate user?' : 'Reactivate user?',
                        'Existing sessions are rejected when membership is inactive.',
                        () =>
                          mutate('userStatus', {
                            id: r.id,
                            status: r.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
                          })
                      )
                    }
                  >
                    {r.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}
                  </Button>
                </>
              )}
            />
          </section>
        ) : current === 'customRoles' ? (
          <IdentitySettings
            mode="roles"
            principal={principal}
            snapshot={snapshot}
            search={search}
            setDialog={setDialog}
            notify={notify}
            onChanged={refresh}
          />
        ) : current === 'invitations' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Invitations</h2>
                <p>Expiring, single-use account activation</p>
              </div>
              <Button icon="mail" kind="primary" onClick={invite}>
                Send invitation
              </Button>
            </div>
            <Table
              rows={snapshot.invitations}
              search={search}
              columns={[
                { key: 'email', title: 'Email' },
                { key: 'role', title: 'Role', render: (r) => label(r.role) },
                statusCol,
                {
                  key: 'expiresAt',
                  title: 'Expires',
                  render: (r) => prettyDate(r.expiresAt || r.expires_at),
                },
              ]}
            />
          </section>
        ) : (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Predefined roles</h2>
                <p>Permissions control who can view and change company records</p>
              </div>
              <Badge value="DEFAULT DENY" />
            </div>
            <div className="role-grid">
              {[
                [
                  'ADMIN',
                  'Company administrator',
                  'Organization settings, user invitations, roles and operational administration.',
                ],
                [
                  'HR',
                  'People administrator',
                  'Employee records, organization structure, leave policies, schedules and HR approvals.',
                ],
                [
                  'MANAGER',
                  'Team manager',
                  'Project delivery, team attendance review and leave decisions.',
                ],
                [
                  'MEMBER',
                  'Team member',
                  'Own profile, attendance, leave requests and assigned project work.',
                ],
                [
                  'AUDITOR',
                  'Read-only auditor',
                  'Authorized records and audit history, without mutation access.',
                ],
                ['SALES', 'Sales team', 'Customer accounts, leads, pipeline and quotations.'],
                [
                  'PROCUREMENT',
                  'Procurement team',
                  'Suppliers, purchase orders, goods receipts and stock control.',
                ],
                [
                  'FINANCE',
                  'Finance team',
                  'Invoices, reconciliation, budgets and independent financial review.',
                ],
                [
                  'PAYROLL',
                  'Payroll team',
                  'Employee compensation, payroll preparation and authorized payroll records.',
                ],
              ].map(([role, name, text]) => (
                <div className="role-card" key={role}>
                  <Icon name="shield" />
                  <h3>{name}</h3>
                  <Badge value={role} />
                  <p>{text}</p>
                </div>
              ))}
            </div>
          </section>
        )}
      </>
    );
  }
  function Organization() {
    const t = tab || 'profile';
    return (
      <>
        {tabs(
          [
            ['profile', 'Company profile'],
            ['departments', 'Departments'],
            ['branches', 'Branches'],
            ['teams', 'Teams'],
            ['designations', 'Designations'],
            ...(isAdmin(principal)
              ? ([
                  ['domains', 'Owned domains'],
                  ['featureFlags', 'Module flags'],
                ] as [string, string][])
              : []),
          ],
          'profile'
        )}
        {t === 'featureFlags' ? (
          <FeatureFlags setDialog={setDialog} notify={notify} onChanged={refresh} />
        ) : t === 'domains' ? (
          <IdentitySettings
            mode="domains"
            principal={principal}
            snapshot={snapshot}
            search={search}
            setDialog={setDialog}
            notify={notify}
            onChanged={refresh}
          />
        ) : t === 'profile' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Company profile</h2>
                <p>Your organization’s essential details</p>
              </div>
            </div>
            <ProfileForm
              key="company-profile"
              profile={snapshot.profile[0] || {}}
              disabled={!isAdmin(principal)}
              onUpload={async (dataUrl) => {
                const result = await api('/org/logo', 'POST', { dataUrl });
                return result.logoKey;
              }}
              onSave={async (v) => {
                await mutate('profile', v);
                notify('Company profile updated');
              }}
            />
          </section>
        ) : (
          resource(t, [nameCol, { key: 'description', title: 'Description' }])
        )}
      </>
    );
  }
  function Employees() {
    return (
      <>
        {tabs(
          [
            ['directory', 'Employee directory'],
            ['hierarchy', 'Organization chart'],
          ],
          'directory'
        )}
        {(tab || 'directory') === 'directory' ? (
          resource(
            'employees',
            [
              {
                key: 'name',
                title: 'Employee',
                render: (r) => (
                  <button className="text-button" onClick={() => setEmployeeDetail(r)}>
                    {person(r.name, r.employeeCode)}
                  </button>
                ),
              },
              {
                key: 'departmentId',
                title: 'Department',
                render: (r) => lookup('departments', r.departmentId),
              },
              {
                key: 'employmentType',
                title: 'Employment',
                render: (r) => label(r.employmentType),
              },
              statusCol,
              { key: 'joinDate', title: 'Joined', render: (r) => prettyDate(r.joinDate) },
            ],
            {},
            visibleEmployees,
            canManage(principal)
          )
        ) : (
          <OrgChart
            employees={visibleEmployees.filter(
              (e) => !search || JSON.stringify(e).toLowerCase().includes(search.toLowerCase())
            )}
            departments={snapshot.departments}
            company={snapshot.profile[0]?.name || 'Your organization'}
            onSelect={setEmployeeDetail}
          />
        )}
        <section className="panel personal-profile">
          <div className="panel-heading">
            <div>
              <h2>Your profile</h2>
              <p>View your own employment and contact information</p>
            </div>
            <Icon name="users" />
          </div>
          {snapshot.employees
            .filter((e) => e.userId === principal.userId)
            .map((e) => (
              <div key={e.id} className="profile-summary">
                {person(e.name, e.email)}
                <span>{e.employeeCode}</span>
                <span>{e.phone || 'No phone added'}</span>
                <span>{e.emergencyContact || 'No emergency contact added'}</span>
                {writable && (
                  <Button
                    icon="edit"
                    onClick={() =>
                      setDialog({
                        title: 'Edit your contact details',
                        fields: [
                          field('name', 'Full name'),
                          field('phone', 'Phone', 'tel', false),
                          field('emergencyContact', 'Emergency contact', 'text', false),
                        ],
                        values: {
                          name: e.name,
                          phone: e.phone,
                          emergencyContact: e.emergencyContact,
                          version: e.version,
                        },
                        onSubmit: (v) => commit('employees', v, e.id),
                      })
                    }
                  >
                    Edit contact details
                  </Button>
                )}
              </div>
            ))}
          {!snapshot.employees.some((e) => e.userId === principal.userId) && (
            <p className="muted">Your account has not yet been linked to an employee profile.</p>
          )}
        </section>
      </>
    );
  }
  function Attendance() {
    const t = tab || 'records';
    const corrections =
      canApprove(principal) || principal.role === 'AUDITOR'
        ? snapshot.corrections
        : snapshot.corrections.filter((r) => r.userId === principal.userId);
    return (
      <>
        <div className="attendance-banner">
          <div>
            <span className={'status-dot ' + (openAttendance ? 'online' : '')} />
            <strong>
              {openAttendance ? 'Currently checked in' : 'You’re currently checked out'}
            </strong>
            <small>
              {openAttendance
                ? 'Started at ' + prettyTime(openAttendance.inAt)
                : 'Your working time is recorded when you check in.'}
            </small>
          </div>
          <div className="row-actions">
            <Button
              disabled={!writable}
              icon="clock"
              kind="primary"
              onClick={() =>
                run(
                  () => mutate(openAttendance ? 'checkOut' : 'checkIn'),
                  openAttendance ? 'Checked out' : 'Checked in'
                )
              }
            >
              {openAttendance ? 'Check out' : 'Check in'}
            </Button>
            <Button disabled={!writable} icon="edit" onClick={() => requestCorrection()}>
              Request correction
            </Button>
          </div>
        </div>
        {tabs(
          [
            ['records', 'Daily attendance'],
            ['corrections', 'Corrections'],
            ...(canManage(principal)
              ? ([
                  ['shifts', 'Shift templates'],
                  ['shiftAssignments', 'Shift assignments'],
                ] as [string, string][])
              : []),
          ],
          'records'
        )}
        {t === 'records' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Attendance register</h2>
                <p>Original entries and approved corrections are retained</p>
              </div>
              <Button icon="download" onClick={() => exportRows(attendance, 'attendance')}>
                Export
              </Button>
            </div>
            <Table
              rows={attendance}
              search={search}
              columns={[
                { key: 'name', title: 'Employee', render: (r) => person(r.name) },
                { key: 'inAt', title: 'Date', render: (r) => prettyDate(r.inAt) },
                {
                  key: 'checkIn',
                  title: 'Check-in',
                  render: (r) => (
                    <span>
                      {prettyTime(r.inAt)}
                      {r.originalInAt && (
                        <small className="muted">Original: {prettyTime(r.originalInAt)}</small>
                      )}
                    </span>
                  ),
                },
                { key: 'outAt', title: 'Check-out', render: (r) => prettyTime(r.outAt) },
                {
                  key: 'duration',
                  title: 'Hours',
                  render: (r) =>
                    r.outAt ? (Number(r.workedMinutes || 0) / 60).toFixed(2) : 'In progress',
                },
                { key: 'lateMinutes', title: 'Late (min)', render: (r) => r.lateMinutes || 0 },
                {
                  key: 'overtimeMinutes',
                  title: 'Overtime (min)',
                  render: (r) => r.overtimeMinutes || 0,
                },
                statusCol,
              ]}
              actions={(r) =>
                r.userId === principal.userId && writable ? (
                  <Button kind="ghost" onClick={() => requestCorrection(r)}>
                    Correct
                  </Button>
                ) : null
              }
            />
          </section>
        ) : t === 'corrections' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Correction requests</h2>
                <p>Supervisor review, with no self-approval</p>
              </div>
            </div>
            <Table
              rows={corrections}
              search={search}
              columns={[
                { key: 'name', title: 'Employee' },
                { key: 'reason', title: 'Reason' },
                {
                  key: 'inAt',
                  title: 'Requested check-in',
                  render: (r) => prettyDate(r.inAt) + ' ' + prettyTime(r.inAt),
                },
                statusCol,
              ]}
              actions={(r) =>
                r.status === 'PENDING' && canApprove(principal) && r.userId !== principal.userId ? (
                  <>
                    <Button
                      kind="ghost"
                      onClick={() =>
                        run(
                          () => mutate('correctionDecision', { id: r.id, decision: 'REJECTED' }),
                          'Correction rejected'
                        )
                      }
                    >
                      Reject
                    </Button>
                    <Button
                      kind="primary"
                      onClick={() =>
                        run(
                          () => mutate('correctionDecision', { id: r.id, decision: 'APPROVED' }),
                          'Correction approved'
                        )
                      }
                    >
                      Approve
                    </Button>
                  </>
                ) : (
                  <span className="muted">
                    {r.userId === principal.userId ? 'Your request' : r.decidedBy || '—'}
                  </span>
                )
              }
            />
          </section>
        ) : t === 'shifts' ? (
          resource('shifts', [
            nameCol,
            { key: 'startTime', title: 'Starts' },
            { key: 'endTime', title: 'Ends' },
            { key: 'graceMinutes', title: 'Grace (min)' },
          ])
        ) : (
          resource('shiftAssignments', [
            {
              key: 'employeeId',
              title: 'Employee',
              render: (r) => lookup('employees', r.employeeId),
            },
            { key: 'shiftId', title: 'Shift', render: (r) => lookup('shifts', r.shiftId) },
            { key: 'effectiveFrom', title: 'From', render: (r) => prettyDate(r.effectiveFrom) },
            { key: 'effectiveTo', title: 'Until', render: (r) => prettyDate(r.effectiveTo) },
          ])
        )}
      </>
    );
  }
  function Leave() {
    const t = tab || 'requests';
    return (
      <>
        <div className="stats-grid leave-stats">
          {ownBalances.map((b) => (
            <div className="stat-card" key={b.id || b.type}>
              <div className="stat-top">
                <span>{label(b.type)} leave</span>
                <Icon name="leaf" />
              </div>
              <strong className="stat-value">
                {b.remaining}
                <small> / {b.allowance} days</small>
              </strong>
              <div className="progress">
                <span
                  style={{
                    width:
                      Math.max(
                        0,
                        Math.min(100, (Number(b.remaining) / Number(b.allowance)) * 100)
                      ) + '%',
                  }}
                />
              </div>
              <small>{b.used} days used · approved leave only</small>
            </div>
          ))}
        </div>
        {tabs(
          [
            ['requests', 'Leave requests'],
            ['ledger', 'Balance history'],
            ...(canManage(principal)
              ? ([['leavePolicies', 'Policies']] as [string, string][])
              : []),
          ],
          'requests'
        )}
        {t === 'requests' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Leave requests</h2>
                <p>
                  {canApprove(principal)
                    ? 'Review team requests and track your own'
                    : 'Your time off, clearly tracked'}
                </p>
              </div>
              {writable && (
                <Button kind="primary" icon="plus" onClick={requestLeave}>
                  Request time off
                </Button>
              )}
            </div>
            <Table
              rows={leaveRequests}
              search={search}
              columns={[
                { key: 'name', title: 'Employee', render: (r) => person(r.name) },
                { key: 'type', title: 'Type', render: (r) => label(r.type) },
                {
                  key: 'dates',
                  title: 'Dates',
                  render: (r) => (
                    <span>
                      {prettyDate(r.startDate)}
                      <small className="muted">to {prettyDate(r.endDate)}</small>
                    </span>
                  ),
                },
                { key: 'days', title: 'Days' },
                {
                  key: 'approval',
                  title: 'Approval stages',
                  render: (r) => `${r.approvedLevels || 0} / ${r.requiredLevels || 1}`,
                },
                statusCol,
                { key: 'reason', title: 'Reason' },
              ]}
              actions={(r) => (
                <>
                  {r.status === 'PENDING' &&
                    canApprove(principal) &&
                    r.userId !== principal.userId && (
                      <>
                        <Button
                          kind="ghost"
                          onClick={() =>
                            run(
                              () => mutate('leaveDecision', { id: r.id, decision: 'REJECTED' }),
                              'Request rejected'
                            )
                          }
                        >
                          Reject
                        </Button>
                        <Button
                          kind="primary"
                          onClick={() =>
                            run(
                              () => mutate('leaveDecision', { id: r.id, decision: 'APPROVED' }),
                              'Request approved'
                            )
                          }
                        >
                          Approve
                        </Button>
                      </>
                    )}
                  {writable &&
                    ['PENDING', 'APPROVED'].includes(r.status) &&
                    (r.userId === principal.userId || canManage(principal)) && (
                      <Button
                        kind="ghost"
                        onClick={() =>
                          confirm(
                            'Cancel leave request?',
                            'Any previously deducted days will be returned to the balance exactly once.',
                            () => mutate('leaveCancel', { id: r.id })
                          )
                        }
                      >
                        Cancel
                      </Button>
                    )}
                </>
              )}
            />
          </section>
        ) : t === 'ledger' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Balance history</h2>
                <p>An append-only record of leave deductions, adjustments and reversals</p>
              </div>
              {canManage(principal) && (
                <Button icon="plus" kind="primary" onClick={adjustLeave}>
                  Adjust balance
                </Button>
              )}
            </div>
            <Table
              rows={snapshot.ledger.filter(
                (l) =>
                  canManage(principal) ||
                  principal.role === 'AUDITOR' ||
                  l.userId === principal.userId
              )}
              search={search}
              columns={[
                { key: 'userId', title: 'Employee', render: (r) => lookup('users', r.userId) },
                { key: 'type', title: 'Type', render: (r) => label(r.type) },
                {
                  key: 'delta',
                  title: 'Days',
                  render: (r) => (
                    <strong className={r.delta > 0 ? 'positive' : 'negative'}>
                      {r.delta > 0 ? '+' : ''}
                      {r.delta}
                    </strong>
                  ),
                },
                { key: 'reason', title: 'Reason' },
                { key: 'createdAt', title: 'Recorded', render: (r) => prettyDate(r.createdAt) },
              ]}
            />
          </section>
        ) : (
          resource('leavePolicies', [
            nameCol,
            { key: 'type', title: 'Type' },
            { key: 'annualDays', title: 'Annual days' },
            { key: 'carryForwardDays', title: 'Carry-forward limit' },
            {
              key: 'approvalLevels',
              title: 'Approval levels',
              render: (r) => r.approvalLevels || 1,
            },
            {
              key: 'workingDays',
              title: 'Working week',
              render: (r) =>
                (r.workingDays || [1, 2, 3, 4, 5])
                  .map((d: number) => ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d % 7])
                  .join(', '),
            },
          ])
        )}
      </>
    );
  }
  function Projects() {
    const t = tab || 'board',
      filteredTasks = tasks.filter(
        (t) =>
          (projectId === 'all' || t.projectId === projectId) &&
          (!search || JSON.stringify(t).toLowerCase().includes(search.toLowerCase()))
      ),
      project = snapshot.projects.find((p) => p.id === projectId);
    return (
      <>
        <div className="project-toolbar">
          <select
            aria-label="Filter by project"
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
          >
            <option value="all">All projects</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.status === 'ARCHIVED' ? ' · Archived' : ''}
              </option>
            ))}
          </select>
          <div className="row-actions">
            {['ADMIN', 'MANAGER'].includes(principal.role) && (
              <Button icon="plus" onClick={() => editRecord('projects')}>
                New project
              </Button>
            )}
            {writable && (
              <Button
                icon="plus"
                kind="primary"
                onClick={() =>
                  editRecord('tasks', undefined, {
                    projectId: projectId !== 'all' ? projectId : projects[0]?.id,
                  })
                }
              >
                New task
              </Button>
            )}
          </div>
        </div>
        {project && (
          <div className="project-summary">
            <span className="project-icon">
              <Icon name="briefcase" />
            </span>
            <div>
              <strong>{project.name}</strong>
              <p>{project.description}</p>
            </div>
            <Badge value={project.status} />
          </div>
        )}
        {tabs(
          [
            ['board', 'Kanban board'],
            ['list', 'Task list'],
            ['projects', 'Projects'],
            ['sprints', 'Sprints'],
            ['milestones', 'Milestones'],
            ['timeEntries', 'Time entries'],
            ['workload', 'Workload'],
            ['timeline', 'Timeline / Gantt'],
            ['risks', 'Risks'],
            ['recurring', 'Recurring tasks'],
            ['analytics', 'Task analytics'],
          ],
          'board'
        )}
        {t === 'board' ? (
          <div className="kanban">
            {[
              ['TODO', 'To do'],
              ['IN_PROGRESS', 'In progress'],
              ['REVIEW', 'In review'],
              ['DONE', 'Completed'],
            ].map(([status, name]) => (
              <section
                className={'kanban-column ' + status.toLowerCase()}
                key={status}
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  const id = e.dataTransfer.getData('text/plain');
                  const task = tasks.find((t) => t.id === id);
                  if (task && task.status !== status) taskStatus(task, status);
                }}
              >
                <div className="kanban-heading">
                  <h2>
                    <span className="status-dot" />
                    {name}
                  </h2>
                  <span className="count">
                    {filteredTasks.filter((t) => t.status === status).length}
                  </span>
                </div>
                {filteredTasks
                  .filter((t) => t.status === status)
                  .map((task) => (
                    <article
                      className="task-card"
                      key={task.id}
                      draggable={writable}
                      onDragStart={(e) => e.dataTransfer.setData('text/plain', task.id)}
                    >
                      <div className="task-card-top">
                        <Badge value={task.priority} />
                        <button
                          className="icon-button"
                          aria-label={'Edit ' + task.name}
                          onClick={() => editRecord('tasks', task)}
                          disabled={!writable}
                        >
                          <Icon name="edit" size={15} />
                        </button>
                      </div>
                      <button className="task-title text-button" onClick={() => showTask(task)}>
                        {task.name}
                      </button>
                      <p>{task.description}</p>
                      <span className="task-project">{lookup('projects', task.projectId)}</span>
                      <div className="task-tags">
                        {String(task.labels || '')
                          .split(',')
                          .filter(Boolean)
                          .map((tag: string) => (
                            <span key={tag}>{tag.trim()}</span>
                          ))}
                      </div>
                      <div className="task-card-bottom">
                        <span>
                          <Icon name="calendar" size={13} />
                          {task.dueDate ? prettyDate(task.dueDate) : 'No due date'}
                        </span>
                        <span className="avatar tiny" title={lookup('users', task.assigneeId)}>
                          {initials(lookup('users', task.assigneeId))}
                        </span>
                      </div>
                    </article>
                  ))}
                {writable && (
                  <button
                    className="add-task"
                    onClick={() =>
                      editRecord('tasks', undefined, {
                        status,
                        projectId: projectId !== 'all' ? projectId : projects[0]?.id,
                      })
                    }
                  >
                    <Icon name="plus" size={16} /> Add task
                  </button>
                )}
              </section>
            ))}
          </div>
        ) : t === 'list' ? (
          <section className="panel">
            <Table
              rows={filteredTasks}
              columns={[
                {
                  key: 'name',
                  title: 'Task',
                  render: (r) => (
                    <button className="text-button" onClick={() => showTask(r)}>
                      {r.name}
                    </button>
                  ),
                },
                {
                  key: 'projectId',
                  title: 'Project',
                  render: (r) => lookup('projects', r.projectId),
                },
                {
                  key: 'assigneeId',
                  title: 'Assignee',
                  render: (r) => lookup('users', r.assigneeId),
                },
                statusCol,
                { key: 'priority', title: 'Priority', render: (r) => <Badge value={r.priority} /> },
                { key: 'dueDate', title: 'Due', render: (r) => prettyDate(r.dueDate) },
              ]}
              actions={
                writable
                  ? (r) => (
                      <Button kind="ghost" icon="edit" onClick={() => editRecord('tasks', r)}>
                        Edit
                      </Button>
                    )
                  : undefined
              }
            />
          </section>
        ) : ['risks', 'recurring', 'analytics'].includes(t) ? (
          <WorkforceExtensions
            mode="projects"
            tool={t}
            projectId={projectId}
            principal={principal}
            snapshot={snapshot}
            search={search}
            setDialog={setDialog}
            notify={notify}
            onChanged={refresh}
          />
        ) : t === 'timeline' ? (
          <ProjectTimeline
            tasks={filteredTasks}
            projects={projects}
            sprints={snapshot.sprints.filter(
              (r) => projectId === 'all' || r.projectId === projectId
            )}
            milestones={snapshot.milestones.filter(
              (r) => projectId === 'all' || r.projectId === projectId
            )}
            onTask={showTask}
          />
        ) : t === 'projects' ? (
          resource(
            'projects',
            [
              nameCol,
              { key: 'ownerId', title: 'Owner', render: (r) => lookup('users', r.ownerId) },
              { key: 'memberIds', title: 'Team size', render: (r) => r.memberIds?.length || 0 },
              statusCol,
              { key: 'dueDate', title: 'Target', render: (r) => prettyDate(r.dueDate) },
            ],
            {},
            projects,
            ['ADMIN', 'MANAGER'].includes(principal.role)
          )
        ) : t === 'sprints' ? (
          resource(
            'sprints',
            [
              nameCol,
              {
                key: 'projectId',
                title: 'Project',
                render: (r) => lookup('projects', r.projectId),
              },
              { key: 'startDate', title: 'Starts', render: (r) => prettyDate(r.startDate) },
              { key: 'endDate', title: 'Ends', render: (r) => prettyDate(r.endDate) },
              statusCol,
            ],
            { projectId: projectId === 'all' ? projects[0]?.id : projectId },
            snapshot.sprints.filter((r) => projects.some((p) => p.id === r.projectId)),
            ['ADMIN', 'MANAGER'].includes(principal.role)
          )
        ) : t === 'workload' ? (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Team workload</h2>
                <p>Open tasks and overdue work across visible projects</p>
              </div>
            </div>
            <Table
              rows={snapshot.users.filter((u) => u.status === 'ACTIVE')}
              search={search}
              columns={[
                { key: 'name', title: 'Teammate', render: (r) => person(r.name, r.role) },
                {
                  key: 'open',
                  title: 'Open tasks',
                  render: (r) =>
                    filteredTasks.filter((t) => t.assigneeId === r.id && t.status !== 'DONE')
                      .length,
                },
                {
                  key: 'overdue',
                  title: 'Overdue',
                  render: (r) =>
                    filteredTasks.filter(
                      (t) =>
                        t.assigneeId === r.id &&
                        t.status !== 'DONE' &&
                        t.dueDate &&
                        t.dueDate < today()
                    ).length,
                },
                {
                  key: 'hours',
                  title: 'Hours logged',
                  render: (r) =>
                    snapshot.timeEntries
                      .filter((t) => t.userId === r.id)
                      .reduce((n, t) => n + Number(t.hours), 0),
                },
              ]}
            />
          </section>
        ) : t === 'milestones' ? (
          resource(
            'milestones',
            [
              nameCol,
              {
                key: 'projectId',
                title: 'Project',
                render: (r) => lookup('projects', r.projectId),
              },
              { key: 'dueDate', title: 'Target', render: (r) => prettyDate(r.dueDate) },
              statusCol,
            ],
            { projectId: projectId === 'all' ? projects[0]?.id : projectId },
            snapshot.milestones.filter((r) => projects.some((p) => p.id === r.projectId)),
            ['ADMIN', 'MANAGER'].includes(principal.role)
          )
        ) : (
          resource(
            'timeEntries',
            [
              { key: 'taskId', title: 'Task', render: (r) => lookup('tasks', r.taskId) },
              { key: 'date', title: 'Date', render: (r) => prettyDate(r.date) },
              { key: 'hours', title: 'Hours' },
              { key: 'note', title: 'Work description' },
              statusCol,
            ],
            {},
            snapshot.timeEntries,
            writable
          )
        )}
      </>
    );
  }
  function Account() {
    const setupMfa = () =>
      setDialog({
        title: 'Set up authenticator',
        description: 'Confirm your password to create a private authenticator secret.',
        fields: [field('currentPassword', 'Current password', 'password')],
        onSubmit: async (v) => {
          setMfaSecret(await api('/me/mfa/setup', 'POST', v));
          setMfa(await api('/me/mfa'));
          notify('Add the displayed secret to your authenticator, then enable verification.');
        },
      });
    const enableMfa = () =>
      setDialog({
        title: 'Enable multi-factor authentication',
        fields: [field('otp', 'Authenticator code')],
        onSubmit: async (v) => {
          setMfaSecret(await api('/me/mfa/enable', 'POST', v));
          setMfa(await api('/me/mfa'));
          notify('Multi-factor authentication enabled. Save your recovery codes.');
        },
      });
    const disableMfa = () =>
      setDialog({
        title: 'Disable multi-factor authentication',
        fields: [
          field('currentPassword', 'Current password', 'password'),
          field('otp', 'Authenticator or recovery code'),
        ],
        onSubmit: async (v) => {
          await api('/me/mfa/disable', 'POST', v);
          setMfaSecret(null);
          setMfa(await api('/me/mfa'));
          notify('Multi-factor authentication disabled.');
        },
      });
    const changePassword = () =>
      setDialog({
        title: 'Change your password',
        description: 'All active sessions are signed out after your password changes.',
        fields: [
          field('currentPassword', 'Current password', 'password'),
          field('newPassword', 'New password', 'password'),
        ],
        submitLabel: 'Change password',
        onSubmit: async (v) => {
          await api('/auth/change-password', 'POST', v);
          setAuthenticated(false);
          setSnapshot(emptySnapshot());
          go('dashboard');
          notify('Password changed. Sign in again to continue.');
        },
      });
    return (
      <>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Personal account</h2>
              <p>Your authenticated identity</p>
            </div>
          </div>
          <div className="profile-summary">
            {person(principal.name, principal.email)}
            <Badge value={principal.role} />
            <Button
              icon="edit"
              onClick={() =>
                setDialog({
                  title: 'Edit your name',
                  fields: [field('name', 'Full name')],
                  values: { name: principal.name },
                  onSubmit: async (v) => {
                    setPrincipal(await api('/me/profile', 'PUT', v));
                    notify('Your name was updated');
                  },
                })
              }
            >
              Edit name
            </Button>
            <Button icon="shield" onClick={changePassword}>
              Change password
            </Button>
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Multi-factor authentication</h2>
              <p>Add an authenticator for an extra verification step at sign in.</p>
            </div>
            <Badge value={mfa.enabled ? 'ENABLED' : 'DISABLED'} />
          </div>
          <div className="profile-summary">
            {!mfa.enabled && (
              <Button icon="shield" onClick={setupMfa}>
                Set up authenticator
              </Button>
            )}
            {mfa.configured && !mfa.enabled && (
              <Button kind="primary" onClick={enableMfa}>
                Verify and enable
              </Button>
            )}
            {mfa.enabled && <Button onClick={disableMfa}>Disable authenticator</Button>}
          </div>
          {mfaSecret && (
            <div className="secret-panel" role="status">
              {mfaSecret.secret && (
                <>
                  <h3>Add this secret to your authenticator</h3>
                  <code>{mfaSecret.secret}</code>
                  <p>Enter the generated code to finish enabling authentication.</p>
                </>
              )}
              {mfaSecret.recoveryCodes && (
                <>
                  <h3>Save these recovery codes now</h3>
                  <p>Each code works once. They will not be shown again.</p>
                  <pre>{mfaSecret.recoveryCodes.join('\n')}</pre>
                </>
              )}
              <Button onClick={() => setMfaSecret(null)}>Dismiss secret</Button>
            </div>
          )}
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Active sessions</h2>
              <p>Revoke access on devices you no longer use.</p>
            </div>
            <Button
              kind="danger"
              onClick={() =>
                confirm(
                  'Sign out every session?',
                  'All devices, including this one, will be signed out.',
                  async () => {
                    await api('/me/sessions', 'DELETE');
                    setAuthenticated(false);
                    setSnapshot(emptySnapshot());
                    go('dashboard');
                  }
                )
              }
            >
              Revoke all sessions
            </Button>
          </div>
          {
            <Table
              rows={sessions}
              columns={[
                {
                  key: 'current',
                  title: 'Session',
                  render: (r) => (r.current ? <Badge value="CURRENT" /> : 'Other device'),
                },
                {
                  key: 'userAgent',
                  title: 'Device',
                  render: (r) => r.userAgent || 'Device information unavailable',
                },
                { key: 'createdAt', title: 'Signed in', render: (r) => prettyDate(r.createdAt) },
                { key: 'expiresAt', title: 'Expires', render: (r) => prettyDate(r.expiresAt) },
              ]}
              actions={(r) => (
                <Button
                  kind="ghost"
                  onClick={() =>
                    confirm(
                      'Revoke this session?',
                      r.current
                        ? 'You will be signed out of the live workspace.'
                        : 'This device will lose access immediately.',
                      async () => {
                        await api('/me/sessions/' + r.id, 'DELETE');
                        if (r.current) {
                          setAuthenticated(false);
                          setSnapshot(emptySnapshot());
                          go('dashboard');
                        } else setSessions(await api('/me/sessions'));
                      }
                    )
                  }
                >
                  Revoke
                </Button>
              )}
            />
          }
        </section>
      </>
    );
  }
  function Appearance() {
    return (
      <div className="appearance-content">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Color palette</h2>
              <p>Ten palettes, five coordinated colors each</p>
            </div>
            <Icon name="settings" />
          </div>
          <div className="palette-grid">
            {palettes.map(([name, ...colors]) => (
              <button
                key={name}
                className={'palette-swatch ' + (theme === name ? 'selected' : '')}
                onClick={() => setTheme(name)}
                aria-pressed={theme === name}
              >
                <span className="palette-dots">
                  {colors.map((c, i) => (
                    <i key={i} style={{ background: c }} />
                  ))}
                </span>
                <strong>{label(name)}</strong>
                {theme === name && <Icon name="check" size={15} />}
              </button>
            ))}
          </div>
          <div className="appearance-mode">
            <div>
              <h3>Display mode</h3>
              <p>Choose what’s comfortable for your day.</p>
            </div>
            <div className="mode-toggle">
              <button
                className={colorMode === 'light' ? 'active' : ''}
                onClick={() => setColorMode('light')}
              >
                <Icon name="sun" size={17} /> Light
              </button>
              <button
                className={colorMode === 'dark' ? 'active' : ''}
                onClick={() => setColorMode('dark')}
              >
                <Icon name="moon" size={17} /> Dark
              </button>
            </div>
          </div>
          <div className="row-actions">
            <Button
              onClick={() => {
                setTheme('ocean');
                setColorMode('light');
              }}
            >
              Reset appearance
            </Button>
          </div>
          <div className="palette-preview">
            {['Primary', 'Secondary', 'Accent', 'Sidebar', 'Soft'].map((l, i) => (
              <div key={l}>
                <span style={{ background: palettes.find((p) => p[0] === theme)?.[i + 1] }} />
                {l}
              </div>
            ))}
          </div>
        </section>
      </div>
    );
  }
  const moduleForPage: Record<string, string> = {
    employees: 'HR',
    attendance: 'HR',
    leave: 'HR',
    requests: 'HR',
    projects: 'PROJECTS',
    crm: 'CRM',
    inventory: 'INVENTORY',
    procurement: 'INVENTORY',
    finance: 'FINANCE',
    payroll: 'PAYROLL',
    workflows: 'WORKFLOWS',
    documents: 'DOCUMENTS',
    reports: 'REPORTS',
    integrations: 'INTEGRATIONS',
  };
  const disabledModule = (p: string) =>
    (snapshot.featureFlags || []).some(
      (flag) => flag.key === moduleForPage[p] && flag.enabled === false
    );
  const allowedPages = navGroups.flatMap((g) => g.items.map((item) => item[0]));
  const enterprisePages = [
    'crm',
    'inventory',
    'procurement',
    'finance',
    'payroll',
    'workflows',
    'documents',
    'notifications',
    'reports',
    'integrations',
    'subscriptions',
    'operations',
  ];
  if (!authenticated)
    return (
      <>
        <AuthGate
          checking={authChecking}
          onLogin={async (v) => {
            const p = await api('/auth/login', 'POST', v);
            setPrincipal(p);
            setAuthenticated(true);
            go('dashboard');
            await refresh(p);
            notify('Signed in successfully');
          }}
          onFlow={publicFlow}
          onAppearance={() => setSettings(true)}
        />
        {settings && (
          <>
            <div className="drawer-backdrop" onClick={() => setSettings(false)} />
            <aside
              ref={drawerRef}
              id="appearance-drawer"
              className="settings-drawer"
              role="dialog"
              aria-modal="true"
              aria-label="Appearance settings"
            >
              <div className="drawer-heading">
                <h2>Make it yours</h2>
                <Button
                  icon="close"
                  aria-label="Close appearance settings"
                  onClick={() => setSettings(false)}
                />
              </div>
              <Appearance />
            </aside>
          </>
        )}
        {dialog && (
          <FormDialog key={dialog.title} dialog={dialog} onClose={() => setDialog(null)} />
        )}
        {toast && (
          <div className={'toast ' + (toast.error ? 'error' : '')} role="status">
            {toast.message}
          </div>
        )}
      </>
    );
  return (
    <div className="app-shell">
      <aside className={'sidebar ' + (mobile ? 'open' : '')}>
        <a className="brand" href="#dashboard" onClick={() => go('dashboard')}>
          <span className="brand-mark">
            <Icon name="dashboard" size={23} />
          </span>
          <span>
            Enterprise<span className="brand-accent">OS</span>
            <small>WORK, BEAUTIFULLY CONNECTED</small>
          </span>
        </a>
        <div className="workspace-picker">
          <span className="workspace-logo">
            {platform ? 'P' : initials(snapshot.profile[0]?.name || 'Company')}
          </span>
          <div>
            <strong>
              {platform ? 'Platform console' : snapshot.profile[0]?.name || 'Your company'}
            </strong>
            <small>{platform ? 'Company administration' : 'Team workspace'}</small>
          </div>
          <Icon name="chevron" size={15} />
        </div>
        <nav aria-label="Main navigation">
          {navGroups.map((group) => (
            <div className="nav-group" key={group.title}>
              <span className="nav-label">{group.title}</span>
              {group.items.map(([id, name, icon]) => (
                <button
                  className={'nav-item ' + (page === id ? 'active' : '')}
                  disabled={disabledModule(id)}
                  title={disabledModule(id) ? 'Disabled by company settings' : undefined}
                  key={id}
                  onClick={() => go(id)}
                >
                  <Icon name={icon} size={19} />
                  <span>{name}</span>
                  {id === 'leave' && leaveRequests.some((l) => l.status === 'PENDING') && (
                    <span className="nav-count">
                      {leaveRequests.filter((l) => l.status === 'PENDING').length}
                    </span>
                  )}
                  {page === id && <span className="nav-active-dot" />}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="phase-card">
            <span className="phase-icon">
              <Icon name="briefcase" size={18} />
            </span>
            <strong>Built for your next chapter</strong>
            <p>People, projects and possibilities.</p>
            <span>ALL FIVE PHASES</span>
          </div>
          <div className="sidebar-user">
            <span className="avatar">{initials(principal.name)}</span>
            <div>
              <strong>{principal.name}</strong>
              <small>{label(principal.role)}</small>
            </div>
            <button
              className="icon-button"
              aria-label="Sign out"
              onClick={() =>
                run(async () => {
                  await api('/auth/logout', 'POST', {});
                  setSnapshot(emptySnapshot());
                  setAuthenticated(false);
                  go('dashboard');
                }, 'Signed out')
              }
            >
              <Icon name="logout" size={18} />
            </button>
          </div>
        </div>
      </aside>
      {mobile && (
        <button
          className="mobile-backdrop"
          aria-label="Close navigation"
          onClick={() => setMobile(false)}
        />
      )}
      <div className="workspace">
        <header className="topbar">
          <div className="topbar-left">
            <button
              className="icon-button mobile-toggle"
              aria-label="Open navigation"
              onClick={() => setMobile(true)}
            >
              <Icon name="menu" />
            </button>
            <span className="breadcrumb">
              Workspace <Icon name="chevron" size={14} />{' '}
              <strong>{heading[page]?.[0] || 'Overview'}</strong>
            </span>
          </div>
          <div className="topbar-right">
            <span className="mode-badge live">
              <span />
              LIVE WORKSPACE
            </span>
            <Button kind="ghost compact" icon="refresh" onClick={() => refresh()} disabled={busy}>
              {busy ? 'Refreshing...' : 'Refresh'}
            </Button>
            <button
              className="icon-button"
              aria-label="Toggle dark mode"
              onClick={() => setColorMode(colorMode === 'dark' ? 'light' : 'dark')}
            >
              <Icon name={colorMode === 'dark' ? 'sun' : 'moon'} size={19} />
            </button>
            <span className="topbar-divider" />
            <button
              className="avatar icon-button"
              aria-label="Open my account"
              onClick={() => go('account')}
            >
              {initials(principal.name)}
            </button>
          </div>
        </header>
        <main className="page">
          <div className="page-heading">
            <div>
              <span className="eyebrow">
                {platform
                  ? 'PLATFORM ADMINISTRATION'
                  : (snapshot.profile[0]?.name || 'YOUR COMPANY').toUpperCase() +
                    ' / ' +
                    (page === 'dashboard' ? 'HOME' : 'WORKSPACE')}
              </span>
              <h1>{heading[page]?.[0] || 'Page not found'}</h1>
              <p>{heading[page]?.[1] || 'Choose a page from the sidebar.'}</p>
            </div>
            <div className="heading-actions">
              <span className="date-pill">
                <Icon name="calendar" size={16} />
                {new Date().toLocaleDateString(undefined, {
                  day: 'numeric',
                  month: 'short',
                  year: 'numeric',
                })}
              </span>
            </div>
          </div>
          {loadErrors.length > 0 && (
            <details className="load-errors">
              <summary>{loadErrors.length} data sources unavailable or restricted</summary>
              {loadErrors.map((e) => (
                <p key={e}>{e}</p>
              ))}
            </details>
          )}
          {page !== 'dashboard' && page !== 'settings' && (
            <div className="toolbar">
              <label className="search-field">
                <Icon name="search" size={18} />
                <input
                  placeholder={'Search ' + (heading[page]?.[0].toLowerCase() || 'records') + '…'}
                  aria-label="Search current page"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
                <kbd>⌕</kbd>
              </label>
              <span className="toolbar-note">Protected by server-side permissions</span>
            </div>
          )}
          {disabledModule(page) ? (
            <section className="panel">
              <Empty
                title="Module disabled for your company"
                text="A company administrator can enable this module from Organization > Module flags. Your records are retained."
              />
            </section>
          ) : !allowedPages.includes(page) ? (
            <section className="panel">
              <Empty
                title="Access unavailable"
                text="This page is not available for your current role."
              />
              <Button onClick={() => go('dashboard')}>Back to overview</Button>
            </section>
          ) : page === 'requests' ? (
            <WorkforceExtensions
              mode="self-service"
              principal={principal}
              snapshot={snapshot}
              search={search}
              setDialog={setDialog}
              notify={notify}
              onChanged={refresh}
            />
          ) : enterprisePages.includes(page) ? (
            <EnterpriseModules
              key={page}
              page={page}
              principal={principal}
              snapshot={snapshot}
              search={search}
              setDialog={setDialog}
              notify={notify}
            />
          ) : page === 'dashboard' ? (
            <Dashboard />
          ) : page === 'tenants' ? (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Company tenants</h2>
                  <p>Platform scope · no routine access to tenant business records</p>
                </div>
                <Button kind="primary" icon="plus" onClick={createTenant}>
                  Create company
                </Button>
              </div>
              <Table
                rows={snapshot.tenants}
                search={search}
                columns={[
                  nameCol,
                  { key: 'slug', title: 'Workspace slug' },
                  { key: 'timezone', title: 'Timezone' },
                  statusCol,
                ]}
                actions={(r) => (
                  <Button
                    kind="ghost"
                    onClick={() =>
                      confirm(
                        r.status === 'ACTIVE' ? 'Suspend company?' : 'Reactivate company?',
                        r.status === 'ACTIVE'
                          ? 'All protected tenant access will be blocked. Historical records are retained.'
                          : 'Tenant members will regain permitted access.',
                        () =>
                          mutate('tenantStatus', {
                            id: r.id,
                            status: r.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                          })
                      )
                    }
                  >
                    {r.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}
                  </Button>
                )}
              />
            </section>
          ) : page === 'users' ? (
            <Users />
          ) : page === 'organization' ? (
            <Organization />
          ) : page === 'employees' ? (
            <Employees />
          ) : page === 'attendance' ? (
            <Attendance />
          ) : page === 'leave' ? (
            <Leave />
          ) : page === 'projects' ? (
            <Projects />
          ) : page === 'account' ? (
            <Account />
          ) : page === 'audit' ? (
            <section className="panel">
              <div className="panel-heading">
                <div>
                  <h2>Workspace activity</h2>
                  <p>Read-only history of privileged and operational changes</p>
                </div>
                <Button icon="download" onClick={() => exportRows(snapshot.audit, 'audit')}>
                  Export
                </Button>
              </div>
              <Table
                rows={snapshot.audit}
                search={search}
                columns={[
                  {
                    key: 'actor',
                    title: 'Actor',
                    render: (r) => r.actorName || r.actor_name || 'System',
                  },
                  { key: 'action', title: 'Action', render: (r) => <Badge value={r.action} /> },
                  { key: 'entity', title: 'Resource', render: (r) => label(r.entity) },
                  {
                    key: 'entityId',
                    title: 'Record',
                    render: (r) => (
                      <code>{String(r.entityId || r.entity_id || '—').slice(0, 12)}</code>
                    ),
                  },
                  {
                    key: 'createdAt',
                    title: 'Recorded',
                    render: (r) =>
                      prettyDate(r.createdAt || r.created_at) +
                      ' ' +
                      prettyTime(r.createdAt || r.created_at),
                  },
                ]}
              />
            </section>
          ) : (
            <Appearance />
          )}
          <footer className="page-footer">
            <span>
              EnterpriseOS <span className="muted">/ Make work flow.</span>
            </span>
            <div>
              <button onClick={() => publicFlow('forgot')}>Forgot password</button>
              <button onClick={() => publicFlow('reset')}>Reset password</button>
              <button onClick={() => publicFlow('accept')}>Accept invitation</button>
            </div>
          </footer>
        </main>
      </div>
      <button
        ref={settingsTriggerRef}
        className="settings-trigger"
        aria-label="Open appearance settings"
        aria-expanded={settings}
        aria-controls="appearance-drawer"
        onClick={() => setSettings(!settings)}
      >
        <Icon name="settings" size={23} />
      </button>
      {settings && (
        <>
          <div className="drawer-backdrop" onClick={() => setSettings(false)} />
          <aside
            ref={drawerRef}
            id="appearance-drawer"
            className="settings-drawer"
            role="dialog"
            aria-modal="true"
            aria-label="Appearance settings"
          >
            <div className="drawer-heading">
              <div>
                <h2>Make it yours</h2>
                <p>One workspace. Your kind of color.</p>
              </div>
              <Button
                icon="close"
                aria-label="Close appearance settings"
                onClick={() => setSettings(false)}
              />
            </div>
            <Appearance />
            <div className="drawer-note">
              <Icon name="check" size={17} /> Appearance is saved automatically on this device.
            </div>
          </aside>
        </>
      )}
      {employeeDetail && !dialog && (
        <div
          className="modal-backdrop"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setEmployeeDetail(null);
          }}
        >
          <section className="modal" role="dialog" aria-modal="true" aria-label="Employee profile">
            <div className="modal-header">
              <div>
                <h2>{employeeDetail.name}</h2>
                <p>
                  {employeeDetail.employeeCode} |{' '}
                  {lookup('departments', employeeDetail.departmentId)}
                </p>
              </div>
              <Button
                icon="close"
                aria-label="Close employee profile"
                onClick={() => setEmployeeDetail(null)}
              />
            </div>
            <div className="task-detail-content">
              <div className="task-detail-meta">
                <Badge value={employeeDetail.status} />
                <span>{employeeDetail.email}</span>
                <span>Joined {prettyDate(employeeDetail.joinDate)}</span>
              </div>
              <p>
                <strong>Reports to:</strong> {lookup('employees', employeeDetail.managerId)}
              </p>
              <p>
                <strong>Employment:</strong> {label(employeeDetail.employmentType)}
              </p>
              {(canManage(principal) || employeeDetail.userId === principal.userId) && (
                <>
                  <p>
                    <strong>Phone:</strong> {employeeDetail.phone || 'Not added'}
                  </p>
                  <p>
                    <strong>Emergency contact:</strong>{' '}
                    {employeeDetail.emergencyContact || 'Not added'}
                  </p>
                  <Button
                    icon="activity"
                    onClick={() => viewChanges('employees', employeeDetail.id)}
                  >
                    Change history
                  </Button>
                  <h3>Employment history</h3>
                  {employeeDetail.history?.length ? (
                    <div className="activity-list">
                      {employeeDetail.history.map((h: any, i: number) => (
                        <div className="activity-item" key={i}>
                          <span className="activity-dot" />
                          <div>
                            <strong>{label(h.status)}</strong>
                            <p>
                              {lookup('departments', h.departmentId)} | effective{' '}
                              {prettyDate(h.effectiveDate)}
                            </p>
                            <small>
                              {prettyDate(h.recordedAt)} | {lookup('users', h.actorId)}
                            </small>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="muted">No employment changes recorded yet.</p>
                  )}
                </>
              )}
              {canManage(principal) && (
                <Button icon="edit" onClick={() => editRecord('employees', employeeDetail)}>
                  Edit employee
                </Button>
              )}
            </div>
          </section>
        </div>
      )}
      {taskDetail && !dialog && (
        <div
          className="modal-backdrop"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setTaskDetail(null);
          }}
        >
          <section
            className="modal task-detail"
            role="dialog"
            aria-modal="true"
            aria-labelledby="task-title"
          >
            <div className="modal-header">
              <div>
                <span className="eyebrow">{lookup('projects', taskDetail.projectId)}</span>
                <h2 id="task-title">{taskDetail.name}</h2>
              </div>
              <Button icon="close" aria-label="Close task" onClick={() => setTaskDetail(null)} />
            </div>
            <div className="task-detail-content">
              <div className="task-detail-meta">
                <Badge value={taskDetail.status} />
                <Badge value={taskDetail.priority} />
                <span>{lookup('users', taskDetail.assigneeId)}</span>
                <span>Due {prettyDate(taskDetail.dueDate)}</span>
              </div>
              <p>{taskDetail.description || 'No description added.'}</p>
              {taskDetail.parentId && (
                <p>
                  <strong>Parent task:</strong> {lookup('tasks', taskDetail.parentId)}
                </p>
              )}
              <p>
                <strong>Dependencies:</strong>{' '}
                {(taskDetail.dependencyIds || [])
                  .map((id: string) => lookup('tasks', id))
                  .join(', ') || 'None'}
              </p>
              <div className="row-actions">
                {writable && (
                  <>
                    <Button icon="edit" onClick={() => editRecord('tasks', taskDetail)}>
                      Edit task
                    </Button>
                    <Button icon="activity" onClick={() => viewChanges('tasks', taskDetail.id)}>
                      Change history
                    </Button>
                    <Button
                      icon="clock"
                      onClick={() =>
                        editRecord('timeEntries', undefined, { taskId: taskDetail.id })
                      }
                    >
                      Log time
                    </Button>
                  </>
                )}
                <span className="muted">
                  {snapshot.timeEntries
                    .filter((t) => t.taskId === taskDetail.id)
                    .reduce((n, t) => n + Number(t.hours), 0)}{' '}
                  hours logged
                </span>
              </div>
              <hr />
              <h3>Activity</h3>
              <div className="activity-list">
                {snapshot.audit
                  .filter((a) => (a.entityId || a.entity_id) === taskDetail.id)
                  .slice(0, 5)
                  .map((a) => (
                    <div key={a.id} className="activity-item">
                      <span className="activity-dot" />
                      <div>
                        <strong>{label(a.action)}</strong>
                        <p>
                          {a.actorName || a.actor_name} | {prettyDate(a.createdAt || a.created_at)}
                        </p>
                      </div>
                    </div>
                  ))}
                {!snapshot.audit.some((a) => (a.entityId || a.entity_id) === taskDetail.id) && (
                  <p className="muted">No accessible activity recorded yet.</p>
                )}
              </div>
              <h3>Discussion</h3>
              <div className="comment-list">
                {snapshot.comments
                  .filter((c) => c.taskId === taskDetail.id)
                  .map((c) => (
                    <div className="comment" key={c.id}>
                      <span className="avatar small">{initials(c.name)}</span>
                      <div>
                        <strong>{c.name}</strong>
                        <small>{prettyDate(c.createdAt)}</small>
                        <p>{c.body}</p>
                      </div>
                    </div>
                  ))}
                {!snapshot.comments.some((c) => c.taskId === taskDetail.id) && (
                  <p className="muted">Start a useful conversation.</p>
                )}
              </div>
              {writable && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    run(async () => {
                      await mutate('comment', {
                        taskId: taskDetail.id,
                        body: comment,
                        mentionUserIds: commentMentions,
                      });
                      setComment('');
                      setCommentMentions([]);
                    }, 'Comment added');
                  }}
                >
                  <label className="sr-only" htmlFor="comment-body">
                    Comment
                  </label>
                  <textarea
                    id="comment-body"
                    placeholder="Add a thoughtful comment…"
                    rows={3}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    required
                  />
                  <FieldInput
                    field={{
                      key: 'commentMentions',
                      label: 'Notify project colleagues',
                      type: 'multi',
                      options: snapshot.users
                        .filter(
                          (u) =>
                            u.status === 'ACTIVE' &&
                            [
                              ...(snapshot.projects.find((p) => p.id === taskDetail.projectId)
                                ?.memberIds || []),
                              snapshot.projects.find((p) => p.id === taskDetail.projectId)?.ownerId,
                            ].includes(u.id)
                        )
                        .map((u) => ({ value: u.id, label: u.name })),
                      hint: 'Optional. Mentioned colleagues receive a scoped notification.',
                    }}
                    value={commentMentions}
                    onChange={setCommentMentions}
                  />
                  <div className="modal-footer">
                    <button className="button primary" type="submit">
                      Post comment <Icon name="arrow" size={16} />
                    </button>
                  </div>
                </form>
              )}
            </div>
          </section>
        </div>
      )}
      {dialog && (
        <FormDialog
          key={dialog.title + (dialog.values?.id || '')}
          dialog={dialog}
          onClose={() => setDialog(null)}
        />
      )}
      {toast && (
        <div
          className={'toast ' + (toast.error ? 'error' : '')}
          role={toast.error ? 'alert' : 'status'}
        >
          <Icon name={toast.error ? 'info' : 'check'} size={18} />
          <span>{toast.message}</span>
          <button aria-label="Dismiss notification" onClick={() => setToast(null)}>
            <Icon name="close" size={16} />
          </button>
        </div>
      )}
    </div>
  );
}

function ProfileForm({
  profile,
  disabled,
  onSave,
  onUpload,
}: {
  profile: any;
  disabled: boolean;
  onSave: (v: any) => Promise<void>;
  onUpload: (dataUrl: string) => Promise<string>;
}) {
  const [values, setValues] = useState({
    currency: 'INR',
    country: 'IN',
    locale: 'en-IN',
    fiscalYearStartMonth: 4,
    ...profile,
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [logoData, setLogoData] = useState('');
  const [logoName, setLogoName] = useState('');
  const fields: Field[] = [
    { key: 'name', label: 'Display name', required: true },
    { key: 'legalName', label: 'Legal name', required: true },
    { key: 'email', label: 'Company email', type: 'email', required: true },
    { key: 'phone', label: 'Phone', type: 'tel' },
    { key: 'address', label: 'Address', type: 'textarea' },
    { key: 'timezone', label: 'Timezone', required: true },
    { key: 'currency', label: 'Default currency', required: true },
    { key: 'country', label: 'Country code', required: true },
    { key: 'locale', label: 'Display locale', required: true },
    {
      key: 'fiscalYearStartMonth',
      label: 'Financial year starts in month',
      type: 'number',
      required: true,
      min: 1,
      max: 12,
    },
    { key: 'gstin', label: 'GSTIN (if registered)' },
  ];
  const preview =
    logoData ||
    (/^data:image\/(png|jpeg|webp);base64,/.test(values.logoKey || '') ||
    values.logoKey === '/api/v1/org/logo'
      ? values.logoKey
      : '');
  async function chooseLogo(file?: File) {
    if (!file) return;
    setError('');
    try {
      if (file.size > 512 * 1024) throw new Error('Choose a logo no larger than 512 KB.');
      const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer());
      const isPng =
        bytes.length >= 8 && [137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => bytes[i] === v);
      const isJpeg = bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
      const isWebp =
        bytes.length >= 12 &&
        String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF' &&
        String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP';
      const mime = isPng ? 'image/png' : isJpeg ? 'image/jpeg' : isWebp ? 'image/webp' : '';
      if (!mime) throw new Error('Choose a valid PNG, JPEG or WebP image.');
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () =>
          reject(new Error('The image could not be read. Please choose it again.'));
        reader.readAsDataURL(file);
      });
      setLogoData('data:' + mime + ';base64,' + dataUrl.split(',')[1]);
      setLogoName(file.name);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <form
      className="profile-form"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError('');
        try {
          const logoKey = logoData ? await onUpload(logoData) : values.logoKey;
          await onSave({ ...values, logoKey });
          setLogoData('');
          setLogoName('');
        } catch (e) {
          setError((e as Error).message);
        } finally {
          setBusy(false);
        }
      }}
    >
      <fieldset disabled={disabled || busy}>
        <div className="company-avatar">
          {preview ? (
            <img
              alt="Company logo preview"
              src={preview}
              style={{ height: '100%', width: '100%', objectFit: 'contain', borderRadius: 12 }}
            />
          ) : (
            initials(values.name)
          )}
        </div>
        <div className="field" style={{ marginBottom: 24 }}>
          <label htmlFor="company-logo-file">Company logo</label>
          <input
            id="company-logo-file"
            type="file"
            accept="image/png,image/jpeg,image/webp"
            onChange={(e) => {
              void chooseLogo(e.target.files?.[0]);
              e.target.value = '';
            }}
          />
          <small>
            {logoName
              ? 'Selected: ' + logoName + ' - uploads when you save.'
              : 'PNG, JPEG or WebP. Maximum 512 KB.'}
          </small>
          {logoData && (
            <Button
              kind="ghost"
              onClick={() => {
                setLogoData('');
                setLogoName('');
              }}
            >
              Cancel logo selection
            </Button>
          )}
        </div>
        <div className="form-grid">
          {fields.map((field) => (
            <FieldInput
              key={field.key}
              field={field}
              value={values[field.key]}
              onChange={(v) => setValues({ ...values, [field.key]: v })}
            />
          ))}
        </div>
        {error && (
          <p role="alert" className="inline-error">
            {error}
          </p>
        )}
        <div className="modal-footer">
          <button type="submit" className="button primary" disabled={disabled || busy}>
            {busy ? 'Saving...' : 'Save company profile'}
          </button>
        </div>
      </fieldset>
    </form>
  );
}
