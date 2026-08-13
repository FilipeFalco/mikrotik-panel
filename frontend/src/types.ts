export type DeviceStatus = 'ONLINE' | 'OFFLINE' | 'BLOCKED' | 'UNKNOWN';
export type PortRole = 'WAN' | 'CLIENT';

export interface SystemStatus {
  connected: boolean;
  mockMode: boolean;
  readOnly: boolean;
  /**
   * Capability returned by Phase 4 backends. It is optional so the frontend
   * remains compatible with older status responses; when present, `false`
   * always disables real device block/unblock controls.
   */
  deviceBlockExecutionEnabled?: boolean;
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
  /** Optional Phase 4 observation fields; older backends omit them. */
  blockOwnership?: ResourceOwnership | null;
  blockSource?: string | null;
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

export type PlanSeverity = 'INFO' | 'WARNING' | 'BLOCKING';
export type ReadinessSeverity = 'INFO' | 'WARNING' | 'BLOCKING';
export type ResourceOwnership = 'MANAGED' | 'FOREIGN' | 'UNKNOWN';
export type ReconciliationStatus = 'IN_SYNC' | 'MISSING' | 'DRIFTED' | 'CONFLICT' | 'FOREIGN' | 'AMBIGUOUS_OWNERSHIP' | 'NOT_APPLICABLE';
export type PlannedOperationType = 'BLOCK_DEVICE' | 'UNBLOCK_DEVICE' | 'SET_DEVICE_SPEED' | 'SET_PORT_SPEED';

/** Diagnostic-only speed representation. Zero means unlimited. */
export interface PlannedSpeedLimit {
  downloadBps: number;
  uploadBps: number;
}

export interface PlanTarget {
  identifier: string;
  displayName?: string | null;
  macAddress?: string | null;
  interfaceName?: string | null;
}

/** Safe plan snapshot returned by the backend. It is not an execution command. */
export interface PlanState {
  displayName?: string | null;
  macAddress?: string | null;
  ipAddress?: string | null;
  interfaceName?: string | null;
  network?: string | null;
  speedLimit?: PlannedSpeedLimit | null;
  blocked?: boolean | null;
  blockingStrategy?: string | null;
}

export interface PlanPrecondition {
  code: string;
  description: string;
  satisfied: boolean;
  severity: PlanSeverity;
}

export interface PlanWarning {
  code: string;
  description: string;
  severity: PlanSeverity;
}

export interface PlanConflict {
  code: string;
  resourceType: string;
  resourceName?: string | null;
  resourceTarget?: string | null;
  ownership: ResourceOwnership;
  description: string;
  severity: PlanSeverity;
}

export interface PlanChange {
  action: string;
  resourceType: string;
  description: string;
}

export interface OperationPlan {
  planId: string;
  operationType: PlannedOperationType;
  target: PlanTarget;
  currentState: PlanState;
  desiredState: PlanState;
  ownership: ResourceOwnership;
  preconditions: PlanPrecondition[];
  warnings: PlanWarning[];
  conflicts: PlanConflict[];
  plannedChanges: PlanChange[];
  changeRequired: boolean;
  readyForFutureExecution: boolean;
  executable: boolean;
  executionDisabledReason: string;
  generatedAt: string;
  snapshotFingerprint: string;
}

export interface ReconciliationFinding {
  code: string;
  description: string;
  severity: PlanSeverity;
}

export interface ReconciliationResource {
  resourceType: string;
  resourceKey: string;
  displayName: string;
  ownership: ResourceOwnership;
  status: ReconciliationStatus;
  expectedName?: string | null;
  expectedTarget?: string | null;
  observedName?: string | null;
  observedTarget?: string | null;
  conflict: boolean;
  findings: ReconciliationFinding[];
}

export interface ReconciliationSummary {
  managed: number;
  foreign: number;
  inSync: number;
  drifted: number;
  missing: number;
  conflicts: number;
  ambiguous: number;
  notApplicable: number;
}

/** On-demand observed reconciliation. It is never part of normal polling. */
export interface ReconciliationReport {
  generatedAt: string;
  snapshotFingerprint: string;
  observedInterfaceCount: number;
  observedDhcpServerCount: number;
  observedLeaseCount: number;
  observedSimpleQueueCount: number;
  fastTrackDetected: boolean;
  resources: ReconciliationResource[];
  summary: ReconciliationSummary;
}

export interface ReadinessCheck {
  code: string;
  description: string;
  satisfied: boolean;
  severity: ReadinessSeverity;
  detail: string;
}

export interface WriteReadinessSummary {
  managed: number;
  foreign: number;
  inSync: number;
  drifted: number;
  missing: number;
  conflicts: number;
  ambiguous: number;
  managedPorts: number;
  validManagedPorts: number;
}

/** Read-only Phase 4 capability and safety diagnostic. */
export interface WriteReadinessReport {
  generatedAt: string;
  mockMode: boolean;
  writeFlagEnabled: boolean;
  readyForFutureExecution: boolean;
  executionEnabled: boolean;
  phaseNotice: string;
  deviceBlockWriteFlagEnabled?: boolean;
  writeCredentialsConfigured?: boolean;
  blockingStrategy?: string;
  firewallOrderingAnalyzable?: boolean;
  checks: ReadinessCheck[];
  summary: WriteReadinessSummary;
}

/**
 * Combined write-analysis. Readiness and reconciliation are derived from the
 * same RouterOS snapshot, so they always refer to one observed moment.
 * reconciliation is null when the snapshot could not be captured.
 */
export interface WriteAnalysis {
  snapshotFingerprint: string | null;
  readiness: WriteReadinessReport;
  reconciliation: ReconciliationReport | null;
}
