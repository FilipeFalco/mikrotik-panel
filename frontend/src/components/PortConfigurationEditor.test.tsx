import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { PortConfigurationEditor } from './PortConfigurationEditor';
import type { Port } from '../types';

const discoveredPort: Port = {
  interfaceName: 'ether2',
  friendlyName: 'ether2',
  description: null,
  network: null,
  dhcpServer: null,
  role: null,
  managed: false,
  enabled: false,
  running: true,
  disabled: false,
  deviceCount: 0,
  onlineDeviceCount: 0,
  blockedDeviceCount: 0,
  downloadLimitBps: 0,
  uploadLimitBps: 0,
  downloadTrafficBps: 0,
  uploadTrafficBps: 0,
  devices: [],
};

it('configures a discovered interface locally and displays the CIDR returned by the backend', async () => {
  const savedPort: Port = {
    ...discoveredPort,
    friendlyName: 'Cliente João',
    description: 'Casa João',
    network: '10.10.10.0/24',
    dhcpServer: 'dhcp-cliente1',
    role: 'CLIENT',
    managed: true,
    enabled: true,
  };
  const onSave = vi.fn(async () => savedPort);

  render(<PortConfigurationEditor port={discoveredPort} saving={false} onSave={onSave} onClose={vi.fn()} />);

  expect(screen.getByText('Estas configurações são armazenadas apenas no painel local. Nenhuma configuração será alterada no MikroTik.')).toBeInTheDocument();
  expect(screen.getByText('ether2')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Nome amigável'), { target: { value: 'Cliente João' } });
  fireEvent.change(screen.getByLabelText('Descrição'), { target: { value: 'Casa João' } });
  fireEvent.change(screen.getByLabelText('Rede/CIDR'), { target: { value: '10.10.10.17/24' } });
  fireEvent.change(screen.getByLabelText('DHCP Server'), { target: { value: 'dhcp-cliente1' } });
  fireEvent.click(screen.getByLabelText('Exibir no dashboard'));
  fireEvent.click(screen.getByRole('button', { name: 'Salvar configuração local' }));

  await waitFor(() => expect(onSave).toHaveBeenCalledWith('ether2', {
    friendlyName: 'Cliente João',
    description: 'Casa João',
    network: '10.10.10.17/24',
    dhcpServer: 'dhcp-cliente1',
    enabled: true,
    role: 'CLIENT',
  }));
  expect(screen.getByLabelText('Rede/CIDR')).toHaveValue('10.10.10.0/24');
});
