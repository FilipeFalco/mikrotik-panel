import { useMemo, useState } from 'react';
import type { Diagnostics, Port, ReconciliationReport, ReconciliationResource, SystemStatus, WriteReadinessReport } from '../types';
import { PortConfigurationEditor, type LocalPortConfiguration } from './PortConfigurationEditor';
import { StatusBadge } from './StatusBadge';

interface SettingsProps {
  systemStatus: SystemStatus | null;
  diagnostics: Diagnostics | null;
  readiness?: WriteReadinessReport | null;
  reconciliation?: ReconciliationReport | null;
  ports: Port[];
  testing: boolean;
  saving: boolean;
  analyzing?: boolean;
  onTestConnection: () => void;
  onAnalyzeRouterOS?: () => void;
  onSavePort: (interfaceName: string, configuration: LocalPortConfiguration) => Promise<Port | null>;
}

function portRoleLabel(port: Port): string {
  if (port.role === 'WAN') return 'WAN local';
  if (port.role === 'CLIENT') return 'Cliente local';
  return 'Ainda não configurada';
}

function statusClass(status: ReconciliationResource['status']): string {
  if (status === 'CONFLICT' || status === 'AMBIGUOUS_OWNERSHIP') return 'blocking';
  if (status === 'DRIFTED' || status === 'MISSING') return 'warning';
  return 'good';
}

function statusLabel(status: ReconciliationResource['status']): string {
  return ({
    IN_SYNC: 'IN_SYNC',
    DRIFTED: 'DRIFTED',
    MISSING: 'MISSING',
    CONFLICT: 'CONFLICT',
    FOREIGN: 'FOREIGN',
    AMBIGUOUS_OWNERSHIP: 'AMBIGUOUS_OWNERSHIP',
    NOT_APPLICABLE: 'NOT_APPLICABLE',
  } as const)[status];
}

