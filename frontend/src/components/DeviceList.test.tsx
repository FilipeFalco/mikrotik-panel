import { fireEvent, render, screen } from '@testing-library/react';
import { it, vi } from 'vitest';
import { DeviceList } from './DeviceList';
import type { Device } from '../types';

const devices: Device[] = [
  { displayName: 'Notebook João', friendlyName: 'Notebook João', hostname: 'DESKTOP-E8FH29', ipAddress: '10.10.10.22', macAddress: 'AA:BB:CC:DD:EE:02', interfaceName: 'ether2', portFriendlyName: 'Cliente João', dhcpServer: 'dhcp-joao', status: 'ONLINE', blocked: false, leaseComment: null, notes: null, downloadLimitBps: 50_000_000, uploadLimitBps: 10_000_000, downloadTrafficBps: 2_000_000, uploadTrafficBps: 1_000_000, lastSeenAt: null },
  { displayName: 'Xbox João', friendlyName: null, hostname: 'Xbox-Joao', ipAddress: '10.10.10.41', macAddress: 'AA:BB:CC:DD:EE:04', interfaceName: 'ether2', portFriendlyName: 'Cliente João', dhcpServer: 'dhcp-joao', status: 'BLOCKED', blocked: true, leaseComment: null, notes: null, downloadLimitBps: 0, uploadLimitBps: 0, downloadTrafficBps: 0, uploadTrafficBps: 0, lastSeenAt: null },
];

it('filters devices by search and blocked state', () => {
  render(<DeviceList devices={devices} onSelect={vi.fn()} />);
  fireEvent.change(screen.getByPlaceholderText('Buscar por nome, IP ou MAC'), { target: { value: 'notebook' } });
  expect(screen.getByText('Notebook João')).toBeInTheDocument();
  expect(screen.queryByText('Xbox João')).not.toBeInTheDocument();
  fireEvent.change(screen.getByPlaceholderText('Buscar por nome, IP ou MAC'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Bloqueados' }));
  expect(screen.getByText('Xbox João')).toBeInTheDocument();
  expect(screen.queryByText('Notebook João')).not.toBeInTheDocument();
});
