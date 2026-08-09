export type DeviceStatus = 'ONLINE' | 'OFFLINE' | 'BLOCKED' | 'UNKNOWN';
export type PortRole = 'WAN' | 'CLIENT';

export interface SystemStatus {
  connected: boolean;
  mockMode: boolean;
  readOnly: boolean;
  host: string;
  port: number;
  routerOsVersion?: string | null;
  latencyMillis?: number | null;
  message: string;
  fastTrackDetected: boolean;
}

export interface Device {
  displayName: string;
  friendlyName?: string | null;
  hostname?: string | null;
  ipAddress?: string | null;
  macAddress: string;
  interfaceName: string;
  portFriendlyName?: string | null;
  dhcpServer?: string | null;
  status: DeviceStatus;
  blocked: boolean;
  leaseComment?: string | null;
  notes?: string | null;
  downloadLimitBps: number;
  uploadLimitBps: number;
  downloadTrafficBps: number;
  uploadTrafficBps: number;
  lastSeenAt?: string | null;
}

export interface Port {
  interfaceName: string;
  friendlyName: string;
  description?: string | null;
  network?: string | null;
  dhcpServer?: string | null;
  /** Local presentation metadata. A null value means the interface was only discovered. */
  role: PortRole | null;
  managed: boolean;
  enabled: boolean;
  running: boolean;
  disabled: boolean;
  deviceCount: number;
  onlineDeviceCount: number;
  blockedDeviceCount: number;
  downloadLimitBps: number;
  uploadLimitBps: number;
  downloadTrafficBps: number;
  uploadTrafficBps: number;
  devices: Device[];
}

export interface AuditLog {
  id: number;
  createdAt: string;
  action: string;
  targetType: string;
  targetIdentifier: string;
  previousValue?: string | null;
  newValue?: string | null;
  success: boolean;
  errorMessage?: string | null;
}

export interface DiagnosticCheck {
  name: string;
  available: boolean;
  detail: string;
}

export interface Diagnostics {
  routerOsVersion?: string | null;
  connected: boolean;
  latencyMillis?: number | null;
  mockMode: boolean;
  fastTrackDetected: boolean;
  checks: DiagnosticCheck[];
  warning?: string | null;
}

export interface ApiErrorBody {
  code: string;
  message: string;
}
