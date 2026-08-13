import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiError } from './api';
import { Dashboard } from './components/Dashboard';
import { DeviceDetails } from './components/DeviceDetails';
import { DeviceList } from './components/DeviceList';
import { ConfirmDialog } from './components/ConfirmDialog';
import { History } from './components/History';
import { PortDetail } from './components/PortDetail';
import type { LocalPortConfiguration } from './components/PortConfigurationEditor';
import { Settings } from './components/Settings';
import { StatusBadge } from './components/StatusBadge';
import { canConfirmOperationPlan, OperationPlanDialog } from './components/OperationPlanDialog';
import type { AuditLog, Device, Diagnostics, OperationPlan, Port, ReconciliationReport, SystemStatus, WriteReadinessReport } from './types';

type View = 'dashboard' | 'devices' | 'history' | 'settings' | 'port';

interface Confirmation {
  device: Device;
  block: boolean;
  plan: OperationPlan;
}

type PlanIntent =
  | { type: 'BLOCK_DEVICE'; macAddress: string }
  | { type: 'UNBLOCK_DEVICE'; macAddress: string }
  | { type: 'SET_PORT_SPEED'; interfaceName: string; downloadBps: number; uploadBps: number }
  | { type: 'SET_DEVICE_SPEED'; macAddress: string; downloadBps: number; uploadBps: number };

const navItems: Array<{ id: Exclude<View, 'port'>; label: string; symbol: string }> = [
  { id: 'dashboard', label: 'Dashboard', symbol: '▦' },
  { id: 'devices', label: 'Dispositivos', symbol: '◉' },
  { id: 'history', label: 'Histórico', symbol: '◷' },
  { id: 'settings', label: 'Configurações', symbol: '⚙' },
];

const STATUS_POLL_INTERVAL_MS = 5_000;
const ROUTER_DATA_POLL_INTERVAL_MS = 5_000;

function errorMessage(reason: unknown, fallback: string): string {
  return reason instanceof ApiError ? reason.message : fallback;
}

function comparableMac(value: string | null | undefined): string {
  return (value ?? '').replace(/[^0-9a-f]/gi, '').toUpperCase();
}

interface OfflineStateProps {
  systemStatus: SystemStatus | null;
  retrying: boolean;
  onRetry: () => void;
}

function OfflineState({ systemStatus, retrying, onRetry }: OfflineStateProps) {
  const host = systemStatus ? `${systemStatus.host}:${systemStatus.port}` : 'Host indisponível';

  return (
    <section className="offline-state" role="status">
      <p className="eyebrow">Status de conexão</p>
      <h1>MikroTik desconectado</h1>
      <p>Host: <strong>{host}</strong></p>
      <p>Não foi possível obter os dados do RouterOS.</p>
      <button type="button" className="button primary" onClick={onRetry} disabled={retrying}>
        {retrying ? 'Tentando…' : 'Tentar novamente'}
      </button>
    </section>
  );
}

