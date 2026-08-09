import { useEffect } from 'react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  busy?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmDialog({ open, title, description, confirmLabel, busy, onCancel, onConfirm }: ConfirmDialogProps) {
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
        <div className="dialog-actions">
          <button type="button" className="button secondary" onClick={onCancel} disabled={busy}>Cancelar</button>
          <button type="button" className="button danger" onClick={onConfirm} disabled={busy}>
            {busy ? 'Aguarde…' : confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
