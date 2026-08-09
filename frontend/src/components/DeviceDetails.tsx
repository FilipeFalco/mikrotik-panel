import { useEffect, useState } from 'react';
import { bpsToMbps, formatRate, mbpsToBps } from '../format';
import type { Device } from '../types';
import { StatusBadge } from './StatusBadge';

interface DeviceDetailsProps {
  device: Device | null;
  busy: boolean;
  onClose: () => void;
  onSave: (device: Device, friendlyName: string, notes: string, downloadBps: number, uploadBps: number) => void;
  onRequestBlock: (device: Device) => void;
}

export function DeviceDetails({ device, busy, onClose, onSave, onRequestBlock }: DeviceDetailsProps) {
  const [friendlyName, setFriendlyName] = useState('');
  const [notes, setNotes] = useState('');
  const [download, setDownload] = useState('');
  const [upload, setUpload] = useState('');

  useEffect(() => {
    if (!device) return;
    setFriendlyName(device.friendlyName ?? '');
    setNotes(device.notes ?? '');
    setDownload(bpsToMbps(device.downloadLimitBps));
    setUpload(bpsToMbps(device.uploadLimitBps));
  }, [device]);

  if (!device) return null;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    onSave(device, friendlyName, notes, mbpsToBps(download), mbpsToBps(upload));
  };

  return (
    <div className="modal-backdrop" role="presentation">
      <section className="device-dialog" role="dialog" aria-modal="true" aria-labelledby="device-details-title">
        <div className="dialog-header"><div><p className="eyebrow">{device.interfaceName} · {device.portFriendlyName}</p><h2 id="device-details-title">{device.displayName}</h2></div><button className="icon-button" type="button" aria-label="Fechar" onClick={onClose}>×</button></div>
        <div className="device-facts">
          <div><span>Hostname</span><strong>{device.hostname ?? 'Não informado'}</strong></div>
          <div><span>IP</span><strong>{device.ipAddress ?? 'Indisponível'}</strong></div>
          <div><span>MAC</span><strong>{device.macAddress}</strong></div>
          <div><span>Status</span><StatusBadge status={device.blocked ? 'BLOCKED' : device.status} /></div>
          <div><span>Download atual</span><strong>{formatRate(device.downloadTrafficBps)}</strong></div>
          <div><span>Upload atual</span><strong>{formatRate(device.uploadTrafficBps)}</strong></div>
        </div>
        <form onSubmit={submit} className="device-form">
          <label>Nome amigável<input value={friendlyName} onChange={(event) => setFriendlyName(event.target.value)} placeholder={device.hostname ?? device.macAddress} /></label>
          <label>Observações<textarea value={notes} onChange={(event) => setNotes(event.target.value)} rows={2} /></label>
          <div className="two-columns">
            <label>Limite download<div className="input-with-unit"><input inputMode="decimal" value={download} onChange={(event) => setDownload(event.target.value)} /><span>Mbps</span></div></label>
            <label>Limite upload<div className="input-with-unit"><input inputMode="decimal" value={upload} onChange={(event) => setUpload(event.target.value)} /><span>Mbps</span></div></label>
          </div>
          <div className="dialog-actions split-actions">
            <button type="button" className={device.blocked ? 'button secondary' : 'button danger'} onClick={() => onRequestBlock(device)} disabled={busy}>
              {device.blocked ? 'Liberar acesso' : 'Bloquear dispositivo'}
            </button>
            <button type="submit" className="button primary" disabled={busy}>{busy ? 'Salvando…' : 'Salvar'}</button>
          </div>
        </form>
      </section>
    </div>
  );
}
