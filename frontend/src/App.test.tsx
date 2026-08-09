import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import App from './App';
import type { Port, SystemStatus } from './types';

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

it('shows real RouterOS version and read-only state while deriving global devices from ports', async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') return jsonResponse(connectedReadOnlyStatus);
    if (String(input) === '/api/ports') return jsonResponse(ports);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByText('RouterOS 7.16.2 · Somente leitura')).toBeInTheDocument();
  expect(screen.getByText('RouterOS em modo somente leitura.')).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/ports')).toHaveLength(1));

  fireEvent.click(screen.getByRole('button', { name: 'Dispositivos' }));
  expect(await screen.findByText('Galaxy S25')).toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).toHaveLength(0);
});

it('recovers from offline to online and starts loading router data from ports', async () => {
  let statusRequestCount = 0;
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') {
      statusRequestCount += 1;
      return jsonResponse(statusRequestCount === 1 ? disconnectedStatus : connectedReadOnlyStatus);
    }
    if (String(input) === '/api/ports') return jsonResponse(ports);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByRole('heading', { name: 'MikroTik desconectado' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }));

  expect(await screen.findByText('RouterOS 7.16.2 · Somente leitura')).toBeInTheDocument();
  await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/ports')).toHaveLength(1));
  expect(screen.queryByRole('heading', { name: 'MikroTik desconectado' })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).toHaveLength(0);
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
  expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/devices')).toHaveLength(0);
});
