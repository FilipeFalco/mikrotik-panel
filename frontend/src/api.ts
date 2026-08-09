import type { ApiErrorBody, AuditLog, Device, Diagnostics, Port, PortRole, SystemStatus } from './types';

export class ApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  });

  if (!response.ok) {
    let body: ApiErrorBody | undefined;
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      // Keep a friendly fallback for reverse-proxy or network failures.
    }
    throw new ApiError(body?.code ?? 'NETWORK_ERROR', body?.message ?? 'Não foi possível comunicar com o backend.');
  }
  return response.json() as Promise<T>;
}

const encoded = (value: string) => encodeURIComponent(value);

export const api = {
  systemStatus: () => request<SystemStatus>('/api/system/status'),
  testConnection: () => request<SystemStatus>('/api/system/test-connection', { method: 'POST' }),
  diagnostics: () => request<Diagnostics>('/api/diagnostics'),
  ports: () => request<Port[]>('/api/ports'),
  port: (interfaceName: string) => request<Port>(`/api/ports/${encoded(interfaceName)}`),
  updatePort: (
    interfaceName: string,
    friendlyName: string,
    description: string,
    network: string,
    dhcpServer: string,
    enabled: boolean,
    role?: PortRole | null,
  ) => request<Port>(`/api/ports/${encoded(interfaceName)}`, {
    method: 'PUT',
    body: JSON.stringify({
      friendlyName,
      description,
      network,
      dhcpServer,
      enabled,
      ...(role === undefined ? {} : { role }),
    }),
  }),
  devices: () => request<Device[]>('/api/devices'),
  audit: () => request<AuditLog[]>('/api/audit'),
  updatePortSpeed: (interfaceName: string, downloadBps: number, uploadBps: number) =>
    request<Port>(`/api/ports/${encoded(interfaceName)}/speed`, {
      method: 'PUT',
      body: JSON.stringify({ downloadBps, uploadBps }),
    }),
  updateDevice: (macAddress: string, friendlyName: string, notes: string) =>
    request<Device>(`/api/devices/${encoded(macAddress)}`, {
      method: 'PUT',
      body: JSON.stringify({ friendlyName, notes }),
    }),
  updateDeviceSpeed: (macAddress: string, downloadBps: number, uploadBps: number) =>
    request<Device>(`/api/devices/${encoded(macAddress)}/speed`, {
      method: 'PUT',
      body: JSON.stringify({ downloadBps, uploadBps }),
    }),
  blockDevice: (macAddress: string) => request<Device>(`/api/devices/${encoded(macAddress)}/block`, { method: 'POST' }),
  unblockDevice: (macAddress: string) => request<Device>(`/api/devices/${encoded(macAddress)}/block`, { method: 'DELETE' }),
};
