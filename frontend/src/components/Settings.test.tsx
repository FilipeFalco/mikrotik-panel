import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { Settings } from './Settings';
import type { Port, ReconciliationReport, SystemStatus, WriteReadinessReport } from '../types';

const systemStatus: SystemStatus = {
  connected: true,
  mockMode: false,
  readOnly: true,
  host: '192.168.88.1',
  port: 443,
  routerOsVersion: '7.18.2',
  latencyMillis: 8,
  message: 'Conectado',
  fastTrackDetected: true,
};

const port: Port = {
  interfaceName: 'ether2', friendlyName: 'Clientes', description: null, network: '10.10.10.0/24', dhcpServer: 'dhcp-clientes', role: 'CLIENT',
  managed: true, enabled: true, running: true, disabled: false, deviceCount: 0, onlineDeviceCount: 0, blockedDeviceCount: 0,
  downloadLimitBps: 0, uploadLimitBps: 0, downloadTrafficBps: 0, uploadTrafficBps: 0, devices: [],
};

const readiness: WriteReadinessReport = {
  generatedAt: '2026-08-09T12:00:00Z', mockMode: false, writeFlagEnabled: false, readyForFutureExecution: false, executionEnabled: false,
  phaseNotice: 'A execução RouterOS permanece desabilitada na Fase 3.',
  checks: [
    { code: 'ROUTEROS_CONNECTED', description: 'RouterOS conectado', satisfied: true, severity: 'INFO', detail: 'Leitura confirmada.' },
    { code: 'FASTTRACK_BANDWIDTH_WARNING', description: 'Impacto do FastTrack em banda', satisfied: false, severity: 'WARNING', detail: 'FastTrack ativo.' },
  ],
  summary: { managed: 1, foreign: 1, inSync: 0, drifted: 0, missing: 0, conflicts: 1, ambiguous: 0, managedPorts: 1, validManagedPorts: 1 },
};

const reconciliation: ReconciliationReport = {
  generatedAt: '2026-08-09T12:00:00Z', snapshotFingerprint: 'fingerprint', observedInterfaceCount: 2, observedDhcpServerCount: 1,
  observedLeaseCount: 1, observedSimpleQueueCount: 1, fastTrackDetected: true,
  resources: [{
    resourceType: 'SIMPLE_QUEUE', resourceKey: 'ether2', displayName: 'Queue da porta ether2', ownership: 'FOREIGN', status: 'CONFLICT',
    expectedName: 'mtmgr-port-ether2', expectedTarget: '10.10.10.0/24', observedName: 'Cliente João Limit', observedTarget: '10.10.10.0/24', conflict: true,
    findings: [{ code: 'FOREIGN_QUEUE_CONFLICT', description: 'Fila manual encontrada.', severity: 'BLOCKING' }],
  }],
  summary: { managed: 0, foreign: 1, inSync: 0, drifted: 0, missing: 0, conflicts: 1, ambiguous: 0, notApplicable: 0 },
};

it('shows readiness, reconciliation statuses and foreign conflict without an automatic action', () => {
  const onAnalyzeRouterOS = vi.fn();
  render(<Settings systemStatus={systemStatus} diagnostics={null} readiness={readiness} reconciliation={reconciliation} ports={[port]} testing={false} saving={false} onTestConnection={vi.fn()} onAnalyzeRouterOS={onAnalyzeRouterOS} onSavePort={async () => port} />);

  expect(screen.getByRole('heading', { name: 'Preparação para futuras alterações' })).toBeInTheDocument();
  expect(screen.getByText('Write flag:')).toBeInTheDocument();
  expect(screen.getAllByText('Desabilitada')).toHaveLength(2);
  expect(screen.getByText('CONFLICT')).toBeInTheDocument();
  expect(screen.getByText('FOREIGN_QUEUE_CONFLICT')).toBeInTheDocument();
  expect(screen.getByText('Configuração RouterOS manual conflitante detectada.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /adotar|reparar|executar/i })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Atualizar análise' }));
  expect(onAnalyzeRouterOS).toHaveBeenCalledOnce();
});

it('keeps the costly reconciliation explicitly on demand before the first analysis', () => {
  const onAnalyzeRouterOS = vi.fn();
  render(<Settings systemStatus={systemStatus} diagnostics={null} ports={[port]} testing={false} saving={false} onTestConnection={vi.fn()} onAnalyzeRouterOS={onAnalyzeRouterOS} onSavePort={async () => port} />);

  expect(screen.getByText('A reconciliação é executada somente quando você solicita a análise. Ela compara metadata local e o snapshot atual sem corrigir, adotar ou remover recursos.')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Analisar RouterOS' }));
  expect(onAnalyzeRouterOS).toHaveBeenCalledOnce();
});
