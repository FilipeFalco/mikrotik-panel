import { useEffect, useState, type FormEvent } from 'react';
import type { Port, PortRole } from '../types';

export interface LocalPortConfiguration {
  friendlyName: string;
  description: string;
  network: string;
  dhcpServer: string;
  enabled: boolean;
  role: PortRole;
}

interface PortConfigurationEditorProps {
  port: Port;
  saving: boolean;
  onSave: (interfaceName: string, configuration: LocalPortConfiguration) => Promise<Port | null>;
  onClose: () => void;
}

function configurationFor(port: Port): LocalPortConfiguration {
  return {
    friendlyName: port.friendlyName,
    description: port.description ?? '',
    network: port.network ?? '',
    dhcpServer: port.dhcpServer ?? '',
    enabled: port.enabled,
    // Newly discovered interfaces receive an explicit, safe local role when saved.
    role: port.role ?? 'CLIENT',
  };
}

export function PortConfigurationEditor({ port, saving, onSave, onClose }: PortConfigurationEditorProps) {
  const [configuration, setConfiguration] = useState<LocalPortConfiguration>(() => configurationFor(port));

  useEffect(() => {
    setConfiguration(configurationFor(port));
  }, [port]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const updatedPort = await onSave(port.interfaceName, configuration);
    if (updatedPort) {
      // The backend owns CIDR validation and normalization, so always render its result.
      setConfiguration(configurationFor(updatedPort));
    }
  };

  return (
    <section className="surface local-port-editor" aria-labelledby="port-configuration-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Configuração local</p>
          <h2 id="port-configuration-title">Configurar interface</h2>
        </div>
        <button className="icon-button" type="button" aria-label="Fechar configuração da porta" onClick={onClose}>×</button>
      </div>
      <div className="notice info local-only-notice">
        <span>Estas configurações são armazenadas apenas no painel local. Nenhuma configuração será alterada no MikroTik.</span>
      </div>
      <form className="port-configuration-form" onSubmit={(event) => void submit(event)}>
        <label>
          Interface
          <output className="readonly-value">{port.interfaceName}</output>
        </label>
        <label>
          Nome amigável
          <input
            value={configuration.friendlyName}
            onChange={(event) => setConfiguration((current) => ({ ...current, friendlyName: event.target.value }))}
            maxLength={100}
            required
          />
        </label>
        <label>
          Descrição
          <textarea
            value={configuration.description}
            onChange={(event) => setConfiguration((current) => ({ ...current, description: event.target.value }))}
            maxLength={500}
            rows={2}
          />
        </label>
        <div className="two-columns">
          <label>
            Rede/CIDR
            <input
              value={configuration.network}
              onChange={(event) => setConfiguration((current) => ({ ...current, network: event.target.value }))}
              placeholder="10.10.10.0/24"
              maxLength={64}
            />
          </label>
          <label>
            DHCP Server
            <input
              value={configuration.dhcpServer}
              onChange={(event) => setConfiguration((current) => ({ ...current, dhcpServer: event.target.value }))}
              maxLength={100}
            />
          </label>
        </div>
        <label>
          Função local da interface
          <select
            value={configuration.role}
            onChange={(event) => setConfiguration((current) => ({ ...current, role: event.target.value as PortRole }))}
          >
            <option value="CLIENT">Cliente</option>
            <option value="WAN">WAN (Internet)</option>
          </select>
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={configuration.enabled}
            onChange={(event) => setConfiguration((current) => ({ ...current, enabled: event.target.checked }))}
          />
          Exibir no dashboard
        </label>
        <div className="dialog-actions">
          <button type="button" className="button secondary" onClick={onClose} disabled={saving}>Cancelar</button>
          <button type="submit" className="button primary" disabled={saving}>{saving ? 'Salvando…' : 'Salvar configuração local'}</button>
        </div>
      </form>
    </section>
  );
}
