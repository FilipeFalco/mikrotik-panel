package com.mikrotikmanager.service;

import com.mikrotikmanager.domain.ManagedDeviceBlockRule;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.RouterFirewallFilter;
import com.mikrotikmanager.domain.RouterSnapshot;
import com.mikrotikmanager.domain.BlockDeviceIntent;
import com.mikrotikmanager.domain.UnblockDeviceIntent;
import com.mikrotikmanager.gateway.DeviceBlockMutationGateway;
import com.mikrotikmanager.gateway.ManagedResourceIdentifier;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteClientException;
import com.mikrotikmanager.gateway.routeros.RouterOsWriteErrorType;
import com.mikrotikmanager.support.ApiErrorCode;
import com.mikrotikmanager.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the only real RouterOS mutations supported by Phase 4.
 * Every execution captures and replans from a fresh snapshot while holding
 * the per-MAC lock; no preview plan, id, fingerprint, or ownership value from
 * the browser is accepted as authorization.
 */
@Service
public final class DeviceBlockExecutionService {
    private static final Logger log = LoggerFactory.getLogger(DeviceBlockExecutionService.class);

    private final RouterSnapshotService snapshotService;
    private final OperationPlanningService planningService;
    private final DeviceBlockMutationGateway mutationGateway;
    private final DeviceService deviceService;
    private final OperationLockManager lockManager;
    private final MikrotikWriteGuard writeGuard;
    private final AuditService auditService;

