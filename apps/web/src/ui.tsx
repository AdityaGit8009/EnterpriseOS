import React, { useEffect, useRef, useState, useId } from 'react';
import { Icon } from './icons';
import { Row } from './data';
export type Option = { value: string; label: string };
export type Field = {
  key: string;
  label: string;
  type?: string;
  required?: boolean;
  options?: Option[];
  hint?: string;
  min?: number;
  max?: number;
  fields?: Field[];
  defaultRow?: Record<string, any>;
  accept?: string;
  maxLength?: number;
};
export type Dialog = {
  content?: React.ReactNode;
  title: string;
  description?: string;
  fields: Field[];
  values?: Record<string, any>;
  submitLabel?: string;
  onSubmit: (v: any) => Promise<void> | void;
};
export const label = (s: any) =>
  String(s ?? '—')
    .replaceAll('_', ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
export const prettyDate = (s: any) =>
  s
    ? new Date(s).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
    : '—';
export const prettyTime = (s: any) =>
  s ? new Date(s).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';
export const initials = (s: any) =>
  String(s || 'EO')
    .split(' ')
    .slice(0, 2)
    .map((x) => x[0])
    .join('')
    .toUpperCase();
export const opts = (a: string[]) => a.map((value) => ({ value, label: label(value) }));
export function Badge({ value }: { value: any }) {
  const v = String(value || '');
  return (
    <span
      className={
        'badge ' +
        (/ACTIVE|APPROVED|COMPLETE|DONE/.test(v) && !v.includes('INACTIVE')
          ? 'success'
          : /REJECTED|OFFBOARDED|HIGH|SUSPENDED/.test(v)
            ? 'danger'
            : /PENDING|REVIEW|ONBOARDING|MEDIUM/.test(v)
              ? 'warning'
              : 'neutral')
      }
    >
      {label(v || 'Not set')}
    </span>
  );
}
export function Button({
  children,
  icon,
  onClick,
  kind = '',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { icon?: string; kind?: string }) {
  return (
    <button type="button" className={'button ' + kind} onClick={onClick} {...props}>
      {icon && <Icon name={icon} size={17} />} {children}
    </button>
  );
}
export function Empty({
  title = 'Nothing here yet',
  text = 'Create your first record to get started.',
}: {
  title?: string;
  text?: string;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <Icon name="file" size={28} />
      </span>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}
export function FieldInput({
  field,
  value,
  onChange,
}: {
  field: Field;
  value: any;
  onChange: (v: any) => void;
}) {
  const id = 'field-' + field.key + '-' + useId();
  return (
    <div
      className={
        'field ' +
        (field.type === 'textarea' || field.type === 'multi' || field.type === 'repeater'
          ? 'full'
          : '')
      }
    >
      <label htmlFor={id}>
        {field.label}
        {field.required && <span aria-hidden="true"> *</span>}
      </label>
      {field.type === 'file' ? (
        <input
          id={id}
          type="file"
          accept={field.accept}
          required={field.required}
          onChange={(e) => onChange(e.target.files?.[0] || null)}
        />
      ) : field.type === 'repeater' ? (
        <div className="repeater">
          {(Array.isArray(value) ? value : []).map((row: any, index: number) => (
            <div className="repeater-row" key={index}>
              <div className="form-grid">
                {field.fields?.map((child) => (
                  <FieldInput
                    key={child.key}
                    field={child}
                    value={row[child.key]}
                    onChange={(v) =>
                      onChange(
                        value.map((r: any, i: number) =>
                          i === index ? { ...r, [child.key]: v } : r
                        )
                      )
                    }
                  />
                ))}
              </div>
              <Button
                kind="ghost"
                icon="close"
                aria-label={'Remove ' + field.label + ' row ' + (index + 1)}
                onClick={() => onChange(value.filter((_: any, i: number) => i !== index))}
              >
                Remove
              </Button>
            </div>
          ))}
          <Button
            icon="plus"
            onClick={() =>
              onChange([...(Array.isArray(value) ? value : []), { ...field.defaultRow }])
            }
          >
            Add row
          </Button>
        </div>
      ) : field.type === 'textarea' ? (
        <textarea
          id={id}
          value={value ?? ''}
          required={field.required}
          rows={3}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : field.type === 'select' || field.type === 'multi' ? (
        <select
          id={id}
          multiple={field.type === 'multi'}
          value={value ?? (field.type === 'multi' ? [] : '')}
          required={field.required}
          onChange={(e) =>
            onChange(
              field.type === 'multi'
                ? Array.from(e.target.selectedOptions, (o) => o.value)
                : e.target.value
            )
          }
        >
          {field.type !== 'multi' && <option value="">Select {field.label.toLowerCase()}</option>}
          {field.options?.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      ) : (
        <input
          id={id}
          type={field.type || 'text'}
          value={value ?? ''}
          min={field.min}
          max={field.max}
          maxLength={field.maxLength}
          required={field.required}
          step={field.type === 'number' ? 'any' : undefined}
          onChange={(e) =>
            onChange(
              field.type === 'number'
                ? e.target.value === ''
                  ? ''
                  : Number(e.target.value)
                : e.target.value
            )
          }
        />
      )}{' '}
      {field.hint && <small>{field.hint}</small>}
    </div>
  );
}
export function FormDialog({ dialog, onClose }: { dialog: Dialog; onClose: () => void }) {
  const [values, setValues] = useState(dialog.values || {});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const old = document.activeElement as HTMLElement;
    dialogRef.current?.querySelector<HTMLElement>('input,select,textarea,button')?.focus();
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onClose();
      if (e.key === 'Tab') {
        const nodes = dialogRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]),input,select,textarea'
        );
        if (!nodes?.length) return;
        const first = nodes[0],
          last = nodes[nodes.length - 1];
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
  }, [busy]);
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onClose();
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialog-title"
        ref={dialogRef}
      >
        <div className="modal-header">
          <div>
            <h2 id="dialog-title">{dialog.title}</h2>
            {dialog.description && <p>{dialog.description}</p>}
          </div>
          <Button icon="close" aria-label="Close dialog" onClick={onClose} disabled={busy} />
        </div>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError('');
            try {
              await dialog.onSubmit(values);
              onClose();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          {dialog.content && <div className="dialog-content">{dialog.content}</div>}
          <div className="form-grid">
            {dialog.fields.map((field) => (
              <FieldInput
                key={field.key}
                field={field}
                value={values[field.key]}
                onChange={(v) => setValues({ ...values, [field.key]: v })}
              />
            ))}
          </div>
          {error && (
            <p className="inline-error" role="alert">
              {error}
            </p>
          )}
          <div className="modal-footer">
            <Button onClick={onClose} disabled={busy}>
              Cancel
            </Button>
            <button className="button primary" type="submit" disabled={busy}>
              {busy ? 'Saving…' : dialog.submitLabel || 'Save changes'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
export type Col = { key: string; title: string; render?: (row: Row) => React.ReactNode };
export function Table({
  rows,
  columns,
  search = '',
  actions,
  emptyText,
}: {
  rows: Row[];
  columns: Col[];
  search?: string;
  actions?: (r: Row) => React.ReactNode;
  emptyText?: string;
}) {
  const [page, setPage] = useState(0);
  const filtered = rows.filter(
    (r) => !search || JSON.stringify(r).toLowerCase().includes(search.toLowerCase())
  );
  useEffect(() => setPage(0), [search, rows.length]);
  const pages = Math.ceil(filtered.length / 8),
    actual = Math.min(page, Math.max(0, pages - 1));
  return (
    <>
      {!filtered.length ? (
        <Empty
          title="No records found"
          text={emptyText || 'Try another search, or add a new record.'}
        />
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  {columns.map((c) => (
                    <th key={c.key}>{c.title}</th>
                  ))}
                  {actions && <th className="actions-cell">Actions</th>}
                </tr>
              </thead>
              <tbody>
                {filtered.slice(actual * 8, actual * 8 + 8).map((row, index) => (
                  <tr key={row.id || index}>
                    {columns.map((c) => (
                      <td key={c.key}>{c.render ? c.render(row) : String(row[c.key] ?? '—')}</td>
                    ))}
                    {actions && (
                      <td className="actions-cell">
                        <div className="row-actions">{actions(row)}</div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="table-footer">
            <span>
              Showing {actual * 8 + 1}–{Math.min(actual * 8 + 8, filtered.length)} of{' '}
              {filtered.length}
            </span>
            <div>
              <Button
                aria-label="Previous page"
                disabled={!actual}
                onClick={() => setPage(actual - 1)}
              >
                Previous
              </Button>
              <Button
                aria-label="Next page"
                disabled={actual >= pages - 1}
                onClick={() => setPage(actual + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
