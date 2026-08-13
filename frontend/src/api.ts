import type {
  ApiErrorBody,
  AuditLog,
  Device,
  Diagnostics,
  OperationPlan,
  Port,
  PortRole,
  ReconciliationReport,
  SystemStatus,
  WriteAnalysis,
  WriteReadinessReport,
} from './types';

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
  reconciliation: () => request<ReconciliationReport>('/api/reconciliation'),
  writeReadiness: () => request<WriteReadinessReport>('/api/write-readiness'),
  writeAnalysis: () => request<WriteAnalysis>('/api/write-analysis'),
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
  // Execution intentionally has no body. The backend revalidates the fresh
  // preview itself; the only client-supplied identity is the MAC in the URL.
  blockDevice: (macAddress: string) => request<Device>(`/api/devices/${encoded(macAddress)}/block`, { method: 'POST' }),
  unblockDevice: (macAddress: string) => request<Device>(`/api/devices/${encoded(macAddress)}/block`, { method: 'DELETE' }),
  planBlockDevice: (macAddress: string) => request<OperationPlan>('/api/plans/block', {
    method: 'POST',
    body: JSON.stringify({ macAddress }),
  }),
  planUnblockDevice: (macAddress: string) => request<OperationPlan>('/api/plans/unblock', {
    method: 'POST',
    body: JSON.stringify({ macAddress }),
  }),
  planPortSpeed: (interfaceName: string, downloadBps: number, uploadBps: number) => request<OperationPlan>('/api/plans/port-speed', {
    method: 'POST',
    body: JSON.stringify({ interfaceName, downloadBps, uploadBps }),
  }),
  planDeviceSpeed: (macAddress: string, downloadBps: number, uploadBps: number) => request<OperationPlan>('/api/plans/device-speed', {
    method: 'POST',
    body: JSON.stringify({ macAddress, downloadBps, uploadBps }),
  }),
};
