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
