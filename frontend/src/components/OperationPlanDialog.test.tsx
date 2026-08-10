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