    public DeviceBlockExecutionService(RouterSnapshotService snapshotService,
                                       OperationPlanningService planningService,
                                       DeviceBlockMutationGateway mutationGateway,
                                       DeviceService deviceService,
                                       OperationLockManager lockManager,
                                       MikrotikWriteGuard writeGuard,
                                       AuditService auditService) {
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.planningService = Objects.requireNonNull(planningService, "planningService");
        this.mutationGateway = Objects.requireNonNull(mutationGateway, "mutationGateway");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.writeGuard = Objects.requireNonNull(writeGuard, "writeGuard");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    public DeviceView block(String rawMacAddress) {
        return execute(rawMacAddress, true);
    }

    public DeviceView unblock(String rawMacAddress) {
        return execute(rawMacAddress, false);
    }

    private DeviceView execute(String rawMacAddress, boolean block) {
        String mac = normalize(rawMacAddress);
        return lockManager.withLock("DEVICE:" + mac, () -> executeLocked(mac, block));
    }

    private DeviceView executeLocked(String mac, boolean block) {
        String action = block ? "BLOCK_DEVICE" : "UNBLOCK_DEVICE";
        log.info("{} operation started target={}", block ? "Block" : "Unblock", mac);
        // Flags and credential separation are checked before the read/write flow.
        try {
            writeGuard.checkDeviceBlockWriteAllowed();
        } catch (RuntimeException exception) {
            auditFailure(action, mac, errorCode(exception));
            throw exception;
        }

        RouterSnapshot before;
        OperationPlan plan;
        try {
            before = snapshotService.capture();
            plan = planningService.planFromSnapshot(block ? new BlockDeviceIntent(mac) : new UnblockDeviceIntent(mac), before);
            validatePlan(plan, mac);
        } catch (RuntimeException exception) {
            auditFailure(action, mac, errorCode(exception));
            throw exception;
        }

        if (block && hasExactlyOneDesiredRule(before, mac)) {
            try {
                Verification verification = verifyBlock(before, mac);
                if (!verification.success()) {
                    throw verificationFailure(verification);
                }
            } catch (ApiException exception) {
                auditFailure(action, mac, errorCode(exception));
                throw exception;
            }
            auditSuccess(action, mac, true);
            log.info("{} operation no-op target={}", block ? "Block" : "Unblock", mac);
            return deviceService.getDevice(mac);
        }
        if (!block && managedRules(before, mac).isEmpty()) {
            auditSuccess(action, mac, true);
            log.info("Unblock operation no-op target={}", mac);
            return deviceService.getDevice(mac);
        }

        try {
            if (block) {
                String placeBefore = firstStaticForwardId(before);
                mutationGateway.createManagedDeviceBlockRule(new ManagedDeviceBlockRule(mac), placeBefore);
                return finishBlock(mac, action);
            }

            RouterFirewallFilter rule = managedRules(before, mac).getFirst();
            if (rule.id() == null || rule.id().isBlank()) {
                throw api(ApiErrorCode.DEVICE_BLOCK_CONFLICT, HttpStatus.CONFLICT,
                        "A regra gerenciada não possui um identificador RouterOS seguro para remoção.");
            }
            mutationGateway.deleteManagedDeviceBlockRule(rule.id());
            return finishUnblock(mac, action);
        } catch (RouterOsWriteClientException exception) {
            return recoverTransportOutcome(mac, block, action, exception);
        } catch (ApiException exception) {
            auditFailure(action, mac, errorCode(exception));
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(action, mac, ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED.name());
            throw api(ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED, HttpStatus.BAD_GATEWAY,
                    "A alteração foi interrompida porque a verificação do estado RouterOS falhou.");
        }
    }

    private DeviceView finishBlock(String mac, String action) {
        RouterSnapshot after = snapshotService.capture();
        Verification verification = verifyBlock(after, mac);
        if (!verification.success()) {
            throw verificationFailure(verification);
        }
        auditSuccess(action, mac, false);
        log.info("Block operation verified target={}", mac);
        return deviceService.getDevice(mac);
    }

    private DeviceView finishUnblock(String mac, String action) {
        RouterSnapshot after = snapshotService.capture();
        if (!managedRules(after, mac).isEmpty()) {
            throw api(ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED, HttpStatus.BAD_GATEWAY,
                    "A regra MTMGR de bloqueio ainda está presente após a remoção.");
        }
        auditSuccess(action, mac, false);
        log.info("Unblock operation verified target={}", mac);
        return deviceService.getDevice(mac);
    }

    private DeviceView recoverTransportOutcome(String mac, boolean block, String action,
                                                RouterOsWriteClientException exception) {
        try {
            if (exception.errorType() == RouterOsWriteErrorType.NOT_FOUND && !block) {
                RouterSnapshot reread = snapshotService.capture();
                if (managedRules(reread, mac).isEmpty()) {
                    auditSuccess(action, mac, true);
                    return deviceService.getDevice(mac);
                }
            }

            if (exception.errorType() == RouterOsWriteErrorType.OUTCOME_UNKNOWN) {
                RouterSnapshot reread = snapshotService.capture();
                if (block) {
                    Verification verification = verifyBlock(reread, mac);
                    if (verification.success()) {
                        auditSuccess(action, mac, false);
                        log.info("Block operation recovered target={}", mac);
                        return deviceService.getDevice(mac);
                    }
                    if (verification.ambiguous()) {
                        throw api(ApiErrorCode.AMBIGUOUS_OWNERSHIP, HttpStatus.CONFLICT,
                                "O resultado da escrita é ambíguo: há mais de uma regra MTMGR para o dispositivo.");
                    }
                    if (verification.unsafeOrder()) {
                        throw verificationFailure(verification);
                    }
                } else if (managedRules(reread, mac).isEmpty()) {
                    auditSuccess(action, mac, false);
                    log.info("Unblock operation recovered target={}", mac);
                    return deviceService.getDevice(mac);
                }
            }
        } catch (ApiException exceptionFromRecovery) {
            auditFailure(action, mac, errorCode(exceptionFromRecovery));
            throw exceptionFromRecovery;
        } catch (RuntimeException recoveryFailure) {
            auditFailure(action, mac, ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED.name());
            throw api(ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED, HttpStatus.BAD_GATEWAY,
                    "O estado RouterOS não pôde ser relido para confirmar o resultado da escrita.");
        }

        ApiErrorCode code = switch (exception.errorType()) {
            case CREDENTIALS_MISSING -> ApiErrorCode.MIKROTIK_WRITE_CREDENTIALS_MISSING;
            case DEVICE_BLOCK_WRITES_DISABLED -> ApiErrorCode.DEVICE_BLOCK_WRITES_DISABLED;
            case GLOBAL_WRITES_DISABLED -> ApiErrorCode.MIKROTIK_WRITES_DISABLED;
            case PERMISSION_DENIED -> ApiErrorCode.MIKROTIK_WRITE_PERMISSION_DENIED;
            case NOT_FOUND, REJECTED -> ApiErrorCode.MIKROTIK_OPERATION_FAILED;
            case OUTCOME_UNKNOWN -> ApiErrorCode.MIKROTIK_WRITE_OUTCOME_UNKNOWN;
        };
        auditFailure(action, mac, code.name());
        HttpStatus status = code == ApiErrorCode.MIKROTIK_WRITE_PERMISSION_DENIED ? HttpStatus.FORBIDDEN
                : code == ApiErrorCode.MIKROTIK_WRITE_OUTCOME_UNKNOWN ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        throw api(code, status, switch (code) {
            case MIKROTIK_WRITE_PERMISSION_DENIED -> "O usuário de escrita do MikroTik não possui permissão para esta operação.";
            case MIKROTIK_WRITE_OUTCOME_UNKNOWN -> "O resultado da escrita no MikroTik não pôde ser confirmado; nenhuma repetição automática foi feita.";
            case MIKROTIK_WRITE_CREDENTIALS_MISSING -> "As credenciais separadas de escrita do MikroTik não estão configuradas.";
            default -> "A alteração no MikroTik não pôde ser concluída.";
        });
    }

    private void validatePlan(OperationPlan plan, String mac) {
        if (!mac.equals(plan.target().macAddress())) {
            throw api(ApiErrorCode.DEVICE_BLOCK_CONFLICT, HttpStatus.CONFLICT,
                    "O alvo da operação não corresponde ao MAC normalizado solicitado.");
        }
        if (!plan.readyForFutureExecution()) {
            if (plan.conflicts().stream().anyMatch(conflict -> "AMBIGUOUS_OWNERSHIP".equals(conflict.code()))) {
                throw api(ApiErrorCode.AMBIGUOUS_OWNERSHIP, HttpStatus.CONFLICT,
                        "Há mais de uma regra de bloqueio com ownership equivalente; nenhuma escrita foi feita.");
            }
            if (plan.conflicts().stream().anyMatch(conflict -> "MANAGED_BLOCK_RULE_DRIFT".equals(conflict.code()))) {
                throw api(ApiErrorCode.MANAGED_BLOCK_RULE_DRIFT, HttpStatus.CONFLICT,
                        "A regra MTMGR de bloqueio divergiu da forma esperada; análise manual é obrigatória.");
            }
            if (plan.conflicts().stream().anyMatch(conflict -> "UNOWNED_BLOCK_RESOURCE".equals(conflict.code()))) {
                throw api(ApiErrorCode.UNOWNED_BLOCK_RESOURCE, HttpStatus.CONFLICT,
                        "O dispositivo possui um bloqueio manual ou não comprovado que a aplicação não pode remover.");
            }
            throw api(ApiErrorCode.DEVICE_BLOCK_CONFLICT, HttpStatus.CONFLICT,
                    "As pré-condições atuais não permitem alterar o bloqueio do dispositivo.");
        }
    }

    private String firstStaticForwardId(RouterSnapshot snapshot) {
        Optional<RouterFirewallFilter> first = snapshot.firewallFilters().stream()
                .filter(filter -> !filter.dynamic() && "forward".equalsIgnoreCase(filter.chain()))
                .findFirst();
        if (first.isEmpty()) {
            return null;
        }
        String id = first.get().id();
        if (id == null || id.isBlank()) {
            throw api(ApiErrorCode.DEVICE_BLOCK_POSITION_UNSAFE, HttpStatus.CONFLICT,
                    "A ordem do firewall não pôde ser comprovada porque a primeira regra forward não possui .id.");
        }
        return id;
    }

    private Verification verifyBlock(RouterSnapshot snapshot, String mac) {
        List<RouterFirewallFilter> rules = managedRules(snapshot, mac);
        if (rules.size() > 1) {
            return new Verification(false, true, false,
                    "O RouterOS contém mais de uma regra MTMGR para o dispositivo.");
        }
        if (rules.isEmpty()) {
            return new Verification(false, false, false,
                    "O RouterOS não confirmou a regra MTMGR de bloqueio.");
        }
        RouterFirewallFilter rule = rules.getFirst();
        if (!ManagedDeviceBlockRule.isDesired(rule, mac)) {
            return new Verification(false, false, false,
                    "A regra MTMGR criada não possui a semântica esperada.");
        }
        int position = snapshot.firewallFilters().indexOf(rule);
        boolean safe = position >= 0;
        if (safe) {
            for (int index = 0; index < snapshot.firewallFilters().size(); index++) {
                RouterFirewallFilter other = snapshot.firewallFilters().get(index);
                if (index != position && !other.dynamic() && "forward".equalsIgnoreCase(other.chain())
                        && position > index) {
                    safe = false;
                    break;
                }
            }
        }
        return safe ? new Verification(true, false, false, "ok")
                : new Verification(false, false, true,
                "A regra MTMGR não ficou antes das demais regras forward estáticas; o bloqueio não foi confirmado.");
    }

    private List<RouterFirewallFilter> managedRules(RouterSnapshot snapshot, String mac) {
        return snapshot.firewallFilters().stream()
                .filter(filter -> ManagedDeviceBlockRule.isOwned(filter, mac))
                .toList();
    }

    private boolean hasExactlyOneDesiredRule(RouterSnapshot snapshot, String mac) {
        List<RouterFirewallFilter> rules = managedRules(snapshot, mac);
        return rules.size() == 1 && ManagedDeviceBlockRule.isDesired(rules.getFirst(), mac);
    }

    private ApiException verificationFailure(Verification verification) {
        ApiErrorCode code = verification.ambiguous() ? ApiErrorCode.AMBIGUOUS_OWNERSHIP
                : verification.unsafeOrder() ? ApiErrorCode.DEVICE_BLOCK_POSITION_UNSAFE
                : ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED;
        HttpStatus status = (code == ApiErrorCode.AMBIGUOUS_OWNERSHIP
                || code == ApiErrorCode.DEVICE_BLOCK_POSITION_UNSAFE)
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_GATEWAY;
        return api(code, status, verification.message());
    }

    private String normalize(String rawMacAddress) {
        try {
            return ManagedResourceIdentifier.normalizeMac(rawMacAddress);
        } catch (IllegalArgumentException exception) {
            throw api(ApiErrorCode.INVALID_INPUT, HttpStatus.BAD_REQUEST, "Endereço MAC inválido.");
        }
    }

    private ApiException api(ApiErrorCode code, HttpStatus status, String message) {
        return new ApiException(code, status, message);
    }

    private void auditSuccess(String action, String mac, boolean noChange) {
        boolean block = action.startsWith("BLOCK");
        String previous = noChange ? (block ? "BLOCKED" : "UNBLOCKED") : (block ? "UNBLOCKED" : "BLOCKED");
        String next = noChange ? "NO_CHANGE" : block ? "BLOCKED" : "UNBLOCKED";
        safeAudit(action, mac, previous, next, true);
    }

    private void auditFailure(String action, String mac, String code) {
        safeAudit(action, mac, "UNKNOWN", code, false);
    }

    private void safeAudit(String action, String mac, String previous, String next, boolean success) {
        try {
            auditService.record(action, "DEVICE", mac, previous, next, success, success ? null : next);
        } catch (RuntimeException exception) {
            log.warn("Device block audit could not be persisted action={} target={}", action, mac);
        }
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return apiException.code().name();
        }
        if (exception instanceof RouterOsWriteClientException writeException) {
            return switch (writeException.errorType()) {
                case CREDENTIALS_MISSING -> ApiErrorCode.MIKROTIK_WRITE_CREDENTIALS_MISSING.name();
                case DEVICE_BLOCK_WRITES_DISABLED -> ApiErrorCode.DEVICE_BLOCK_WRITES_DISABLED.name();
                case GLOBAL_WRITES_DISABLED -> ApiErrorCode.MIKROTIK_WRITES_DISABLED.name();
                case PERMISSION_DENIED -> ApiErrorCode.MIKROTIK_WRITE_PERMISSION_DENIED.name();
                case OUTCOME_UNKNOWN -> ApiErrorCode.MIKROTIK_WRITE_OUTCOME_UNKNOWN.name();
                case NOT_FOUND, REJECTED -> ApiErrorCode.MIKROTIK_OPERATION_FAILED.name();
            };
        }
        return ApiErrorCode.MIKROTIK_POST_VERIFY_FAILED.name();
    }

    private record Verification(boolean success, boolean ambiguous, boolean unsafeOrder, String message) {
    }
}
