import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { PortDetail } from './PortDetail';
import type { Port } from '../types';

const port: Port = {
  interfaceName: 'ether2', friendlyName: 'Cliente João', description: null, network: '10.10.10.0/24', dhcpServer: 'dhcp-joao',
  role: 'CLIENT',
  managed: true, enabled: true, running: true, disabled: false, deviceCount: 0, onlineDeviceCount: 0, blockedDeviceCount: 0,
  downloadLimitBps: 100_000_000, uploadLimitBps: 20_000_000, downloadTrafficBps: 0, uploadTrafficBps: 0, devices: [],
};

it('keeps invalid Mbps input from invoking the save operation', () => {
  const onSaveSpeed = vi.fn();
  render(<PortDetail port={port} saving={false} onSaveSpeed={onSaveSpeed} onDeviceSelect={vi.fn()} onBack={vi.fn()} />);

  fireEvent.change(screen.getByLabelText('Limite de download em Mbps'), { target: { value: '10O' } });
  fireEvent.click(screen.getByRole('button', { name: 'Salvar limite' }));

  expect(screen.getByRole('alert')).toHaveTextContent('Informe um valor em Mbps válido.');
  expect(onSaveSpeed).not.toHaveBeenCalled();
});

it('treats an empty Mbps field as an explicit unlimited limit', () => {
  const onSaveSpeed = vi.fn();
  render(<PortDetail port={port} saving={false} onSaveSpeed={onSaveSpeed} onDeviceSelect={vi.fn()} onBack={vi.fn()} />);

  fireEvent.change(screen.getByLabelText('Limite de download em Mbps'), { target: { value: '' } });
  fireEvent.change(screen.getByLabelText('Limite de upload em Mbps'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Salvar limite' }));

  expect(onSaveSpeed).toHaveBeenCalledWith(port, 0, 0);
});

it('disables RouterOS speed controls without disabling the local configuration flow elsewhere', () => {
  const onSaveSpeed = vi.fn();
  render(<PortDetail port={port} saving={false} readOnly onSaveSpeed={onSaveSpeed} onDeviceSelect={vi.fn()} onBack={vi.fn()} />);

  expect(screen.getByLabelText('Limite de download em Mbps')).toBeDisabled();
  expect(screen.getByLabelText('Limite de upload em Mbps')).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Salvar limite' })).toBeDisabled();
  expect(screen.getByText('Disponível apenas quando a escrita RouterOS for habilitada em uma fase futura.')).toBeInTheDocument();
});

it('keeps the real speed save disabled while allowing a dry-run preview', () => {
  const onPreviewSpeed = vi.fn();
  render(<PortDetail port={port} saving={false} readOnly onSaveSpeed={vi.fn()} onPreviewSpeed={onPreviewSpeed} onDeviceSelect={vi.fn()} onBack={vi.fn()} />);

  fireEvent.click(screen.getByRole('button', { name: 'Visualizar plano' }));

  expect(onPreviewSpeed).toHaveBeenCalledWith(port, 100_000_000, 20_000_000);
  expect(screen.getByRole('button', { name: 'Salvar limite' })).toBeDisabled();
});
