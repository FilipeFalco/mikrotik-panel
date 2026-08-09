import { statusLabel } from '../format';
import type { DeviceStatus } from '../types';

interface StatusBadgeProps {
  status: DeviceStatus | 'CONNECTED' | 'DISCONNECTED';
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const label = status === 'CONNECTED' ? 'Conectado' : status === 'DISCONNECTED' ? 'Desconectado' : statusLabel(status);
  const className = status.toLowerCase().replace('_', '-');
  return <span className={`status-badge ${className}`}><span aria-hidden="true" />{label}</span>;
}
