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

export type MbpsParseResult =
  | { valid: true; bps: number }
  | { valid: false; error: string };

export function parseMbps(value: string): MbpsParseResult {
  const normalized = value.trim();

  // An empty field explicitly means "no limit". Any non-empty value must be a
  // decimal number so a typo can never silently become an unlimited limit.
  if (!normalized) return { valid: true, bps: 0 };
  if (!/^\d+(?:[,.]\d+)?$/.test(normalized)) {
    return { valid: false, error: 'Informe um valor em Mbps válido.' };
  }

  const mbps = Number(normalized.replace(',', '.'));
  const bps = Math.round(mbps * 1_000_000);
  if (!Number.isSafeInteger(bps)) {
    return { valid: false, error: 'Informe um valor em Mbps válido.' };
  }

  return { valid: true, bps };
}

export function mbpsToBps(value: string): number | null {
  const parsed = parseMbps(value);
  return parsed.valid ? parsed.bps : null;
}

export function statusLabel(status: DeviceStatus): string {
  return ({ ONLINE: 'Online', OFFLINE: 'Offline', BLOCKED: 'Bloqueado', UNKNOWN: 'Desconhecido' } as const)[status];
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value));
}
