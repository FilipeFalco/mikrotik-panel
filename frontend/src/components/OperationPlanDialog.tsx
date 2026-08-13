import { useEffect } from 'react';
import { formatLimit } from '../format';
import type { Device, OperationPlan, PlanSeverity, PlanState } from '../types';

interface OperationPlanDialogProps {
  open: boolean;
  plan: OperationPlan | null;
  loading: boolean;
  onClose: () => void;
  onRefresh: () => void;
  /** Capability for the real device block/unblock executor. */
  executionEnabled?: boolean;
  /** Current local device presentation, used only to show its friendly port name. */
  device?: Device | null;
  /** Opens the second, explicit confirmation step; it does not execute by itself. */
  onConfirm?: (plan: OperationPlan) => void;
}

function operationLabel(operation: OperationPlan['operationType']): string {
  return ({
    BLOCK_DEVICE: 'Bloqueio de dispositivo',
    UNBLOCK_DEVICE: 'Liberação de dispositivo',
    SET_DEVICE_SPEED: 'Limite de velocidade do dispositivo',
    SET_PORT_SPEED: 'Limite de velocidade da porta',
  } as const)[operation];
}

function severityClass(severity: PlanSeverity): string {
  return severity === 'BLOCKING' ? 'bad' : severity === 'WARNING' ? 'warning' : 'good';
}

function isDeviceBlockOperation(plan: OperationPlan): boolean {
  return plan.operationType === 'BLOCK_DEVICE' || plan.operationType === 'UNBLOCK_DEVICE';
}

/**
 * A plan is only eligible for the explicit confirmation step when the backend
 * has found a real change and no blocking safety finding remains. The plan id,
 * fingerprint and any RouterOS fields are intentionally not involved in this
 * decision or in the subsequent execution request.
 */
export function canConfirmOperationPlan(plan: OperationPlan, executionEnabled: boolean): boolean {
  return isDeviceBlockOperation(plan)
    && executionEnabled
    && plan.changeRequired
    && !plan.preconditions.some((precondition) => precondition.severity === 'BLOCKING' && !precondition.satisfied)
    && !plan.conflicts.some((conflict) => conflict.severity === 'BLOCKING');
}

function confirmationDisabledReason(plan: OperationPlan, executionEnabled: boolean): string | null {
  if (!executionEnabled) return 'A execução de bloqueio/liberação está desabilitada pela capability atual. O preview continua disponível.';
  if (!plan.changeRequired) return 'Confirmação desabilitada: o snapshot atual indica que nenhuma alteração é necessária.';
  if (plan.preconditions.some((precondition) => precondition.severity === 'BLOCKING' && !precondition.satisfied)) {
    return 'Confirmação desabilitada: existe uma pré-condição BLOCKING não atendida.';
  }
  if (plan.conflicts.some((conflict) => conflict.severity === 'BLOCKING')) {
    return 'Confirmação desabilitada: existe um conflito BLOCKING no snapshot atual.';
  }
  return null;
}

function stateSummary(state: PlanState): string[] {
  const facts: string[] = [];
  if (state.ipAddress) facts.push(`IP ${state.ipAddress}`);
  if (state.interfaceName) facts.push(`Interface ${state.interfaceName}`);
  if (state.network) facts.push(`Rede ${state.network}`);
  if (state.speedLimit) facts.push(`Limite ${formatLimit(state.speedLimit.downloadBps, state.speedLimit.uploadBps)}`);
  if (state.blocked !== null && state.blocked !== undefined) facts.push(state.blocked ? 'Bloqueado' : 'Liberado');
  return facts;
}

