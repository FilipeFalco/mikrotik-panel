import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import App from './App';
import type { SystemStatus } from './types';

const disconnectedStatus: SystemStatus = {
  connected: false,
  mockMode: false,
  host: '192.168.88.1',
  port: 443,
  routerOsVersion: null,
  latencyMillis: null,
  message: 'RouterOS indisponível',
  fastTrackDetected: false,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

it('keeps the disconnected status visible when RouterOS data is unavailable', async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/system/status') {
      return { ok: true, json: async () => disconnectedStatus } as Response;
    }
    throw new Error('RouterOS indisponível');
  });
  vi.stubGlobal('fetch', fetchMock);

  render(<App />);

  expect(await screen.findByRole('heading', { name: 'MikroTik desconectado' })).toBeInTheDocument();
  expect(screen.getByText('192.168.88.1:443')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeEnabled();
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/system/status', expect.anything()));
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
