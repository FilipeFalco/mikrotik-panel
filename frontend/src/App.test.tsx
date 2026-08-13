import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import App from './App';
import type { Device, OperationPlan, Port, SystemStatus } from './types';

const disconnectedStatus: SystemStatus = {
  connected: false,
  mockMode: false,
  readOnly: true,
  host: '192.168.88.1',
  port: 443,
  routerOsVersion: null,
  latencyMillis: null,
  message: 'RouterOS indisponível',
  fastTrackDetected: false,
};

const connectedReadOnlyStatus: SystemStatus = {
  ...disconnectedStatus,
  connected: true,
  routerOsVersion: '7.16.2',
  latencyMillis: 12,
  message: 'Conectado',
};

const ports: Port[] = [{
  interfaceName: 'ether2',
  friendlyName: 'Clientes',
  description: null,
  network: '10.10.10.0/24',
  dhcpServer: 'dhcp-clientes',
  role: 'CLIENT',
  managed: true,
  enabled: true,
  running: true,
  disabled: false,
  deviceCount: 1,
  onlineDeviceCount: 1,
  blockedDeviceCount: 0,
  downloadLimitBps: 0,
  uploadLimitBps: 0,
  downloadTrafficBps: 0,
  uploadTrafficBps: 0,
  devices: [{
    displayName: 'Galaxy S25',
    friendlyName: null,
    hostname: 'Galaxy-S25',
    ipAddress: '10.10.10.21',
    macAddress: 'AA:BB:CC:DD:EE:01',
    interfaceName: 'ether2',
    portFriendlyName: 'Clientes',
    dhcpServer: 'dhcp-clientes',
    status: 'ONLINE',
    blocked: false,
    leaseComment: null,
    notes: null,
    downloadLimitBps: 0,
    uploadLimitBps: 0,
    downloadTrafficBps: 0,
    uploadTrafficBps: 0,
    lastSeenAt: null,
  }],
}];

const bridgeDevice: Device = {
  ...ports[0].devices[0],
  displayName: 'Filipe-PC',
  hostname: 'Filipe-PC',
  ipAddress: '192.168.88.254',
  macAddress: '3C:7C:3F:30:01:DF',
  interfaceName: 'bridge',
  portFriendlyName: 'bridge',
  dhcpServer: 'defconf',
};

const blockPlan: OperationPlan = {
  planId: 'app-block-plan',
  operationType: 'BLOCK_DEVICE',
  target: { identifier: ports[0].devices[0].macAddress, displayName: ports[0].devices[0].displayName, macAddress: ports[0].devices[0].macAddress, interfaceName: ports[0].devices[0].interfaceName },
  currentState: { displayName: ports[0].devices[0].displayName, macAddress: ports[0].devices[0].macAddress, ipAddress: ports[0].devices[0].ipAddress, interfaceName: ports[0].devices[0].interfaceName, blocked: false },
  desiredState: { displayName: ports[0].devices[0].displayName, macAddress: ports[0].devices[0].macAddress, ipAddress: ports[0].devices[0].ipAddress, interfaceName: ports[0].devices[0].interfaceName, blocked: true },
  ownership: 'MANAGED',
  preconditions: [{ code: 'DEVICE_EXISTS', description: 'O dispositivo existe.', satisfied: true, severity: 'INFO' }],
  warnings: [],
  conflicts: [],
  plannedChanges: [{ action: 'BLOCK', resourceType: 'DEVICE_BLOCK', description: 'Bloquear após revalidação.' }],
  changeRequired: true,
  readyForFutureExecution: true,
  executable: false,
  executionDisabledReason: '',
  generatedAt: '2026-08-09T12:00:00Z',
  snapshotFingerprint: 'app-block-fingerprint',
};