export function OperationPlanDialog({
  open,
  plan,
  loading,
  onClose,
  onRefresh,
  executionEnabled = false,
  device,
  onConfirm,
}: OperationPlanDialogProps) {
  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  const currentFacts = plan ? stateSummary(plan.currentState) : [];
  const desiredFacts = plan ? stateSummary(plan.desiredState) : [];
  const blockOperation = plan ? isDeviceBlockOperation(plan) : false;
  const macAddress = plan
    ? plan.target.macAddress ?? plan.currentState.macAddress ?? (blockOperation ? plan.target.identifier : null)
    : null;
  const interfaceName = plan ? plan.target.interfaceName ?? plan.currentState.interfaceName : null;
  const portLabel = device?.portFriendlyName ?? plan?.target.displayName ?? plan?.currentState.displayName ?? 'Porta não informada';
  const confirmDisabledReason = plan && blockOperation ? confirmationDisabledReason(plan, executionEnabled) : null;
  const canConfirm = Boolean(plan && blockOperation && canConfirmOperationPlan(plan, executionEnabled));

  return (
    <div className="modal-backdrop" role="presentation">
      <section className="plan-dialog" role="dialog" aria-modal="true" aria-labelledby="operation-plan-title">
        <div className="dialog-header">
          <div>
            <p className="simulation-notice">{blockOperation && executionEnabled ? 'PREVIEW — nenhuma alteração foi enviada ao MikroTik' : 'SIMULAÇÃO — nenhuma alteração será enviada ao MikroTik'}</p>
            <h2 id="operation-plan-title">Plano de operação</h2>
          </div>
          <button className="icon-button" type="button" aria-label="Fechar simulação" onClick={onClose}>×</button>
        </div>
        {loading && !plan ? <div className="plan-loading"><span className="loading-spinner" /><p>Analisando o estado atual somente por leitura…</p></div> : plan && (
          <div className="plan-content">
            <section className="plan-overview" aria-label="Resumo do plano">
              <div><span>Operação</span><strong>{operationLabel(plan.operationType)}</strong></div>
              <div><span>Alvo</span><strong>{plan.target.displayName ?? plan.target.identifier}</strong></div>
              <div><span>MAC</span><strong>{macAddress ?? 'Não aplicável'}</strong></div>
              <div><span>Interface/porta</span><strong>{interfaceName ?? 'Não informado'}</strong><small>{portLabel}</small></div>
              <div><span>Ownership</span><strong>{plan.ownership}</strong></div>
              <div><span>Execução RouterOS</span><strong className={blockOperation && executionEnabled ? 'plan-enabled' : 'plan-disabled'}>{blockOperation ? (executionEnabled ? 'Disponível após confirmação' : 'Desabilitada pela capability') : 'Desabilitada na Fase 3'}</strong></div>
              <div><span>Revisão futura</span><strong>{plan.readyForFutureExecution ? 'Pré-condições atendidas' : 'Revisão necessária'}</strong></div>
            </section>

            {!plan.changeRequired && <div className="notice info plan-phase-notice"><strong>Nenhuma alteração necessária no snapshot atual.</strong><span>Nenhuma escrita será enviada. Uma eventual confirmação futura sempre fará nova leitura e revalidação.</span></div>}

            <section className="plan-section">
              <h3>Estado observado e desejado</h3>
              <div className="plan-state-grid">
                <div><span>Atual</span>{currentFacts.length ? currentFacts.map((fact) => <small key={fact}>{fact}</small>) : <small>Não disponível.</small>}</div>
                <div><span>Desejado</span>{desiredFacts.length ? desiredFacts.map((fact) => <small key={fact}>{fact}</small>) : <small>Não disponível.</small>}</div>
              </div>
            </section>

            <section className="plan-section">
              <h3>Pré-condições</h3>
              <ul className="plan-list">
                {plan.preconditions.map((precondition) => <li key={precondition.code} className={severityClass(precondition.severity)}>
                  <span aria-hidden="true">{precondition.satisfied ? '✓' : precondition.severity === 'BLOCKING' ? '×' : '!'}</span>
                  <div><strong>{precondition.code}</strong><small>{precondition.description}</small></div>
                </li>)}
              </ul>
            </section>

            <section className="plan-section">
              <h3>Alterações planejadas</h3>
              <ul className="plan-list">
                {plan.plannedChanges.map((change, index) => <li key={`${change.action}-${index}`}>
                  <span aria-hidden="true">•</span><div><strong>{change.action}</strong><small>{change.description}</small></div>
                </li>)}
              </ul>
            </section>

            {plan.warnings.length > 0 && <section className="plan-section">
              <h3>Avisos</h3>
              <ul className="plan-list">
                {plan.warnings.map((warning) => <li key={warning.code} className={severityClass(warning.severity)}>
                  <span aria-hidden="true">!</span><div><strong>{warning.code}</strong><small>{warning.description}</small></div>
                </li>)}
              </ul>
            </section>}

            {plan.conflicts.length > 0 && <section className="plan-section">
              <h3>Conflitos</h3>
              <ul className="plan-list">
                {plan.conflicts.map((conflict) => <li key={`${conflict.code}-${conflict.resourceName ?? ''}`} className={severityClass(conflict.severity)}>
                  <span aria-hidden="true">×</span><div><strong>{conflict.resourceType}{conflict.resourceName ? ` · ${conflict.resourceName}` : ''}</strong><small>{conflict.description}</small>{conflict.resourceTarget && <small>Target: {conflict.resourceTarget} · Ownership: {conflict.ownership}</small>}</div>
                </li>)}
              </ul>
            </section>}

            <div className="notice warning plan-revalidation-notice"><strong>Aviso de revalidação obrigatória</strong><span>Este preview é efêmero. O estado, ownership, pré-condições e conflitos devem ser relidos pelo backend imediatamente antes da mutação.</span></div>
            {blockOperation && onConfirm && !canConfirm && <div className="notice info plan-phase-notice" role="status"><strong>Confirmação indisponível.</strong><span>{confirmDisabledReason ?? 'O plano não está elegível para execução.'}</span></div>}
            {!blockOperation && <div className="notice info plan-phase-notice"><strong>Execução permanece desabilitada.</strong><span>{plan.executionDisabledReason}</span></div>}
            {blockOperation && executionEnabled && canConfirm && <div className="notice info plan-phase-notice"><strong>Confirmação em duas etapas.</strong><span>O botão abaixo abre uma confirmação explícita; somente a confirmação final chamará a operação pelo MAC da URL.</span></div>}
          </div>
        )}
        <div className="dialog-actions plan-dialog-actions">
          <button type="button" className="button secondary" onClick={onClose}>Fechar</button>
          <button type="button" className="button primary" onClick={onRefresh} disabled={loading}>{loading ? 'Atualizando…' : 'Atualizar análise'}</button>
          {plan && blockOperation && onConfirm && <button
            type="button"
            className="button danger"
            onClick={() => { if (plan) onConfirm?.(plan); }}
            disabled={loading || !canConfirm}
            title={confirmDisabledReason ?? undefined}
          >
            {plan.operationType === 'BLOCK_DEVICE' ? 'Confirmar bloqueio' : 'Confirmar liberação'}
          </button>}
        </div>
      </section>
    </div>
  );
}