export function Settings({
  systemStatus,
  diagnostics,
  readiness = null,
  reconciliation = null,
  ports,
  testing,
  saving,
  analyzing = false,
  onTestConnection,
  onAnalyzeRouterOS,
  onSavePort,
}: SettingsProps) {
  const [selectedPortName, setSelectedPortName] = useState<string | null>(null);
  const selectedPort = useMemo(
    () => ports.find((port) => port.interfaceName === selectedPortName) ?? null,
    [ports, selectedPortName],
  );
  const summary = reconciliation?.summary ?? readiness?.summary ?? null;

  return (
    <div className="page-stack">
      <section className="page-heading"><div><p className="eyebrow">Ambiente local</p><h1>Configurações</h1><p>Credenciais ficam somente no backend, via arquivo <code>.env</code> ou variáveis de ambiente.</p></div></section>
      <section className="surface settings-status">
        <div><h2>MikroTik</h2><p>{systemStatus ? `${systemStatus.host}:${systemStatus.port}` : 'Carregando…'}</p></div>
        {systemStatus && <StatusBadge status={systemStatus.connected ? 'CONNECTED' : 'DISCONNECTED'} />}
        <button type="button" className="button secondary" onClick={onTestConnection} disabled={testing}>{testing ? 'Testando…' : 'Testar conexão'}</button>
      </section>
      {systemStatus?.mockMode && <section className="notice info"><strong>Mock mode ativo.</strong> Nenhuma operação desta tela alcança um RouterOS real.</section>}
      {diagnostics && <section className="surface"><div className="section-heading"><div><p className="eyebrow">Diagnóstico</p><h2>Estado dos serviços</h2></div><span className="subtle">RouterOS: {diagnostics.routerOsVersion ?? '—'} · {diagnostics.latencyMillis ?? '—'} ms</span></div><div className="diagnostics-grid">{diagnostics.checks.map((check) => <div key={check.name} className="diagnostic-item"><span className={check.available ? 'check good' : 'check bad'} aria-hidden="true" /> <div><strong>{check.name}</strong><small>{check.detail}</small></div></div>)}</div>{diagnostics.warning && <div className="notice warning">⚠ {diagnostics.warning}</div>}</section>}
      <section className="surface write-readiness" aria-labelledby="write-readiness-title">
        <div className="section-heading">
          <div><p className="eyebrow">Segurança de escrita</p><h2 id="write-readiness-title">Preparação para futuras alterações</h2></div>
          {onAnalyzeRouterOS && <button type="button" className="button secondary" onClick={onAnalyzeRouterOS} disabled={analyzing}>{analyzing ? 'Analisando…' : readiness || reconciliation ? 'Atualizar análise' : 'Analisar RouterOS'}</button>}
        </div>
        <div className="notice info local-only-notice"><strong>A Fase 4 permite somente bloqueio/liberação controlados.</strong><span>{readiness?.phaseNotice ?? 'A análise é sob demanda e usa somente leituras do RouterOS.'}</span></div>
        {!readiness ? <p className="subtle">Execute a análise para verificar ownership, conflitos, FastTrack e pré-condições para uma fase futura. Nenhuma configuração RouterOS será alterada.</p> : <>
          <div className="readiness-grid">
            {readiness.checks.map((check) => <div key={check.code} className={`readiness-item ${check.severity.toLowerCase()} ${check.satisfied ? 'satisfied' : 'unsatisfied'}`}>
              <span className="check" aria-hidden="true">{check.satisfied ? '✓' : check.severity === 'BLOCKING' ? '×' : '!'}</span>
              <div><strong>{check.description}</strong><small>{check.detail}</small></div>
            </div>)}
          </div>
          <div className="readiness-state">
            <span>Global write flag: <strong>{readiness.writeFlagEnabled ? 'Ativada' : 'Desabilitada'}</strong></span>
            <span>Device block flag: <strong>{readiness.deviceBlockWriteFlagEnabled ? 'Ativada' : 'Desabilitada'}</strong></span>
            <span>Credenciais write: <strong>{readiness.writeCredentialsConfigured ? 'Configuradas' : 'Ausentes'}</strong></span>
            <span>Estratégia: <strong>{readiness.blockingStrategy ?? 'FIREWALL_MAC_RULE'}</strong></span>
            <span>Ordem analisável: <strong>{readiness.firewallOrderingAnalyzable ? 'Sim' : 'Não'}</strong></span>
            <span>Execução: <strong>{readiness.executionEnabled ? 'Disponível após confirmação' : 'Desabilitada'}</strong></span>
          </div>
        </>}
        {summary && <div className="reconciliation-counts" aria-label="Contagens de reconciliação">
          <span>Managed <strong>{summary.managed}</strong></span><span>In sync <strong>{summary.inSync}</strong></span><span>Drifted <strong>{summary.drifted}</strong></span><span>Missing <strong>{summary.missing}</strong></span><span>Conflicts <strong>{summary.conflicts}</strong></span><span>Ambiguous <strong>{summary.ambiguous}</strong></span>
        </div>}
      </section>
      <section className="surface reconciliation-panel" aria-labelledby="reconciliation-title">
        <div className="section-heading"><div><p className="eyebrow">Reconciliação observacional</p><h2 id="reconciliation-title">Recursos relevantes</h2></div>{reconciliation && <span className="subtle">{reconciliation.resources.length} recurso(s) analisado(s)</span>}</div>
        {!reconciliation ? <p className="subtle">A reconciliação é executada somente quando você solicita a análise. Ela compara metadata local e o snapshot atual sem corrigir, adotar ou remover recursos.</p> : <>
          {reconciliation.summary.conflicts > 0 && <div className="notice warning foreign-conflict-notice"><strong>Configuração RouterOS manual conflitante detectada.</strong><span>A aplicação não irá alterá-la automaticamente nem assumir sua propriedade.</span></div>}
          <div className="reconciliation-list">
            {reconciliation.resources.map((resource) => <article key={`${resource.resourceType}-${resource.resourceKey}`} className="reconciliation-resource">
              <div className="reconciliation-resource-heading"><div><p className="eyebrow">{resource.resourceType}</p><h3>{resource.displayName}</h3></div><span className={`reconciliation-status ${statusClass(resource.status)}`}>{statusLabel(resource.status)}</span></div>
              <div className="resource-facts"><span>Ownership: <strong>{resource.ownership}</strong></span>{resource.expectedTarget && <span>Esperado: <strong>{resource.expectedTarget}</strong></span>}{resource.observedName && <span>Observado: <strong>{resource.observedName}{resource.observedTarget ? ` · ${resource.observedTarget}` : ''}</strong></span>}</div>
              {resource.findings.length > 0 && <ul className="resource-findings">{resource.findings.map((finding) => <li key={finding.code} className={finding.severity.toLowerCase()}><strong>{finding.code}</strong><span>{finding.description}</span></li>)}</ul>}
              {resource.conflict && <p className="foreign-conflict-copy">Existe configuração RouterOS manual que pode conflitar com a operação planejada. A aplicação não irá alterá-la automaticamente.</p>}
            </article>)}
          </div>
        </>}
      </section>
      <section className="surface">
        <div className="section-heading"><div><p className="eyebrow">Portas</p><h2>Interfaces descobertas</h2></div></div>
        <p className="subtle settings-port-description">Escolha uma interface descoberta para definir sua metadata local e como ela será apresentada no painel.</p>
        <div className="settings-port-list">
          {ports.map((port) => (
            <div key={port.interfaceName}>
              <div>
                <strong>{port.friendlyName}</strong>
                <small>{port.interfaceName} · {portRoleLabel(port)} · {port.network ?? 'Sem rede cadastrada'} · {port.enabled ? 'Ativa no painel' : 'Oculta no painel'}</small>
              </div>
              <button className="button secondary compact" type="button" onClick={() => setSelectedPortName(port.interfaceName)}>Configurar</button>
            </div>
          ))}
        </div>
      </section>
      {selectedPort && <PortConfigurationEditor port={selectedPort} saving={saving} onSave={onSavePort} onClose={() => setSelectedPortName(null)} />}
    </div>
  );
}
