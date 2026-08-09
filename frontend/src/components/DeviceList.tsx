import { useMemo, useState } from 'react';
import { formatLimit, formatRate } from '../format';
import type { Device, DeviceStatus } from '../types';
import { StatusBadge } from './StatusBadge';

type Filter = 'ALL' | DeviceStatus;

interface DeviceListProps {
  devices: Device[];
  onSelect: (device: Device) => void;
  title?: string;
}

export function DeviceList({ devices, onSelect, title = 'Dispositivos' }: DeviceListProps) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('ALL');
  const filteredDevices = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase('pt-BR');
    return devices.filter((device) => {
      const deviceStatus: DeviceStatus = device.blocked ? 'BLOCKED' : device.status;
      const matchesFilter = filter === 'ALL' || deviceStatus === filter;
      const searchable = [device.displayName, device.hostname, device.ipAddress, device.macAddress].filter(Boolean).join(' ').toLocaleLowerCase('pt-BR');
      return matchesFilter && (!normalizedQuery || searchable.includes(normalizedQuery));
    });
  }, [devices, filter, query]);

  return (
    <section className="device-section">
      <div className="section-heading">
        <div><p className="eyebrow">Inventário</p><h2>{title}</h2></div>
        <span className="subtle">{filteredDevices.length} exibidos</span>
      </div>
      <div className="device-controls">
        <label className="search-field"><span className="sr-only">Buscar por nome, IP ou MAC</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Buscar por nome, IP ou MAC" /></label>
        <div className="filter-group" aria-label="Filtrar dispositivos">
          {([['ALL', 'Todos'], ['ONLINE', 'Online'], ['OFFLINE', 'Offline'], ['BLOCKED', 'Bloqueados']] as const).map(([value, label]) => (
            <button key={value} type="button" className={filter === value ? 'filter active' : 'filter'} onClick={() => setFilter(value)}>{label}</button>
          ))}
        </div>
      </div>
      {filteredDevices.length ? (
        <div className="device-table-wrap">
          <table className="device-table">
            <thead><tr><th>Dispositivo</th><th>Rede</th><th>Status</th><th>Tráfego</th><th>Limite</th><th><span className="sr-only">Ações</span></th></tr></thead>
            <tbody>{filteredDevices.map((device) => (
              <tr key={device.macAddress}>
                <td><strong>{device.displayName}</strong><small>{device.hostname && device.hostname !== device.displayName ? device.hostname : device.macAddress}</small><small>{device.ipAddress ?? 'IP indisponível'}</small></td>
                <td><strong>{device.portFriendlyName ?? device.interfaceName}</strong><small>{device.interfaceName}</small></td>
                <td><StatusBadge status={device.blocked ? 'BLOCKED' : device.status} /></td>
                <td>↓ {formatRate(device.downloadTrafficBps)}<br />↑ {formatRate(device.uploadTrafficBps)}</td>
                <td>{formatLimit(device.downloadLimitBps, device.uploadLimitBps)}</td>
                <td><button type="button" className="button secondary compact" onClick={() => onSelect(device)}>Detalhes</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      ) : <div className="empty-state compact-empty"><p>Nenhum dispositivo corresponde aos filtros.</p></div>}
    </section>
  );
}
