import React, { useEffect, useState } from 'react';
import { api, Row } from './data';
import { Badge, Button, Dialog, opts, Table } from './ui';
const modules = [
  'HR',
  'PROJECTS',
  'CRM',
  'INVENTORY',
  'FINANCE',
  'PAYROLL',
  'WORKFLOWS',
  'DOCUMENTS',
  'REPORTS',
  'INTEGRATIONS',
];
export function FeatureFlags({
  setDialog,
  notify,
  onChanged,
}: {
  setDialog: (v: Dialog | null) => void;
  notify: (message: string, error?: boolean) => void;
  onChanged: () => Promise<void>;
}) {
  const [rows, setRows] = useState<Row[]>([]),
    [error, setError] = useState('');
  const load = async () => {
    try {
      const data = await api('/org/feature-flags');
      setRows(data.map((r: any) => ({ id: r.key, ...r })));
      setError('');
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  const edit = (row?: Row) =>
    setDialog({
      title: row ? 'Change module feature flag' : 'Configure module feature flag',
      description:
        'Feature flags are separate from plan entitlements. Disabling a module also blocks its protected server operations.',
      fields: [
        { key: 'key', label: 'Module', type: 'select', required: true, options: opts(modules) },
        {
          key: 'enabled',
          label: 'Enabled',
          type: 'select',
          required: true,
          options: [
            { value: 'true', label: 'Enabled' },
            { value: 'false', label: 'Disabled' },
          ],
        },
        { key: 'description', label: 'Reason or description', type: 'textarea', required: true },
      ],
      values: { enabled: 'true', ...row, ...(row ? { enabled: String(row.enabled) } : {}) },
      onSubmit: async (v) => {
        await api('/org/feature-flags/' + encodeURIComponent(v.key), row ? 'PUT' : 'POST', {
          enabled: v.enabled === 'true',
          description: v.description,
          version: row?.version,
        });
        await load();
        await onChanged();
        notify('Module feature flag updated');
      },
    });
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>Module feature flags</h2>
          <p>Effective company module controls</p>
        </div>
        <Button kind="primary" icon="plus" onClick={() => edit()}>
          Configure module
        </Button>
      </div>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
      <Table
        rows={rows}
        columns={[
          { key: 'key', title: 'Module' },
          {
            key: 'enabled',
            title: 'State',
            render: (r) => <Badge value={r.enabled ? 'ENABLED' : 'DISABLED'} />,
          },
          { key: 'description', title: 'Description' },
        ]}
        actions={(r) => <Button onClick={() => edit(r)}>Change</Button>}
      />
    </section>
  );
}
