import { fireEvent, render, screen } from '@testing-library/react';
import { it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog';

it('requires an explicit confirmation before the destructive action', () => {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(<ConfirmDialog open title="Bloquear Notebook João?" description="O dispositivo perderá acesso." confirmLabel="Bloquear" onCancel={onCancel} onConfirm={onConfirm} />);
  fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
  expect(onConfirm).not.toHaveBeenCalled();
  expect(onCancel).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: 'Bloquear' }));
  expect(onConfirm).toHaveBeenCalledOnce();
});
