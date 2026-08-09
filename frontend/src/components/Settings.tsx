import { useMemo, useState } from 'react';
import type { Diagnostics, Port, SystemStatus } from '../types';
import { PortConfigurationEditor, type LocalPortConfiguration } from './PortConfigurationEditor';
import { StatusBadge } from './StatusBadge';

interface SettingsProps {
  systemStatus: SystemStatus | null;
  diagnostics: Diagnostics | null;
  ports: Port[];
  testing: boolean;
  saving: boolean;
  onTestConnection: () => void;
  onSavePort: (interfaceName: string, configuration: LocalPortConfiguration) => Promise<Port | null>;
}

function portRoleLabel(port: Port): string {
  if (port.role === 'WAN') return 'WAN local';
  if (port.role === 'CLIENT') return 'Cliente local';
  return 'Ainda não configurada';
}

export function Settings({ systemStatus, diagnostics, ports, testing, saving, onTestConnection, onSavePort }: SettingsProps) {
  const [selectedPortName, setSelectedPortName] = useState<string | null>(null);
  const selectedPort = useMemo(
    () => ports.find((port) => port.interfaceName === selectedPortName) ?? null,
    [ports, selectedPortName],
  );

  return (
    <div className="page-stack">
      <section className="page-heading"><div><p className="eyebrow">Ambiente local</p><h1>Configurações</h1><p>Credenciais ficam somente no backend, via arquivo <code>.env</code> ou variáveis de ambiente.</p></div></section>
      <section className="surface settings-status">
        <div><h2>MikroTik</h2><p>{systemStatus ? `${systemStatus.host}:${systemStatus.port}` : 'Carregando…'}</p></div>
        {systemStatus && <StatusBadge status={systemStatus.connected ? 'CONNECTED' : 'DISCONNECTED'} />}
        <button type="button" className="button secondary" onClick={onTestConnection} disabled={testing}>{testing ? 'Testando…' : 'Testar conexão'}</button>
      </section>
      {systemStatus?.mockMode && <section className="notice info"><strong>Mock mode ativo.</strong> Nenhuma operação desta tela alcança um RouterOS real.</section>}
      {diagnostics && <section className="surface"><div className="section-heading"><div><p className="eyebrow">Diagnóstico</p><h2>Estado dos serviços</h2></div><span className="subtle">RouterOS: {diagnostics.routerOsVersion ?? '—'} · {diagnostics.latencyMillis ?? '—'} ms</span></div><div className="diagnostics-grid">{diagnostics.checks.map((check) => <div key={check.name} className="diagnostic-item"><span className={check.available ? 'check good' : 'check bad'} aria-hidden="true" /> <div><strong>{check.name}</strong><small>{check.detail}</small></div></div>)}</div>{diagnostics.warning && <div className="notice warning">⚠ {diagnostics.warning}</div>}</section>}
      <section className="surface">
        <div className="section-heading"><div><p className="eyebrow">Portas</p><h2>Interfaces descobertas</h2></div></div>
        <p className="subtle settings-port-description">Escolha uma interface descoberta para definir sua metadata local e como ela será apresentada no painel.</p>
        <div className="settings-port-list">
          {ports.map((port) => (
            <div key={port.interfaceName}>
              <div>
                <strong>{port.friendlyName}</strong>
                <small>{port.interfaceName} · {portRoleLabel(port)} · {port.network ?? 'Sem rede cadastrada'} · {port.enabled ? 'Ativa no painel' : 'Oculta no painel'}</small>
              </div>
              <button className="button secondary compact" type="button" onClick={() => setSelectedPortName(port.interfaceName)}>Configurar</button>
            </div>
          ))}
        </div>
      </section>
      {selectedPort && <PortConfigurationEditor port={selectedPort} saving={saving} onSave={onSavePort} onClose={() => setSelectedPortName(null)} />}
    </div>
  );
}
