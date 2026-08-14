import { afterEach, expect, it, vi } from 'vitest';
import { api } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
});

it('sends a local port configuration PUT with an encoded interface name and role', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) }) as Response);
  vi.stubGlobal('fetch', fetchMock);

  await api.updatePort(
    'sfp/sfpplus1',
    'Link principal',
    'Uplink local',
    '10.10.10.17/24',
    'dhcp-uplink',
    true,
    'WAN',
  );

  expect(fetchMock).toHaveBeenCalledWith('/api/ports/sfp%2Fsfpplus1', expect.objectContaining({
    method: 'PUT',
    body: JSON.stringify({
      friendlyName: 'Link principal',
      description: 'Uplink local',
      network: '10.10.10.17/24',
      dhcpServer: 'dhcp-uplink',
      enabled: true,
      role: 'WAN',
    }),
  }));
});

it('posts only untrusted dry-run intent to local plan endpoints', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ executable: false }) }) as Response);
  vi.stubGlobal('fetch', fetchMock);

  await api.planBlockDevice('AA:BB:CC:DD:EE:01');
  await api.planDeviceSpeed('AA:BB:CC:DD:EE:01', 50_000_000, 10_000_000);
  await api.planPortSpeed('sfp/sfpplus1', 100_000_000, 20_000_000);

  expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/plans/block', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ macAddress: 'AA:BB:CC:DD:EE:01' }),
  }));
  expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/plans/device-speed', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ macAddress: 'AA:BB:CC:DD:EE:01', downloadBps: 50_000_000, uploadBps: 10_000_000 }),
  }));
  expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/plans/port-speed', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ interfaceName: 'sfp/sfpplus1', downloadBps: 100_000_000, uploadBps: 20_000_000 }),
  }));
});

it('executes block state with only the MAC in the URL and no plan or RouterOS payload', async () => {
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) }) as Response);
  vi.stubGlobal('fetch', fetchMock);

  await api.blockDevice('AA:BB:CC:DD:EE:01');
  await api.unblockDevice('AA:BB:CC:DD:EE:01');

  expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/devices/AA%3ABB%3ACC%3ADD%3AEE%3A01/block', expect.objectContaining({ method: 'POST' }));
  expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/devices/AA%3ABB%3ACC%3ADD%3AEE%3A01/block', expect.objectContaining({ method: 'DELETE' }));
  for (const [, init] of fetchMock.mock.calls as unknown as Array<[RequestInfo | URL, RequestInit | undefined]>) {
    expect(init?.body).toBeUndefined();
    expect(JSON.stringify(init)).not.toMatch(/plan|fingerprint|ownership|\.id|routeros/i);
  }
});
