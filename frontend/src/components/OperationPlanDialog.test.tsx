import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { OperationPlanDialog } from './OperationPlanDialog';
import type { OperationPlan } from '../types';

const plan: OperationPlan = {
  planId: 'plan-1',
  operationType: 'SET_PORT_SPEED',
  target: { identifier: 'ether2', displayName: 'Clientes', interfaceName: 'ether2', macAddress: null },
  currentState: { interfaceName: 'ether2', network: '10.10.10.0/24', speedLimit: { downloadBps: 100_000_000, uploadBps: 20_000_000 }, blocked: null },
  desiredState: { interfaceName: 'ether2', network: '10.10.10.0/24', speedLimit: { downloadBps: 50_000_000, uploadBps: 10_000_000 }, blocked: null },
  ownership: 'MANAGED',
  preconditions: [{ code: 'PORT_EXISTS', description: 'A porta existe.', satisfied: true, severity: 'INFO' }],
  warnings: [{ code: 'FASTTRACK_ACTIVE', description: 'FastTrack precisa de revisão.', severity: 'WARNING' }],
  conflicts: [{ code: 'FOREIGN_QUEUE_CONFLICT', resourceType: 'SIMPLE_QUEUE', resourceName: 'Fila manual', resourceTarget: '10.10.10.0/24', ownership: 'FOREIGN', description: 'Não será adotada.', severity: 'BLOCKING' }],
  plannedChanges: [{ action: 'NO_ACTION', resourceType: 'SIMPLE_QUEUE', description: 'Nenhuma alteração será enviada.' }],
  changeRequired: false,
  readyForFutureExecution: false,
  executable: false,
  executionDisabledReason: 'Phase 3 is dry-run only; RouterOS execution is not implemented.',
  generatedAt: '2026-08-09T12:00:00Z',
  snapshotFingerprint: 'fingerprint',
};

const blockPlan: OperationPlan = {
  planId: 'block-plan-1',
  operationType: 'BLOCK_DEVICE',
  target: { identifier: 'AA:BB:CC:DD:EE:01', displayName: 'Notebook João', interfaceName: 'ether2', macAddress: 'AA:BB:CC:DD:EE:01' },
  currentState: { displayName: 'Notebook João', macAddress: 'AA:BB:CC:DD:EE:01', ipAddress: '10.10.10.21', interfaceName: 'ether2', blocked: false },
  desiredState: { displayName: 'Notebook João', macAddress: 'AA:BB:CC:DD:EE:01', ipAddress: '10.10.10.21', interfaceName: 'ether2', blocked: true },
  ownership: 'MANAGED',
  preconditions: [{ code: 'DEVICE_EXISTS', description: 'O dispositivo existe.', satisfied: true, severity: 'INFO' }],
  warnings: [{ code: 'FASTTRACK_ACTIVE', description: 'FastTrack pode afetar o tráfego deste dispositivo.', severity: 'WARNING' }],
  conflicts: [],
  plannedChanges: [{ action: 'BLOCK', resourceType: 'DEVICE_BLOCK', description: 'Bloquear o dispositivo após revalidação.' }],
  changeRequired: true,
  readyForFutureExecution: true,
  executable: false,
  executionDisabledReason: '',
  generatedAt: '2026-08-09T12:00:00Z',
  snapshotFingerprint: 'block-fingerprint',
};

it('shows a clearly identified dry-run without an execution or confirmation control', () => {
  const onClose = vi.fn();
  const onRefresh = vi.fn();
  render(<OperationPlanDialog open plan={plan} loading={false} onClose={onClose} onRefresh={onRefresh} />);

  expect(screen.getByText('SIMULAÇÃO — nenhuma alteração será enviada ao MikroTik')).toBeInTheDocument();
  expect(screen.getByText('Desabilitada na Fase 3')).toBeInTheDocument();
  expect(screen.getByText('SIMPLE_QUEUE · Fila manual')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Fechar' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Atualizar análise' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /executar|confirmar/i })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Atualizar análise' }));
  expect(onRefresh).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: 'Fechar' }));
  expect(onClose).toHaveBeenCalledOnce();
});

it('shows the device identity, ownership, FastTrack warning and explicit block confirmation', () => {
  const onConfirm = vi.fn();
  render(<OperationPlanDialog open plan={blockPlan} loading={false} executionEnabled onConfirm={onConfirm} onClose={vi.fn()} onRefresh={vi.fn()} />);

  expect(screen.getByText('AA:BB:CC:DD:EE:01')).toBeInTheDocument();
  expect(screen.getByText('ether2')).toBeInTheDocument();
  expect(screen.getByText('MANAGED')).toBeInTheDocument();
  expect(screen.getByText('FASTTRACK_ACTIVE')).toBeInTheDocument();
  expect(screen.getByText('FastTrack pode afetar o tráfego deste dispositivo.')).toBeInTheDocument();
  expect(screen.getByText('Aviso de revalidação obrigatória')).toBeInTheDocument();

  const confirmButton = screen.getByRole('button', { name: 'Confirmar bloqueio' });
  expect(confirmButton).toBeEnabled();
  fireEvent.click(confirmButton);
  expect(onConfirm).toHaveBeenCalledWith(blockPlan);
});

it('uses an explicit release confirmation label for an unblock plan', () => {
  const onConfirm = vi.fn();
  const unblockPlan: OperationPlan = {
    ...blockPlan,
    planId: 'unblock-plan-1',
    operationType: 'UNBLOCK_DEVICE',
    desiredState: { ...blockPlan.desiredState, blocked: false },
  };
  render(<OperationPlanDialog open plan={unblockPlan} loading={false} executionEnabled onConfirm={onConfirm} onClose={vi.fn()} onRefresh={vi.fn()} />);

  expect(screen.getByRole('button', { name: 'Confirmar liberação' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar liberação' }));
  expect(onConfirm).toHaveBeenCalledWith(unblockPlan);
});

const nonConfirmablePlans: Array<[string, Partial<OperationPlan>]> = [
  ['precondition BLOCKING', {
    preconditions: [{ code: 'BLOCKING_CHECK', description: 'Falhou.', satisfied: false, severity: 'BLOCKING' as const }],
  }],
  ['conflict BLOCKING', {
    conflicts: [{ code: 'FOREIGN_RESOURCE', resourceType: 'FIREWALL', resourceName: 'manual-rule', resourceTarget: '10.10.10.21', ownership: 'FOREIGN' as const, description: 'Recurso externo.', severity: 'BLOCKING' as const }],
  }],
  ['no-op', { changeRequired: false }],
];

it.each(nonConfirmablePlans)('disables confirmation for %s', (_reason, override) => {
  render(
    <OperationPlanDialog
      open
      plan={{ ...blockPlan, ...override }}
      loading={false}
      executionEnabled
      onConfirm={vi.fn()}
      onClose={vi.fn()}
      onRefresh={vi.fn()}
    />,
  );

  expect(screen.getByRole('button', { name: 'Confirmar bloqueio' })).toBeDisabled();
});
