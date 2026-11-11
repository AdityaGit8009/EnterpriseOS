import React, { useEffect, useState } from 'react';
import { api, Principal, Row, uid } from './data';
import { Badge, Button, Dialog, Empty, opts, Table } from './ui';

export function DashboardWidgets({
  principal,
  setDialog,
  notify,
}: {
  principal: Principal;
  setDialog: (value: Dialog | null) => void;
  notify: (message: string, error?: boolean) => void;
}) {
  const [config, setConfig] = useState<any>({ widgets: [], version: 0 }),
    [widgets, setWidgets] = useState<Row[]>([]),
    [error, setError] = useState('');
  const load = async () => {
    try {
      const [c, d] = await Promise.all([
        api('/org/dashboard/widgets'),
        api('/org/dashboard/widgets/data'),
      ]);
      setConfig(c);
      setWidgets(d);
      setError('');
    } catch (e) {
      setError((e as Error).message);
    }
  };
  useEffect(() => {
    void load();
  }, [principal.userId]);
  const sources = [
    'projects',
    ...(['ADMIN', 'HR', 'AUDITOR'].includes(principal.role) ? ['employees'] : []),
    ...(['ADMIN', 'HR', 'MANAGER', 'MEMBER', 'AUDITOR'].includes(principal.role)
      ? ['attendance', 'leaves']
      : []),
    ...(['ADMIN', 'FINANCE', 'AUDITOR'].includes(principal.role) ? ['invoices'] : []),
    ...(['ADMIN', 'PROCUREMENT', 'AUDITOR'].includes(principal.role) ? ['inventory'] : []),
  ];
  const edit = () =>
    setDialog({
      title: 'Customize your dashboard',
      description:
        'Choose metric, chart and list widgets from sources your account may access. Lower order numbers appear first.',
      fields: [
        {
          key: 'widgets',
          label: 'Dashboard widgets',
          type: 'repeater',
          defaultRow: { id: uid(), title: '', type: 'METRIC', source: 'projects', order: 0 },
          fields: [
            { key: 'title', label: 'Widget title', required: true },
            {
              key: 'type',
              label: 'Display',
              type: 'select',
              required: true,
              options: opts(['METRIC', 'CHART', 'LIST']),
            },
            {
              key: 'source',
              label: 'Source records',
              type: 'select',
              required: true,
              options: opts(sources),
            },
            {
              key: 'order',
              label: 'Display order',
              type: 'number',
              required: true,
              min: 0,
              max: 100,
            },
          ],
        },
      ],
      values: { version: config.version, widgets: config.widgets || [] },
      submitLabel: 'Save layout',
      onSubmit: async (v) => {
        const ids = new Set();
        const normalized = (v.widgets || []).map((w: Row) => {
          let id = w.id;
          if (!id || ids.has(id)) id = uid();
          ids.add(id);
          return { ...w, id };
        });
        await api('/org/dashboard/widgets', 'PUT', {
          version: config.version,
          widgets: normalized,
        });
        await load();
        notify('Your dashboard layout was saved');
      },
    });
  return (
    <section className="panel dashboard-widgets">
      <div className="panel-heading">
        <div>
          <h2>Your dashboard widgets</h2>
          <p>Your saved layout uses current, permission-aware company records</p>
        </div>
        <div className="row-actions">
          <Button icon="refresh" onClick={() => void load()}>
            Refresh
          </Button>
          <Button icon="settings" onClick={edit}>
            Customize
          </Button>
        </div>
      </div>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
      {widgets.length ? (
        <div className="widget-grid">
          {widgets.map((widget) => (
            <section className="widget" key={widget.id}>
              <h3>{widget.title}</h3>
              <small>{widget.source}</small>
              {widget.error ? (
                <p className="inline-error">{widget.error}</p>
              ) : widget.type === 'METRIC' ? (
                <strong className="widget-metric">{widget.data?.count ?? 0}</strong>
              ) : widget.type === 'CHART' ? (
                <div className="widget-chart">
                  {(widget.data?.series || []).map((point: any) => {
                    const max = Math.max(1, ...widget.data.series.map((p: any) => Number(p.value)));
                    return (
                      <div className="widget-chart-row" key={point.label}>
                        <span>{point.label}</span>
                        <div>
                          <i style={{ width: (Number(point.value) / max) * 100 + '%' }} />
                        </div>
                        <strong>{point.value}</strong>
                      </div>
                    );
                  })}
                  {!widget.data?.series?.length && <p>No grouped records yet.</p>}
                </div>
              ) : (
                <Table
                  rows={widget.data?.rows || []}
                  columns={[
                    { key: 'name', title: 'Record' },
                    { key: 'status', title: 'State', render: (r) => <Badge value={r.status} /> },
                  ]}
                  emptyText="No authorized records match this widget."
                />
              )}
            </section>
          ))}
        </div>
      ) : (
        <Empty
          title="Choose your dashboard widgets"
          text="Add a metric, chart or list using your company’s actual records."
        />
      )}
    </section>
  );
}
