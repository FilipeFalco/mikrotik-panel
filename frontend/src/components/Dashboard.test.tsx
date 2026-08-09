import { fireEvent, render, screen } from '@testing-library/react';
import { it, vi } from 'vitest';
import { Dashboard } from './Dashboard';
import type { Port } from '../types';

const port: Port = {
  interfaceName: 'ether2', friendlyName: 'Cliente João', description: 'Casa João', network: '10.10.10.0/24', dhcpServer: 'dhcp-joao',
  managed: true, enabled: true, running: true, disabled: false, deviceCount: 5, onlineDeviceCount: 3, blockedDeviceCount: 1,
  downloadLimitBps: 100_000_000, uploadLimitBps: 20_000_000, downloadTrafficBps: 72_000_000, uploadTrafficBps: 8_000_000, devices: [],
};

it('renders a managed port and opens its management screen', () => {
  const onManage = vi.fn();
  render(<Dashboard ports={[port]} onManage={onManage} />);
  expect(screen.getByText('Cliente João')).toBeInTheDocument();
  expect(screen.getByText((_, element) => element?.textContent === '5 dispositivos')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Gerenciar' }));
  expect(onManage).toHaveBeenCalledWith('ether2');
});
