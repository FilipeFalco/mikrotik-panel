import { useEffect, useState } from 'react';
import { bpsToMbps, formatRate, parseMbps } from '../format';
import type { Device } from '../types';
import { StatusBadge } from './StatusBadge';

interface DeviceDetailsProps {
  device: Device | null;
  busy: boolean;
  readOnly?: boolean;
  onClose: () => void;
  onSave: (device: Device, friendlyName: string, notes: string, downloadBps: number, uploadBps: number) => void;
  onRequestBlock: (device: Device) => void;
}

export function DeviceDetails({ device, busy, readOnly = false, onClose, onSave, onRequestBlock }: DeviceDetailsProps) {
  const [friendlyName, setFriendlyName] = useState('');
  const [notes, setNotes] = useState('');
  const [download, setDownload] = useState('');
  const [upload, setUpload] = useState('');
  const [errors, setErrors] = useState<{ download?: string; upload?: string }>({});

  useEffect(() => {
    if (!device) return;
    setFriendlyName(device.friendlyName ?? '');
    setNotes(device.notes ?? '');
    setDownload(bpsToMbps(device.downloadLimitBps));
    setUpload(bpsToMbps(device.uploadLimitBps));
    setErrors({});
  }, [device]);

  if (!device) return null;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (readOnly) {
      onSave(device, friendlyName, notes, device.downloadLimitBps, device.uploadLimitBps);
      return;
    }
    const parsedDownload = parseMbps(download);
    const parsedUpload = parseMbps(upload);
    setErrors({
      download: parsedDownload.valid ? undefined : parsedDownload.error,
      upload: parsedUpload.valid ? undefined : parsedUpload.error,
    });
    if (!parsedDownload.valid || !parsedUpload.valid) return;
    onSave(device, friendlyName, notes, parsedDownload.bps, parsedUpload.bps);
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
          <div className="form-section-heading"><p className="eyebrow">Metadata local</p><small>Estes campos são salvos apenas no painel local.</small></div>
          <label>Nome amigável<input value={friendlyName} onChange={(event) => setFriendlyName(event.target.value)} placeholder={device.hostname ?? device.macAddress} /></label>
          <label>Observações<textarea value={notes} onChange={(event) => setNotes(event.target.value)} rows={2} /></label>
          <div className="form-section-heading"><p className="eyebrow">Controles RouterOS</p><small>{readOnly ? 'Disponível apenas quando a escrita RouterOS for habilitada em uma fase futura.' : 'Limites e bloqueio são aplicados pelo RouterOS.'}</small></div>
          <div className="two-columns">
            <label>Limite download<div className="input-with-unit"><input inputMode="decimal" value={download} onChange={(event) => { setDownload(event.target.value); setErrors((current) => ({ ...current, download: undefined })); }} aria-label="Limite de download em Mbps" aria-invalid={Boolean(errors.download)} aria-describedby={errors.download ? 'device-download-error' : undefined} disabled={readOnly} /><span>Mbps</span></div>{errors.download && <small id="device-download-error" className="field-error" role="alert">{errors.download}</small>}</label>
            <label>Limite upload<div className="input-with-unit"><input inputMode="decimal" value={upload} onChange={(event) => { setDownload(event.target.value); setErrors((current) => ({ ...current, upload: undefined })); }} aria-label="Limite de upload em Mbps" aria-invalid={Boolean(errors.upload)} aria-describedby={errors.upload ? 'device-upload-error' : undefined} disabled={readOnly} /><span>Mbps</span></div>{errors.upload && <small id="device-upload-error" className="field-error" role="alert">{errors.upload}</small>}</label>
          </div>
          <div className="dialog-actions split-actions">
            <span title={readOnly ? 'Disponível apenas quando a escrita RouterOS for habilitada em uma fase futura.' : undefined}>
              <button type="button" className={device.blocked ? 'button secondary' : 'button danger'} onClick={() => onRequestBlock(device)} disabled={busy || readOnly}>
                {device.blocked ? 'Liberar acesso' : 'Bloquear dispositivo'}
              </button>
            </span>
            <button type="submit" className="button primary" disabled={busy}>{busy ? 'Salvando…' : readOnly ? 'Salvar metadata local' : 'Salvar alterações'}</button>
          </div>
        </form>
      </section>
    </div>
  );
}
