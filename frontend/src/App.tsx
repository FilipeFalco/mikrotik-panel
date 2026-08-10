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
import { OperationPlanDialog } from './components/OperationPlanDialog';
import type { AuditLog, Device, Diagnostics, OperationPlan, Port, ReconciliationReport, SystemStatus, WriteReadinessReport } from './types';

type View = 'dashboard' | 'devices' | 'history' | 'settings' | 'port';

interface Confirmation {
  device: Device;
  block: boolean;
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
  // RouterOS snapshot.
  const analyzeRouterOS = useCallback(() => {
    setAnalyzing(true);
    setError(null);
    void Promise.all([api.writeReadiness(), api.reconciliation()])
      .then(([nextReadiness, nextReconciliation]) => {
        setReadiness(nextReadiness);
        setReconciliation(nextReconciliation);
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

  const confirmBlock = () => {
    if (!confirmation || routerControlsReadOnly) return;
    const { device, block } = confirmation;
    void runOperation(
      () => block ? api.blockDevice(device.macAddress) : api.unblockDevice(device.macAddress),
      () => {
        setConfirmation(null);
        setSelectedDevice(null);
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
        <div className="sidebar-foot"><span>Local only</span><small>v0.3 · Fase 3</small></div>
      </aside>
      <main className="main-content">
        <header className="topbar"><div><span className="topbar-title">MikroTik Local Manager</span><small>{routerConnectionLabel}</small></div><StatusBadge status={systemStatus?.connected ? 'CONNECTED' : 'DISCONNECTED'} /></header>
        {error && <section className="notice error" role="alert"><div><strong>Não foi possível concluir a ação.</strong><span>{error}</span></div><button type="button" className="button secondary compact" onClick={retryConnection}>Tentar novamente</button></section>}
        {systemStatus && !systemStatus.mockMode && systemStatus.readOnly && <section className="notice info"><strong>RouterOS em modo somente leitura.</strong><span>Configurações e metadata locais continuam disponíveis; nenhuma alteração é enviada ao roteador.</span></section>}
        {systemStatus?.fastTrackDetected && <section className="notice warning"><strong>⚠ FastTrack detectado</strong><span>A verificação é informativa nesta fase; nenhuma regra será alterada automaticamente.</span></section>}
        {content()}
      </main>
      <DeviceDetails device={selectedDevice} busy={busy} readOnly={routerControlsReadOnly} onClose={() => setSelectedDevice(null)} onSave={saveDevice} onRequestBlock={(device) => !routerControlsReadOnly && setConfirmation({ device, block: !device.blocked })} onPreviewBlock={(device, block) => previewPlan(block ? { type: 'BLOCK_DEVICE', macAddress: device.macAddress } : { type: 'UNBLOCK_DEVICE', macAddress: device.macAddress })} onPreviewSpeed={(device, downloadBps, uploadBps) => previewPlan({ type: 'SET_DEVICE_SPEED', macAddress: device.macAddress, downloadBps, uploadBps })} />
      <ConfirmDialog open={Boolean(confirmation)} title={confirmation?.block ? `Bloquear ${confirmation.device.displayName}?` : `Liberar ${confirmation?.device.displayName ?? 'dispositivo'}?`} description={confirmation?.block ? 'O dispositivo perderá acesso à rede. Esta alteração é reversível.' : 'O acesso será liberado novamente para este dispositivo.'} confirmLabel={confirmation?.block ? 'Bloquear' : 'Liberar acesso'} busy={busy} onCancel={() => !busy && setConfirmation(null)} onConfirm={confirmBlock} />
      <OperationPlanDialog open={Boolean(planIntent)} plan={operationPlan} loading={planning} onClose={() => { setPlanIntent(null); setOperationPlan(null); }} onRefresh={refreshPlan} />
    </div>
  );
}
