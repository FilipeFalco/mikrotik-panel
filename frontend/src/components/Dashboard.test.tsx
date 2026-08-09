import { fireEvent, render, screen, within } from '@testing-library/react';
import { it, vi } from 'vitest';
import { Dashboard } from './Dashboard';
import type { Port } from '../types';

const port: Port = {
  interfaceName: 'ether2', friendlyName: 'Cliente João', description: 'Casa João', network: '10.10.10.0/24', dhcpServer: 'dhcp-joao',
  role: 'CLIENT',
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

it('renders an enabled local WAN instead of assuming ether1 is Internet', () => {
  const wan: Port = {
    ...port,
    interfaceName: 'ether5',
    friendlyName: 'Link dedicado',
    role: 'WAN',
    enabled: true,
  };

  render(<Dashboard ports={[wan, port]} onManage={vi.fn()} />);

  const internetCard = screen.getByLabelText('Tráfego da internet');
  expect(within(internetCard).getByText('Link dedicado')).toBeInTheDocument();
  expect(within(internetCard).getByText('ether5')).toBeInTheDocument();
  const clientCards = screen.getByLabelText('Clientes configurados');
  expect(within(clientCards).getByText('Cliente João')).toBeInTheDocument();
  expect(within(clientCards).queryByText('Link dedicado')).not.toBeInTheDocument();
});

it('does not render a disabled local WAN while keeping enabled CLIENT ports visible', () => {
  const wan: Port = {
    ...port,
    interfaceName: 'ether5',
    friendlyName: 'Link dedicado',
    role: 'WAN',
    enabled: false,
  };

  render(<Dashboard ports={[wan, port]} onManage={vi.fn()} />);

  expect(screen.queryByLabelText('Tráfego da internet')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Clientes configurados')).toBeInTheDocument();
  expect(screen.getByText('Cliente João')).toBeInTheDocument();
});
