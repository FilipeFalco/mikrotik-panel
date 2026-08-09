import { useEffect, useState } from 'react';
import { bpsToMbps, formatRate, parseMbps } from '../format';
import type { Device, Port } from '../types';
import { StatusBadge } from './StatusBadge';
import { DeviceList } from './DeviceList';

interface PortDetailProps {
  port: Port;
  saving: boolean;
  onSaveSpeed: (port: Port, downloadBps: number, uploadBps: number) => void;
  onDeviceSelect: (device: Device) => void;
  onBack: () => void;
}

export function PortDetail({ port, saving, onSaveSpeed, onDeviceSelect, onBack }: PortDetailProps) {
  const [download, setDownload] = useState(bpsToMbps(port.downloadLimitBps));
  const [upload, setUpload] = useState(bpsToMbps(port.uploadLimitBps));
  const [errors, setErrors] = useState<{ download?: string; upload?: string }>({});

  useEffect(() => {
    setDownload(bpsToMbps(port.downloadLimitBps));
    setUpload(bpsToMbps(port.uploadLimitBps));
    setErrors({});
  }, [port.interfaceName, port.downloadLimitBps, port.uploadLimitBps]);

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    const parsedDownload = parseMbps(download);
    const parsedUpload = parseMbps(upload);
    setErrors({
      download: parsedDownload.valid ? undefined : parsedDownload.error,
      upload: parsedUpload.valid ? undefined : parsedUpload.error,
    });
    if (!parsedDownload.valid || !parsedUpload.valid) return;
    onSaveSpeed(port, parsedDownload.bps, parsedUpload.bps);
  };

  return (
    <div className="page-stack">
      <button type="button" className="back-link" onClick={onBack}>← Voltar ao dashboard</button>
      <section className="port-detail-heading">
        <div>
          <p className="eyebrow">{port.interfaceName}</p>
          <h1>{port.friendlyName}</h1>
          <p>{port.network ?? 'Rede não configurada'}</p>
        </div>
        <div className="detail-status"><StatusBadge status={port.running && !port.disabled ? 'ONLINE' : 'OFFLINE'} /><small>↓ {formatRate(port.downloadTrafficBps)} · ↑ {formatRate(port.uploadTrafficBps)}</small></div>
      </section>

      <section className="surface speed-editor">
        <div className="section-heading"><div><p className="eyebrow">Controle de banda</p><h2>Limite total da rede</h2></div><p className="subtle">O mock aplica a alteração imediatamente.</p></div>
        <form onSubmit={save} className="speed-form">
          <label>Download <div className="input-with-unit"><input inputMode="decimal" value={download} onChange={(event) => { setDownload(event.target.value); setErrors((current) => ({ ...current, download: undefined })); }} aria-label="Limite de download em Mbps" aria-invalid={Boolean(errors.download)} aria-describedby={errors.download ? 'port-download-error' : undefined} /><span>Mbps</span></div>{errors.download && <small id="port-download-error" className="field-error" role="alert">{errors.download}</small>}</label>
          <label>Upload <div className="input-with-unit"><input inputMode="decimal" value={upload} onChange={(event) => { setUpload(event.target.value); setErrors((current) => ({ ...current, upload: undefined })); }} aria-label="Limite de upload em Mbps" aria-invalid={Boolean(errors.upload)} aria-describedby={errors.upload ? 'port-upload-error' : undefined} /><span>Mbps</span></div>{errors.upload && <small id="port-upload-error" className="field-error" role="alert">{errors.upload}</small>}</label>
          <button type="submit" className="button primary" disabled={saving}>{saving ? 'Salvando…' : 'Salvar limite'}</button>
        </form>
      </section>

      <DeviceList devices={port.devices} onSelect={onDeviceSelect} title={`Dispositivos · ${port.friendlyName}`} />
    </div>
  );
}
