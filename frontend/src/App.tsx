import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiError } from './api';
import { Dashboard } from './components/Dashboard';
import { DeviceDetails } from './components/DeviceDetails';
import { DeviceList } from './components/DeviceList';
import { ConfirmDialog } from './components/ConfirmDialog';
import { History } from './components/History';
import { PortDetail } from './components/PortDetail';
import { Settings } from './components/Settings';
import { StatusBadge } from './components/StatusBadge';
import type { AuditLog, Device, Diagnostics, Port, SystemStatus } from './types';

type View = 'dashboard' | 'devices' | 'history' | 'settings' | 'port';

interface Confirmation {
  device: Device;
  block: boolean;
}

const navItems: Array<{ id: Exclude<View, 'port'>; label: string; symbol: string }> = [
  { id: 'dashboard', label: 'Dashboard', symbol: '▦' },
  { id: 'devices', label: 'Dispositivos', symbol: '◉' },
  { id: 'history', label: 'Histórico', symbol: '◷' },
  { id: 'settings', label: 'Configurações', symbol: '⚙' },
];

export default function App() {
  const [view, setView] = useState<View>('dashboard');
  const [ports, setPorts] = useState<Port[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [audit, setAudit] = useState<AuditLog[]>([]);
  const [systemStatus, setSystemStatus] = useState<SystemStatus | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [selectedPortName, setSelectedPortName] = useState<string | null>(null);
  const [selectedDevice, setSelectedDevice] = useState<Device | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async (initial = false) => {
    if (initial) setLoading(true);
    try {
      const [nextStatus, nextPorts, nextDevices, nextAudit, nextDiagnostics] = await Promise.all([
        api.systemStatus(), api.ports(), api.devices(), api.audit(), api.diagnostics(),
      ]);
      setSystemStatus(nextStatus);
      setPorts(nextPorts);
      setDevices(nextDevices);
      setAudit(nextAudit);
      setDiagnostics(nextDiagnostics);
      setError(null);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Não foi possível carregar os dados do painel.');
    } finally {
      if (initial) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh(true);
    const timer = window.setInterval(() => void refresh(), 5_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const selectedPort = useMemo(() => ports.find((port) => port.interfaceName === selectedPortName) ?? null, [ports, selectedPortName]);

  const openPort = (interfaceName: string) => {
    setSelectedPortName(interfaceName);
    setView('port');
  };

  const navigate = (nextView: Exclude<View, 'port'>) => {
    setSelectedDevice(null);
    setView(nextView);
  };

  const runOperation = async (operation: () => Promise<unknown>, after?: () => void) => {
    setBusy(true);
    setError(null);
    try {
      await operation();
      await refresh();
      after?.();
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'A operação não pôde ser concluída.');
    } finally {
      setBusy(false);
    }
  };

  const savePortSpeed = (port: Port, downloadBps: number, uploadBps: number) => {
    void runOperation(() => api.updatePortSpeed(port.interfaceName, downloadBps, uploadBps));
  };

  const saveDevice = (device: Device, friendlyName: string, notes: string, downloadBps: number, uploadBps: number) => {
    void runOperation(async () => {
      if (friendlyName !== (device.friendlyName ?? '') || notes !== (device.notes ?? '')) {
        await api.updateDevice(device.macAddress, friendlyName, notes);
      }
      if (downloadBps !== device.downloadLimitBps || uploadBps !== device.uploadLimitBps) {
        await api.updateDeviceSpeed(device.macAddress, downloadBps, uploadBps);
      }
    }, () => setSelectedDevice(null));
  };

  const confirmBlock = () => {
    if (!confirmation) return;
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
    setTesting(true);
    setError(null);
    void api.testConnection()
      .then((status) => {
        setSystemStatus(status);
        return refresh();
      })
      .catch((reason: unknown) => setError(reason instanceof ApiError ? reason.message : 'Não foi possível testar a conexão.'))
      .finally(() => setTesting(false));
  };

  const content = () => {
    if (loading) return <div className="loading-state"><span className="loading-spinner" /><p>Carregando o painel local…</p></div>;
    if (view === 'dashboard') return <Dashboard ports={ports} onManage={openPort} />;
    if (view === 'devices') return <div className="page-stack"><section className="page-heading"><div><p className="eyebrow">Todos os clientes</p><h1>Dispositivos</h1><p>Pesquise por nome, IP ou MAC e gerencie cada dispositivo.</p></div></section><DeviceList devices={devices} onSelect={setSelectedDevice} /></div>;
    if (view === 'history') return <History entries={audit} />;
    if (view === 'settings') return <Settings systemStatus={systemStatus} diagnostics={diagnostics} ports={ports} testing={testing} onTestConnection={testConnection} onManagePort={openPort} />;
    if (view === 'port' && selectedPort) return <PortDetail port={selectedPort} saving={busy} onSaveSpeed={savePortSpeed} onDeviceSelect={setSelectedDevice} onBack={() => navigate('dashboard')} />;
    return <div className="empty-state"><h2>Porta não encontrada</h2><button className="button primary" type="button" onClick={() => navigate('dashboard')}>Voltar ao dashboard</button></div>;
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">M</span><div><strong>MikroTik</strong><small>Local Manager</small></div></div>
        <nav aria-label="Navegação principal">{navItems.map((item) => <button key={item.id} type="button" className={view === item.id ? 'nav-item active' : 'nav-item'} aria-current={view === item.id ? 'page' : undefined} onClick={() => navigate(item.id)}><span aria-hidden="true">{item.symbol}</span>{item.label}</button>)}</nav>
        <div className="sidebar-foot"><span>Local only</span><small>v0.1 · Fase 1</small></div>
      </aside>
      <main className="main-content">
        <header className="topbar"><div><span className="topbar-title">MikroTik Local Manager</span><small>{systemStatus?.mockMode ? 'Dados simulados' : 'RouterOS'}</small></div>{systemStatus && <StatusBadge status={systemStatus.connected ? 'CONNECTED' : 'DISCONNECTED'} />}</header>
        {error && <section className="notice error" role="alert"><div><strong>Não foi possível concluir a ação.</strong><span>{error}</span></div><button type="button" className="button secondary compact" onClick={() => void refresh()}>Tentar novamente</button></section>}
        {systemStatus?.fastTrackDetected && <section className="notice warning"><strong>⚠ FastTrack detectado</strong><span>A verificação é informativa nesta fase; nenhuma regra será alterada automaticamente.</span></section>}
        {content()}
      </main>
      <DeviceDetails device={selectedDevice} busy={busy} onClose={() => setSelectedDevice(null)} onSave={saveDevice} onRequestBlock={(device) => setConfirmation({ device, block: !device.blocked })} />
      <ConfirmDialog open={Boolean(confirmation)} title={confirmation?.block ? `Bloquear ${confirmation.device.displayName}?` : `Liberar ${confirmation?.device.displayName ?? 'dispositivo'}?`} description={confirmation?.block ? 'O dispositivo perderá acesso à rede. Esta alteração é reversível.' : 'O acesso será liberado novamente para este dispositivo.'} confirmLabel={confirmation?.block ? 'Bloquear' : 'Liberar acesso'} busy={busy} onCancel={() => !busy && setConfirmation(null)} onConfirm={confirmBlock} />
    </div>
  );
}