export default function App() {
  const [view, setView] = useState<View>('dashboard');
  const [ports, setPorts] = useState<Port[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [audit, setAudit] = useState<AuditLog[]>([]);
  const [systemStatus, setSystemStatus] = useState<SystemStatus | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [readiness, setReadiness] = useState<WriteReadinessReport | null>(null);
  const [reconciliation, setReconciliation] = useState<ReconciliationReport | null>(null);
  const [selectedPortName, setSelectedPortName] = useState<string | null>(null);
  const [selectedDevice, setSelectedDevice] = useState<Device | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [planIntent, setPlanIntent] = useState<PlanIntent | null>(null);
  const [operationPlan, setOperationPlan] = useState<OperationPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);
  const [planning, setPlanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const applyRouterData = useCallback((nextPorts: Port[], nextDevices: Device[]) => {
    setPorts(nextPorts);
    setDevices(nextDevices);
  }, []);

  const refreshSystemStatus = useCallback(async (initial = false): Promise<SystemStatus | null> => {
    if (initial) setLoading(true);
    try {
      const nextStatus = await api.systemStatus();
      setSystemStatus(nextStatus);
      setError(null);
      return nextStatus;
    } catch (reason) {
      setSystemStatus(null);
      setError(errorMessage(reason, 'Não foi possível obter o status de conexão do MikroTik.'));
      return null;
    } finally {
      if (initial) setLoading(false);
    }
  }, []);

  const refreshRouterData = useCallback(async () => {
    try {
      const [nextPorts, nextDevices] = await Promise.all([api.ports(), api.devices()]);
      applyRouterData(nextPorts, nextDevices);
      setError(null);
    } catch (reason) {
      setError(errorMessage(reason, 'Não foi possível obter os dados do RouterOS.'));
    }
  }, [applyRouterData]);

  const refreshAudit = useCallback(async () => {
    try {
      setAudit(await api.audit());
      setError(null);
    } catch (reason) {
      setError(errorMessage(reason, 'Não foi possível carregar o histórico de auditoria.'));
    }
  }, []);

  const refreshDiagnostics = useCallback(async () => {
    try {
      setDiagnostics(await api.diagnostics());
      setError(null);
    } catch (reason) {
      setError(errorMessage(reason, 'Não foi possível carregar os diagnósticos.'));
    }
  }, []);

  // These diagnostics are deliberately on-demand. They are not part of the
  // five-second dashboard polling loop because reconciliation reads a fuller
  // RouterOS snapshot. A single combined endpoint captures one snapshot and
  // derives both readiness and reconciliation from it, so the two views always
  // refer to the same observed moment and the six RouterOS collections are not
  // read twice for the same analysis.
  const analyzeRouterOS = useCallback(() => {
    setAnalyzing(true);
    setError(null);
    void api.writeAnalysis()
      .then((analysis) => {
        setReadiness(analysis.readiness);
        setReconciliation(analysis.reconciliation);
      })
      .catch((reason: unknown) => setError(errorMessage(reason, 'Não foi possível analisar o estado RouterOS.')))
      .finally(() => setAnalyzing(false));
  }, []);

  const requestPlan = useCallback(async (intent: PlanIntent): Promise<OperationPlan> => {
    switch (intent.type) {
      case 'BLOCK_DEVICE':
        return api.planBlockDevice(intent.macAddress);
      case 'UNBLOCK_DEVICE':
        return api.planUnblockDevice(intent.macAddress);
      case 'SET_PORT_SPEED':
        return api.planPortSpeed(intent.interfaceName, intent.downloadBps, intent.uploadBps);
      case 'SET_DEVICE_SPEED':
        return api.planDeviceSpeed(intent.macAddress, intent.downloadBps, intent.uploadBps);
    }
  }, []);

  const previewPlan = useCallback((intent: PlanIntent) => {
    setPlanIntent(intent);
    setOperationPlan(null);
    setPlanning(true);
    setError(null);
    void requestPlan(intent)
      .then(setOperationPlan)
      .catch((reason: unknown) => setError(errorMessage(reason, 'Não foi possível gerar a simulação.')))
      .finally(() => setPlanning(false));
  }, [requestPlan]);

  const previewDeviceBlock = useCallback((device: Device, block: boolean) => {
    // Every click starts a new backend preview. The preview is deliberately
    // the only input used to decide whether the confirmation step is shown.
    previewPlan(block
      ? { type: 'BLOCK_DEVICE', macAddress: device.macAddress }
      : { type: 'UNBLOCK_DEVICE', macAddress: device.macAddress });
  }, [previewPlan]);

  const refreshPlan = useCallback(() => {
    if (!planIntent) return;
    setPlanning(true);
    setError(null);
    void requestPlan(planIntent)
      .then(setOperationPlan)
      .catch((reason: unknown) => setError(errorMessage(reason, 'Não foi possível atualizar a simulação.')))
      .finally(() => setPlanning(false));
  }, [planIntent, requestPlan]);

  useEffect(() => {
    void refreshSystemStatus(true);
    const timer = window.setInterval(() => void refreshSystemStatus(), STATUS_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [refreshSystemStatus]);

  useEffect(() => {
    if (!systemStatus?.connected) {
      setPorts([]);
      setDevices([]);
      return undefined;
    }

    void refreshRouterData();
    const timer = window.setInterval(() => void refreshRouterData(), ROUTER_DATA_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [systemStatus?.connected, refreshRouterData]);

  useEffect(() => {
    if (view === 'history') void refreshAudit();
  }, [view, refreshAudit]);

  useEffect(() => {
    if (view === 'settings') void refreshDiagnostics();
  }, [view, refreshDiagnostics]);

  const selectedPort = useMemo(() => ports.find((port) => port.interfaceName === selectedPortName) ?? null, [ports, selectedPortName]);

  const openPort = (interfaceName: string) => {
    setSelectedPortName(interfaceName);
    setView('port');
  };

  const navigate = (nextView: Exclude<View, 'port'>) => {
    setSelectedDevice(null);
    setView(nextView);
  };

  const retryConnection = () => {
    const wasConnected = systemStatus?.connected === true;
    setTesting(true);
    setError(null);
    void refreshSystemStatus()
      .then((status) => status?.connected && wasConnected ? refreshRouterData() : undefined)
      .finally(() => setTesting(false));
  };

  const runOperation = async (operation: () => Promise<unknown>, after?: () => void) => {
    setBusy(true);
    setError(null);
    try {
      await operation();
      await Promise.all([refreshRouterData(), refreshAudit()]);
      after?.();
    } catch (reason) {
      setError(errorMessage(reason, 'A operação não pôde ser concluída.'));
    } finally {
      setBusy(false);
    }
  };

  // Mock mode keeps simulated controls available. This gates only actions that would target RouterOS.
  const routerControlsReadOnly = Boolean(systemStatus && !systemStatus.mockMode && systemStatus.readOnly);
  // Phase 4 exposes a specific capability for block/unblock. Falling back to
  // the pre-capability behavior keeps older status responses working, while an
  // explicit false always wins. Mock mode is therefore enabled only when the
  // backend leaves it enabled or reports the capability explicitly.
  const deviceBlockExecutionEnabled = Boolean(
    systemStatus?.connected
      && (systemStatus.deviceBlockExecutionEnabled ?? !routerControlsReadOnly),
  );

  const savePortConfiguration = async (
    interfaceName: string,
    configuration: LocalPortConfiguration,
  ): Promise<Port | null> => {
    setBusy(true);
    setError(null);
    try {
      const updatedPort = await api.updatePort(
        interfaceName,
        configuration.friendlyName,
        configuration.description,
        configuration.network,
        configuration.dhcpServer,
        configuration.enabled,
        configuration.role,
      );

      try {
        const refreshedPorts = await api.ports();
        // A local metadata update refreshes the interface list only. Devices
        // keep their last read-only RouterOS observation until normal polling
        // fetches both collections together.
        setPorts(refreshedPorts);
        return refreshedPorts.find((port) => port.interfaceName === updatedPort.interfaceName) ?? updatedPort;
      } catch {
        // Port PUT returns local metadata. Preserve the live RouterOS data until polling can refresh it.
        const previousPort = ports.find((port) => port.interfaceName === updatedPort.interfaceName);
        const fallbackPort = previousPort ? {
          ...previousPort,
          friendlyName: updatedPort.friendlyName,
          description: updatedPort.description,
          network: updatedPort.network,
          dhcpServer: updatedPort.dhcpServer,
          role: updatedPort.role,
          managed: updatedPort.managed,
          enabled: updatedPort.enabled,
        } : updatedPort;
        setPorts((currentPorts) => currentPorts.map((port) => {
          if (port.interfaceName === updatedPort.interfaceName) return fallbackPort;
          return updatedPort.role === 'WAN' && port.role === 'WAN' ? { ...port, role: 'CLIENT' } : port;
        }));
        return fallbackPort;
      }
    } catch (reason) {
      setError(errorMessage(reason, 'A configuração local da porta não pôde ser salva.'));
      return null;
    } finally {
      setBusy(false);
    }
  };

  const savePortSpeed = (port: Port, downloadBps: number, uploadBps: number) => {
    if (routerControlsReadOnly) return;
    void runOperation(() => api.updatePortSpeed(port.interfaceName, downloadBps, uploadBps));
  };

  const saveDevice = (device: Device, friendlyName: string, notes: string, downloadBps: number, uploadBps: number) => {
    void runOperation(async () => {
      if (friendlyName !== (device.friendlyName ?? '') || notes !== (device.notes ?? '')) {
        await api.updateDevice(device.macAddress, friendlyName, notes);
      }
      if (!routerControlsReadOnly && (downloadBps !== device.downloadLimitBps || uploadBps !== device.uploadLimitBps)) {
        await api.updateDeviceSpeed(device.macAddress, downloadBps, uploadBps);
      }
    }, () => setSelectedDevice(null));
  };

  const requestBlockConfirmation = (plan: OperationPlan) => {
    if (!deviceBlockExecutionEnabled || !canConfirmOperationPlan(plan, deviceBlockExecutionEnabled)) return;
    const planMac = plan.target.macAddress ?? plan.target.identifier;
    const device = (selectedDevice && comparableMac(selectedDevice.macAddress) === comparableMac(planMac))
      ? selectedDevice
      : devices.find((candidate) => comparableMac(candidate.macAddress) === comparableMac(planMac));
    if (!device) {
      setError('O dispositivo do preview não está mais disponível para confirmação. Gere um novo preview.');
      return;
    }
    setConfirmation({ device, block: plan.operationType === 'BLOCK_DEVICE', plan });
  };

  const confirmBlock = () => {
    if (!confirmation || !deviceBlockExecutionEnabled) return;
    const { device, block } = confirmation;
    void runOperation(
      () => block ? api.blockDevice(device.macAddress) : api.unblockDevice(device.macAddress),
      () => {
        setConfirmation(null);
        setSelectedDevice(null);
        setPlanIntent(null);
        setOperationPlan(null);
      },
    );
  };

  const testConnection = () => {
    const wasConnected = systemStatus?.connected === true;
    setTesting(true);
    setError(null);
    void api.testConnection()
      .then(async (status) => {
        setSystemStatus(status);
        await refreshDiagnostics();
        if (status.connected && wasConnected) await refreshRouterData();
      })
      .catch((reason: unknown) => setError(errorMessage(reason, 'Não foi possível testar a conexão.')))
      .finally(() => setTesting(false));
  };

  const routerConnectionLabel = systemStatus?.mockMode
    ? 'Dados simulados'
    : systemStatus?.connected
      ? `RouterOS ${systemStatus.routerOsVersion ?? 'conectado'}${systemStatus.readOnly ? ' · Somente leitura' : ''}`
      : 'RouterOS';

  const content = () => {
    if (loading) return <div className="loading-state"><span className="loading-spinner" /><p>Carregando o painel local…</p></div>;
    if (!systemStatus?.connected && (view === 'dashboard' || view === 'devices' || view === 'port')) return <OfflineState systemStatus={systemStatus} retrying={testing} onRetry={retryConnection} />;
    if (view === 'dashboard') return <Dashboard ports={ports} onManage={openPort} />;
    if (view === 'devices') return <div className="page-stack"><section className="page-heading"><div><p className="eyebrow">Todos os clientes</p><h1>Dispositivos</h1><p>Pesquise por nome, IP ou MAC e gerencie cada dispositivo.</p></div></section><DeviceList devices={devices} onSelect={setSelectedDevice} /></div>;
    if (view === 'history') return <History entries={audit} />;
    if (view === 'settings') return <Settings systemStatus={systemStatus} diagnostics={diagnostics} readiness={readiness} reconciliation={reconciliation} ports={ports} testing={testing} saving={busy} analyzing={analyzing} onTestConnection={testConnection} onAnalyzeRouterOS={analyzeRouterOS} onSavePort={savePortConfiguration} />;
    if (view === 'port' && selectedPort) return <PortDetail port={selectedPort} saving={busy} readOnly={routerControlsReadOnly} onSaveSpeed={savePortSpeed} onPreviewSpeed={(port, downloadBps, uploadBps) => previewPlan({ type: 'SET_PORT_SPEED', interfaceName: port.interfaceName, downloadBps, uploadBps })} onDeviceSelect={setSelectedDevice} onBack={() => navigate('dashboard')} />;
    return <div className="empty-state"><h2>Porta não encontrada</h2><button className="button primary" type="button" onClick={() => navigate('dashboard')}>Voltar ao dashboard</button></div>;
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">M</span><div><strong>MikroTik</strong><small>Local Manager</small></div></div>
        <nav aria-label="Navegação principal">{navItems.map((item) => <button key={item.id} type="button" className={view === item.id ? 'nav-item active' : 'nav-item'} aria-current={view === item.id ? 'page' : undefined} onClick={() => navigate(item.id)}><span aria-hidden="true">{item.symbol}</span>{item.label}</button>)}</nav>
        <div className="sidebar-foot"><span>Local only</span><small>v0.4 · Fase 4</small></div>
      </aside>
      <main className="main-content">
        <header className="topbar"><div><span className="topbar-title">MikroTik Local Manager</span><small>{routerConnectionLabel}</small></div><StatusBadge status={systemStatus?.connected ? 'CONNECTED' : 'DISCONNECTED'} /></header>
        {error && <section className="notice error" role="alert"><div><strong>Não foi possível concluir a ação.</strong><span>{error}</span></div><button type="button" className="button secondary compact" onClick={retryConnection}>Tentar novamente</button></section>}
        {systemStatus && !systemStatus.mockMode && systemStatus.readOnly && <section className="notice info"><strong>RouterOS em modo somente leitura.</strong><span>{deviceBlockExecutionEnabled ? 'Configurações e metadata locais continuam disponíveis; bloqueio/liberação está habilitado pela capability informada.' : 'Configurações e metadata locais continuam disponíveis; nenhuma alteração é enviada ao roteador.'}</span></section>}
        {systemStatus?.fastTrackDetected && <section className="notice warning"><strong>⚠ FastTrack detectado</strong><span>A verificação é informativa nesta fase; nenhuma regra será alterada automaticamente.</span></section>}
        {content()}
      </main>
      <DeviceDetails
        device={selectedDevice}
        busy={busy}
        readOnly={routerControlsReadOnly}
        blockExecutionEnabled={deviceBlockExecutionEnabled}
        onClose={() => setSelectedDevice(null)}
        onSave={saveDevice}
        onRequestBlock={(device) => previewDeviceBlock(device, !device.blocked)}
        onPreviewBlock={previewDeviceBlock}
        onPreviewSpeed={(device, downloadBps, uploadBps) => previewPlan({ type: 'SET_DEVICE_SPEED', macAddress: device.macAddress, downloadBps, uploadBps })}
      />
      <OperationPlanDialog
        open={Boolean(planIntent) && !confirmation}
        plan={operationPlan}
        loading={planning}
        executionEnabled={deviceBlockExecutionEnabled}
        device={selectedDevice}
        onConfirm={requestBlockConfirmation}
        onClose={() => { setPlanIntent(null); setOperationPlan(null); }}
        onRefresh={refreshPlan}
      />
      <ConfirmDialog
        open={Boolean(confirmation)}
        title={confirmation?.block ? `Bloquear ${confirmation.device.displayName}?` : `Liberar ${confirmation?.device.displayName ?? 'dispositivo'}?`}
        description={confirmation?.block
          ? 'O dispositivo perderá acesso à rede para novos fluxos encaminhados.'
          : 'A regra MTMGR de bloqueio será removida somente depois de nova leitura e validação de ownership.'}
        confirmLabel={confirmation?.block ? 'Confirmar bloqueio' : 'Confirmar liberação'}
        details={confirmation ? [
          { label: 'Dispositivo', value: confirmation.device.displayName },
          { label: 'MAC', value: confirmation.device.macAddress },
          { label: 'Interface/porta', value: `${confirmation.device.interfaceName} · ${confirmation.device.portFriendlyName ?? 'não informada'}` },
          { label: 'Ownership', value: confirmation.plan.ownership },
        ] : []}
        warnings={confirmation?.plan.warnings.map((warning) => `${warning.code}: ${warning.description}`) ?? []}
        confirmDisabled={!deviceBlockExecutionEnabled}
        confirmDisabledReason="A capability de execução de bloqueio/liberação foi desabilitada; gere um novo preview quando ela estiver disponível."
        busy={busy}
        onCancel={() => !busy && setConfirmation(null)}
        onConfirm={confirmBlock}
      />
    </div>
  );
}
