import { formatLimit, formatRate } from '../format';
import type { Port } from '../types';
import { StatusBadge } from './StatusBadge';

interface DashboardProps {
  ports: Port[];
  onManage: (interfaceName: string) => void;
}

export function Dashboard({ ports, onManage }: DashboardProps) {
  const internetPort = ports.find((port) => port.interfaceName === 'ether1');
  const clientPorts = ports.filter((port) => port.enabled);

  return (
    <div className="page-stack">
      <section className="page-heading">
        <div>
          <p className="eyebrow">Visão geral</p>
          <h1>Dashboard</h1>
          <p>Portas e redes administradas localmente.</p>
        </div>
      </section>

      {internetPort && (
        <section className="internet-card" aria-label="Tráfego da internet">
          <div>
            <p className="eyebrow">Internet</p>
            <h2>{internetPort.friendlyName}</h2>
            <span className="subtle">{internetPort.interfaceName}</span>
          </div>
          <div className="traffic-pair">
            <span>↓ <strong>{formatRate(internetPort.downloadTrafficBps)}</strong></span>
            <span>↑ <strong>{formatRate(internetPort.uploadTrafficBps)}</strong></span>
          </div>
        </section>
      )}

      {clientPorts.length ? (
        <section className="port-grid" aria-label="Clientes configurados">
          {clientPorts.map((port) => (
            <article className="port-card" key={port.interfaceName}>
              <div className="card-heading">
                <div>
                  <p className="eyebrow">{port.interfaceName}</p>
                  <h2>{port.friendlyName}</h2>
                </div>
                <StatusBadge status={port.running && !port.disabled ? 'ONLINE' : 'OFFLINE'} />
              </div>
              <p className="network-label">{port.network ?? 'Rede não configurada'}</p>
              <div className="metric-row"><span>Limite total</span><strong>{formatLimit(port.downloadLimitBps, port.uploadLimitBps)}</strong></div>
              <div className="metric-row"><span>Tráfego atual</span><strong>↓ {formatRate(port.downloadTrafficBps)} · ↑ {formatRate(port.uploadTrafficBps)}</strong></div>
              <div className="device-summary">
                <span><strong>{port.deviceCount}</strong> dispositivos</span>
                <span><strong>{port.onlineDeviceCount}</strong> online</span>
                <span><strong>{port.blockedDeviceCount}</strong> bloqueados</span>
              </div>
              <button type="button" className="button primary full-width" onClick={() => onManage(port.interfaceName)}>Gerenciar</button>
            </article>
          ))}
        </section>
      ) : (
        <section className="empty-state"><h2>Nenhuma porta ativa no painel</h2><p>Configure as portas em Configurações para vê-las aqui.</p></section>
      )}
    </div>
  );
}