function jsonResponse(body: unknown): Response {
  return { ok: true, json: async () => body } as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

it('keeps the disconnected status visible when RouterOS data is unavailable', async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') {
      return { ok: true, json: async () => disconnectedStatus } as Response;
    }
    throw new Error('RouterOS indisponível');
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByRole('heading', { name: 'MikroTik desconectado' })).toBeInTheDocument();
  expect(screen.getByText('192.168.88.1:443')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeEnabled();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/system/status', expect.anything()));
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('shows global devices discovered through a bridge, even though the dashboard lists physical ports', async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') return jsonResponse(connectedReadOnlyStatus);
    if (String(input) === '/api/ports') return jsonResponse(ports);
    if (String(input) === '/api/devices') return jsonResponse([bridgeDevice]);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByText('RouterOS 7.16.2 · Somente leitura')).toBeInTheDocument();
  expect(screen.getByText('RouterOS em modo somente leitura.')).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/ports')).toHaveLength(1));

  fireEvent.click(screen.getByRole('button', { name: 'Dispositivos' }));
  expect(await screen.findByText('Filipe-PC')).toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).toHaveLength(1);
});

it('recovers from offline to online and starts loading router data from ports', async () => {
  let statusRequestCount = 0;
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') {
      statusRequestCount += 1;
      return jsonResponse(statusRequestCount === 1 ? disconnectedStatus : connectedReadOnlyStatus);
    }
    if (String(input) === '/api/ports') return jsonResponse(ports);
    if (String(input) === '/api/devices') return jsonResponse(ports[0].devices);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByRole('heading', { name: 'MikroTik desconectado' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }));

  expect(await screen.findByText('RouterOS 7.16.2 · Somente leitura')).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/ports')).toHaveLength(1));
  expect(screen.queryByRole('heading', { name: 'MikroTik desconectado' })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).toHaveLength(1);
});

