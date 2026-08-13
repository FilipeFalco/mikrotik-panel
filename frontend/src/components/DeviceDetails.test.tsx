import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { DeviceDetails } from './DeviceDetails';
import type { Device } from '../types';

const device: Device = {
  displayName: 'Notebook João',
  friendlyName: null,
  hostname: 'notebook-joao',
  ipAddress: '10.10.10.21',
  macAddress: 'AA:BB:CC:DD:EE:01',
  interfaceName: 'ether2',
  portFriendlyName: 'Cliente João',
  dhcpServer: 'dhcp-cliente1',
  status: 'ONLINE',
  blocked: false,
  leaseComment: null,
  notes: null,
  downloadLimitBps: 100_000_000,
  uploadLimitBps: 20_000_000,
  downloadTrafficBps: 0,
  uploadTrafficBps: 0,
  lastSeenAt: null,
};

it('keeps local metadata editable while disabling RouterOS controls in read-only mode', () => {
  const onSave = vi.fn();
  render(
    <DeviceDetails
      device={device}
      busy={false}
      readOnly
      onClose={vi.fn()}
      onSave={onSave}
      onRequestBlock={vi.fn()}
    />,
  );

  expect(screen.getByLabelText('Nome amigável')).toBeEnabled();
  expect(screen.getByLabelText('Observações')).toBeEnabled();
  expect(screen.getByLabelText('Limite de download em Mbps')).toBeDisabled();
  expect(screen.getByLabelText('Limite de upload em Mbps')).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Bloquear dispositivo' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Salvar metadata local' })).toBeEnabled();

  fireEvent.change(screen.getByLabelText('Nome amigável'), { target: { value: 'Notebook da sala' } });
  fireEvent.click(screen.getByRole('button', { name: 'Salvar metadata local' }));

  expect(onSave).toHaveBeenCalledWith(device, 'Notebook da sala', '', 100_000_000, 20_000_000);
});

it('saves independently edited download and upload limits when RouterOS controls are enabled', () => {
  const onSave = vi.fn();
  render(
    <DeviceDetails
      device={device}
      busy={false}
      readOnly={false}
      onClose={vi.fn()}
      onSave={onSave}
      onRequestBlock={vi.fn()}
    />,
  );

  fireEvent.change(screen.getByLabelText('Limite de download em Mbps'), { target: { value: '80' } });
  fireEvent.change(screen.getByLabelText('Limite de upload em Mbps'), { target: { value: '15' } });
  fireEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }));

  expect(onSave).toHaveBeenCalledWith(device, '', '', 80_000_000, 15_000_000);
});

it('offers a dry-run preview beside disabled real RouterOS controls', () => {
  const onPreviewBlock = vi.fn();
  const onPreviewSpeed = vi.fn();
  render(
    <DeviceDetails
      device={device}
      busy={false}
      readOnly
      onClose={vi.fn()}
      onSave={vi.fn()}
      onRequestBlock={vi.fn()}
      onPreviewBlock={onPreviewBlock}
      onPreviewSpeed={onPreviewSpeed}
    />,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Visualizar plano' }));
  fireEvent.click(screen.getByRole('button', { name: 'Visualizar plano de limite' }));

  expect(onPreviewBlock).toHaveBeenCalledWith(device, true);
  expect(onPreviewSpeed).toHaveBeenCalledWith(device, 100_000_000, 20_000_000);
  expect(screen.getByRole('button', { name: 'Bloquear dispositivo' })).toBeDisabled();
});

it('starts a fresh block preview when the real block control is clicked', () => {
  const onPreviewBlock = vi.fn();
  render(
    <DeviceDetails
      device={device}
      busy={false}
      readOnly
      blockExecutionEnabled
      onClose={vi.fn()}
      onSave={vi.fn()}
      onRequestBlock={vi.fn()}
      onPreviewBlock={onPreviewBlock}
    />,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Bloquear dispositivo' }));
  expect(onPreviewBlock).toHaveBeenCalledWith(device, true);
});

it('keeps block preview available when the backend disables real execution', () => {
  const onPreviewBlock = vi.fn();
  render(
    <DeviceDetails
      device={device}
      busy={false}
      readOnly
      blockExecutionEnabled={false}
      onClose={vi.fn()}
      onSave={vi.fn()}
      onRequestBlock={vi.fn()}
      onPreviewBlock={onPreviewBlock}
    />,
  );

  expect(screen.getByRole('button', { name: 'Bloquear dispositivo' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Visualizar plano' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: 'Visualizar plano' }));
  expect(onPreviewBlock).toHaveBeenCalledWith(device, true);
});
