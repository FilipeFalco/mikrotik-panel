import { useEffect } from 'react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  busy?: boolean;
  confirmDisabled?: boolean;
  confirmDisabledReason?: string;
  details?: Array<{ label: string; value: string }>;
  warnings?: string[];
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  busy,
  confirmDisabled = false,
  confirmDisabledReason,
  details = [],
  warnings = [],
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => event.key === 'Escape' && onCancel();
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <div className="modal-backdrop" role="presentation">
      <section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title">{title}</h2>
        <p>{description}</p>
        {details.length > 0 && <dl className="confirm-details">
          {details.map((detail) => <div key={detail.label}><dt>{detail.label}</dt><dd>{detail.value}</dd></div>)}
        </dl>}
        {warnings.length > 0 && <div className="notice warning confirm-warnings">
          <strong>Avisos do preview</strong>
          <ul>{warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
        </div>}
        <p className="confirm-routeros-warning">Esta operação irá alterar o firewall do MikroTik. O estado será validado novamente antes da alteração.</p>
        {confirmDisabled && confirmDisabledReason && <p className="field-error" role="alert">{confirmDisabledReason}</p>}
        <div className="dialog-actions">
          <button type="button" className="button secondary" onClick={onCancel} disabled={busy}>Cancelar</button>
          <button type="button" className="button danger" onClick={onConfirm} disabled={busy || confirmDisabled}>
            {busy ? 'Aguarde…' : confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
