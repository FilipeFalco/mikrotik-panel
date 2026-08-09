import { formatDate } from '../format';
import type { AuditLog } from '../types';

interface HistoryProps {
  entries: AuditLog[];
}

const actionLabel: Record<string, string> = {
  DEVICE_BLOCKED: 'Dispositivo bloqueado',
  DEVICE_UNBLOCKED: 'Dispositivo liberado',
  DEVICE_SPEED_CHANGED: 'Velocidade do dispositivo alterada',
  PORT_SPEED_CHANGED: 'Limite da porta alterado',
};

export function History({ entries }: HistoryProps) {
  return (
    <div className="page-stack">
      <section className="page-heading"><div><p className="eyebrow">Auditoria local</p><h1>Histórico</h1><p>Alterações administrativas feitas pelo painel.</p></div></section>
      <section className="surface timeline">
        {entries.length ? entries.map((entry) => (
          <article className="timeline-entry" key={entry.id}>
            <span className={entry.success ? 'timeline-marker success' : 'timeline-marker error'} aria-hidden="true" />
            <div><strong>{actionLabel[entry.action] ?? entry.action}</strong><p>{entry.targetIdentifier}</p>{entry.previousValue !== entry.newValue && <small>{entry.previousValue ?? '—'} → {entry.newValue ?? '—'}</small>}{entry.errorMessage && <small className="error-text">{entry.errorMessage}</small>}</div>
            <time>{formatDate(entry.createdAt)}</time>
          </article>
        )) : <div className="empty-state compact-empty"><p>Nenhuma operação administrativa registrada.</p></div>}
      </section>
    </div>
  );
}
