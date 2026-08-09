import type { DeviceStatus } from './types';

export function formatRate(bps: number): string {
  if (!bps) return '—';
  const units = ['bps', 'Kbps', 'Mbps', 'Gbps'];
  let value = bps;
  let index = 0;
  while (value >= 1000 && index < units.length - 1) {
    value /= 1000;
    index += 1;
  }
  const precision = value >= 100 || index === 0 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(precision)} ${units[index]}`;
}

export function formatLimit(downloadBps: number, uploadBps: number): string {
  if (!downloadBps && !uploadBps) return 'Sem limite';
  return `↓ ${formatRate(downloadBps)} · ↑ ${formatRate(uploadBps)}`;
}

export function bpsToMbps(bps: number): string {
  return bps ? String(bps / 1_000_000) : '';
}

export function mbpsToBps(value: string): number {
  const parsed = Number(value.replace(',', '.'));
  return Number.isFinite(parsed) && parsed >= 0 ? Math.round(parsed * 1_000_000) : 0;
}

export function statusLabel(status: DeviceStatus): string {
  return ({ ONLINE: 'Online', OFFLINE: 'Offline', BLOCKED: 'Bloqueado', UNKNOWN: 'Desconhecido' } as const)[status];
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value));
}