it('saves a discovered interface locally and refreshes the dashboard without a browser reload', async () => {
  let currentPorts: Port[] = [{
    ...ports[0],
    friendlyName: 'ether2',
    description: null,
    network: null,
    dhcpServer: null,
    role: null,
    managed: false,
    enabled: false,
    devices: [],
    deviceCount: 0,
    onlineDeviceCount: 0,
  }];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/system/status') return jsonResponse(connectedReadOnlyStatus);
    if (String(input) === '/api/ports') return jsonResponse(currentPorts);
    if (String(input) === '/api/devices') return jsonResponse([]);
    if (String(input) === '/api/diagnostics') {
      return jsonResponse({ connected: true, routerOsVersion: '7.16.2', latencyMillis: 12, mockMode: false, fastTrackDetected: false, checks: [] });
    }
    if (String(input) === '/api/ports/ether2' && init?.method === 'PUT') {
      currentPorts = [{
        ...currentPorts[0],
        friendlyName: 'Cliente João',
        description: 'Casa João',
        network: '10.10.10.0/24',
        dhcpServer: 'dhcp-cliente1',
        role: 'CLIENT',
        managed: true,
        enabled: true,
      }];
      return jsonResponse(currentPorts[0]);
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  await screen.findByRole('heading', { name: 'Dashboard' });
  fireEvent.click(screen.getByRole('button', { name: 'Configurações' }));
  await screen.findByRole('heading', { name: 'Interfaces descobertas' });
  fireEvent.click(screen.getByRole('button', { name: 'Configurar' }));

  expect(screen.getByRole('button', { name: 'Salvar configuração local' })).toBeEnabled();
  fireEvent.change(screen.getByLabelText('Nome amigável'), { target: { value: 'Cliente João' } });
  fireEvent.change(screen.getByLabelText('Descrição'), { target: { value: 'Casa João' } });
  fireEvent.change(screen.getByLabelText('Rede/CIDR'), { target: { value: '10.10.10.17/24' } });
  fireEvent.change(screen.getByLabelText('DHCP Server'), { target: { value: 'dhcp-cliente1' } });
  fireEvent.click(screen.getByLabelText('Exibir no dashboard'));
  fireEvent.click(screen.getByRole('button', { name: 'Salvar configuração local' }));

  await waitFor(() => {
    const putCall = fetchMock.mock.calls.find(
      ([input, init]) => String(input) === '/api/ports/ether2' && (init as RequestInit | undefined)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    expect(JSON.parse((putCall?.[1] as RequestInit).body as string)).toEqual({
      friendlyName: 'Cliente João',
      description: 'Casa João',
      network: '10.10.10.17/24',
      dhcpServer: 'dhcp-cliente1',
      enabled: true,
      role: 'CLIENT',
    });
  });
  expect(screen.getByLabelText('Rede/CIDR')).toHaveValue('10.10.10.0/24');

  fireEvent.click(screen.getByRole('button', { name: 'Dashboard' }));
  expect(await screen.findByText('Cliente João')).toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).not.toHaveLength(0);
});

it('uses a single combined write-analysis endpoint for Analisar RouterOS and updates readiness and reconciliation together', async () => {
  const readinessReport = {
    generatedAt: '2026-08-09T12:00:00Z', mockMode: false, writeFlagEnabled: false, readyForFutureExecution: true,
    executionEnabled: false, phaseNotice: 'A execução RouterOS permanece desabilitada na Fase 3.',
    checks: [{ code: 'ROUTEROS_CONNECTED', description: 'RouterOS conectado', satisfied: true, severity: 'INFO', detail: 'Leitura confirmada.' }],
    summary: { managed: 1, foreign: 0, inSync: 1, drifted: 0, missing: 0, conflicts: 0, ambiguous: 0, managedPorts: 1, validManagedPorts: 1 },
  };
  const reconciliationReport = {
    generatedAt: '2026-08-09T12:00:00Z', snapshotFingerprint: 'single-snapshot-fingerprint', observedInterfaceCount: 2,
    observedDhcpServerCount: 1, observedLeaseCount: 1, observedSimpleQueueCount: 1, fastTrackDetected: false,
    resources: [{
      resourceType: 'SIMPLE_QUEUE', resourceKey: 'ether2', displayName: 'Queue da porta ether2', ownership: 'MANAGED', status: 'IN_SYNC',
      expectedName: 'mtmgr-port-ether2', expectedTarget: '10.10.10.0/24', observedName: 'mtmgr-port-ether2', observedTarget: '10.10.10.0/24',
      conflict: false, findings: [],
    }],
    summary: { managed: 1, foreign: 0, inSync: 1, drifted: 0, missing: 0, conflicts: 0, ambiguous: 0, notApplicable: 0 },
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') return jsonResponse(connectedReadOnlyStatus);
    if (String(input) === '/api/ports') return jsonResponse(ports);
    if (String(input) === '/api/devices') return jsonResponse(ports[0].devices);
    if (String(input) === '/api/diagnostics') {
      return jsonResponse({ connected: true, routerOsVersion: '7.16.2', latencyMillis: 12, mockMode: false, fastTrackDetected: false, checks: [] });
    }
    if (String(input) === '/api/write-analysis') {
      return jsonResponse({ snapshotFingerprint: 'single-snapshot-fingerprint', readiness: readinessReport, reconciliation: reconciliationReport });
    }
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  await screen.findByRole('heading', { name: 'Dashboard' });
  fireEvent.click(screen.getByRole('button', { name: 'Configurações' }));
  await screen.findByRole('button', { name: 'Analisar RouterOS' });
  fireEvent.click(screen.getByRole('button', { name: 'Analisar RouterOS' }));

  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/write-analysis')).toHaveLength(1));
  await screen.findByText('IN_SYNC');
  expect(screen.getByText('Queue da porta ether2')).toBeInTheDocument();

  // The legacy split endpoints must not be called simultaneously.
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/write-readiness')).toHaveLength(0);
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/reconciliation')).toHaveLength(0);

  // The execution-disabled notice stays visible.
  expect(screen.getByText('A execução RouterOS permanece desabilitada na Fase 3.')).toBeInTheDocument();
});

it('disables real block execution when the capability is false while keeping a fresh preview available', async () => {
  const disabledStatus: SystemStatus = { ...connectedReadOnlyStatus, deviceBlockExecutionEnabled: false };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/system/status') return jsonResponse(disabledStatus);
    if (path === '/api/ports') return jsonResponse(ports);
    if (path === '/api/devices') return jsonResponse([ports[0].devices[0]]);
    if (path === '/api/plans/block' && init?.method === 'POST') return jsonResponse(blockPlan);
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  await screen.findByRole('heading', { name: 'Dashboard' });
  fireEvent.click(screen.getByRole('button', { name: 'Dispositivos' }));
  await screen.findByText('Galaxy S25');
  fireEvent.click(screen.getByRole('button', { name: 'Detalhes' }));
  expect(screen.getByRole('button', { name: 'Bloquear dispositivo' })).toBeDisabled();

  fireEvent.click(screen.getByRole('button', { name: 'Visualizar plano' }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/plans/block')).toHaveLength(1));
  expect(screen.getByRole('button', { name: 'Confirmar bloqueio' })).toBeDisabled();
});

it('previews block before mutation, requires explicit confirmation, sends no plan payload and refreshes router data and audit', async () => {
  let currentDevice = ports[0].devices[0];
  let currentPorts = ports;
  const executionStatus: SystemStatus = {
    ...connectedReadOnlyStatus,
    deviceBlockExecutionEnabled: true,
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/system/status') return jsonResponse(executionStatus);
    if (path === '/api/ports') return jsonResponse(currentPorts);
    if (path === '/api/devices') return jsonResponse([currentDevice]);
    if (path === '/api/plans/block' && init?.method === 'POST') return jsonResponse(blockPlan);
    if (path === `/api/devices/${encodeURIComponent(currentDevice.macAddress)}/block` && init?.method === 'POST') {
      currentDevice = { ...currentDevice, blocked: true, status: 'BLOCKED' };
      currentPorts = [{ ...currentPorts[0], blockedDeviceCount: 1, devices: [currentDevice] }];
      return jsonResponse(currentDevice);
    }
    if (path === '/api/audit') return jsonResponse([]);
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  await screen.findByRole('heading', { name: 'Dashboard' });
  fireEvent.click(screen.getByRole('button', { name: 'Dispositivos' }));
  await screen.findByText('Galaxy S25');
  fireEvent.click(screen.getByRole('button', { name: 'Detalhes' }));
  fireEvent.click(screen.getByRole('button', { name: 'Bloquear dispositivo' }));

  await screen.findByRole('button', { name: 'Confirmar bloqueio' });
  expect(fetchMock.mock.calls.filter(([input, requestInit]) => String(input).includes('/api/devices/') && (requestInit as RequestInit | undefined)?.method === 'POST')).toHaveLength(0);

  // The first confirmation button belongs to the preview. It only opens the
  // explicit confirmation dialog; the mutating endpoint is still untouched.
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar bloqueio' }));
  expect(screen.getByRole('heading', { name: 'Bloquear Galaxy S25?' })).toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/devices/'))).toHaveLength(0);

  fireEvent.click(screen.getByRole('button', { name: 'Confirmar bloqueio' }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input, requestInit]) =>
    String(input) === '/api/devices/AA%3ABB%3ACC%3ADD%3AEE%3A01/block' && (requestInit as RequestInit | undefined)?.method === 'POST',
  )).toHaveLength(1));

  const blockCall = fetchMock.mock.calls.find(([input, requestInit]) =>
    String(input) === '/api/devices/AA%3ABB%3ACC%3ADD%3AEE%3A01/block' && (requestInit as RequestInit | undefined)?.method === 'POST',
  );
  expect((blockCall?.[1] as RequestInit).body).toBeUndefined();
  expect(JSON.stringify(blockCall?.[1])).not.toMatch(/plan|fingerprint|ownership|\.id|routeros/i);
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/audit')).toHaveLength(1));
  expect(screen.queryByRole('dialog', { name: 'Galaxy S25' })).not.toBeInTheDocument();
});

it('shows restricted write rather than a global read-only label when device block is enabled', async () => {
  const restrictedStatus: SystemStatus = { ...connectedReadOnlyStatus, deviceBlockExecutionEnabled: true };
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') return jsonResponse(restrictedStatus);
    if (String(input) === '/api/ports') return jsonResponse(ports);
    if (String(input) === '/api/devices') return jsonResponse(ports[0].devices);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByText('RouterOS 7.16.2 · Escrita restrita')).toBeInTheDocument();
  expect(screen.getByText('RouterOS com escrita restrita.')).toBeInTheDocument();
  expect(screen.queryByText('RouterOS em modo somente leitura.')).not.toBeInTheDocument();
});
