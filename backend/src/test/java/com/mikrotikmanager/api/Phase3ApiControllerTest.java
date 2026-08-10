package com.mikrotikmanager.api;

import com.mikrotikmanager.domain.BlockingStrategy;
import com.mikrotikmanager.domain.OperationPlan;
import com.mikrotikmanager.domain.PlanChange;
import com.mikrotikmanager.domain.PlanConflict;
import com.mikrotikmanager.domain.PlanPrecondition;
import com.mikrotikmanager.domain.PlanSeverity;
import com.mikrotikmanager.domain.PlanState;
import com.mikrotikmanager.domain.PlanTarget;
import com.mikrotikmanager.domain.PlanWarning;
import com.mikrotikmanager.domain.PlannedOperationType;
import com.mikrotikmanager.domain.ReadinessCheck;
import com.mikrotikmanager.domain.ReadinessSeverity;
import com.mikrotikmanager.domain.ReadinessSummary;
import com.mikrotikmanager.domain.ReconciliationFinding;
import com.mikrotikmanager.domain.ReconciliationReport;
import com.mikrotikmanager.domain.ReconciliationResource;
import com.mikrotikmanager.domain.ReconciliationStatus;
import com.mikrotikmanager.domain.ReconciliationSummary;
import com.mikrotikmanager.domain.ResourceOwnership;
import com.mikrotikmanager.domain.SpeedLimit;
import com.mikrotikmanager.domain.WriteReadinessReport;
import com.mikrotikmanager.config.AppProperties;
import com.mikrotikmanager.service.OperationPlanningService;
import com.mikrotikmanager.service.ReconciliationService;
import com.mikrotikmanager.service.WriteReadinessService;
import com.mikrotikmanager.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PlanController.class, ReconciliationController.class, WriteReadinessController.class,
        WriteAnalysisController.class},
        properties = "app.frontend-origin=http://localhost:5173")
@Import(GlobalExceptionHandler.class)
@EnableConfigurationProperties(AppProperties.class)
class Phase3ApiControllerTest {
    private static final Instant GENERATED_AT = Instant.parse("2026-08-09T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperationPlanningService operationPlanningService;

    @MockBean
    private ReconciliationService reconciliationService;

    @MockBean
    private WriteReadinessService writeReadinessService;

    @MockBean
    private com.mikrotikmanager.service.WriteAnalysisService writeAnalysisService;

    @Test
    void mapsOnDemandReconciliationToItsOwnSafeDto() throws Exception {
        when(reconciliationService.analyze()).thenReturn(reconciliation());

        mockMvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotFingerprint").value("fingerprint"))
                .andExpect(jsonPath("$.resources[0].status").value("CONFLICT"))
                .andExpect(jsonPath("$.resources[0].ownership").value("FOREIGN"))
                .andExpect(jsonPath("$.resources[0].findings[0].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.resources[0]['.id']").doesNotExist());

        verify(reconciliationService).analyze();
    }

    @Test
    void mapsWriteReadinessWithoutEnablingExecution() throws Exception {
        when(writeReadinessService.analyze()).thenReturn(readiness());

        mockMvc.perform(get("/api/write-readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEnabled").value(false))
                .andExpect(jsonPath("$.writeFlagEnabled").value(false))
                .andExpect(jsonPath("$.checks[0].code").value("ROUTEROS_CONNECTED"))
                .andExpect(jsonPath("$.summary.conflicts").value(1));

        verify(writeReadinessService).analyze();
    }

    @Test
    void mapsCombinedWriteAnalysisWithoutEnablingExecutionAndKeepsOneSnapshotFingerprint() throws Exception {
        when(writeAnalysisService.analyze()).thenReturn(new com.mikrotikmanager.service.WriteAnalysisService.WriteAnalysis(
                readiness(), reconciliation(), "fingerprint"));

        mockMvc.perform(get("/api/write-analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotFingerprint").value("fingerprint"))
                .andExpect(jsonPath("$.readiness.executionEnabled").value(false))
                .andExpect(jsonPath("$.readiness.summary.conflicts").value(1))
                .andExpect(jsonPath("$.reconciliation.resources[0].status").value("CONFLICT"))
                .andExpect(jsonPath("$.reconciliation.snapshotFingerprint").value("fingerprint"))
                .andExpect(content().string(not(containsString("router-secret"))))
                .andExpect(content().string(not(containsString("Authorization"))));

        verify(writeAnalysisService).analyze();
        verifyNoInteractions(writeReadinessService, reconciliationService);
    }

    @Test
    void acceptsOnlyIntentAndRebuildsBlockPlanOnTheBackend() throws Exception {
        when(operationPlanningService.planBlockDevice(eq("AA:BB:CC:DD:EE:01"))).thenReturn(plan(PlannedOperationType.BLOCK_DEVICE));

        mockMvc.perform(post("/api/plans/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"macAddress":"AA:BB:CC:DD:EE:01","ownership":"MANAGED","executable":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("BLOCK_DEVICE"))
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.ownership").value("MANAGED"))
                .andExpect(jsonPath("$.target.macAddress").value("AA:BB:CC:DD:EE:01"))
                .andExpect(jsonPath("$.currentState.speedLimit.downloadBps").value(100_000_000))
                .andExpect(jsonPath("$.conflicts[0].resourceName").value("Fila manual"));

        verify(operationPlanningService).planBlockDevice("AA:BB:CC:DD:EE:01");
        verifyNoInteractions(reconciliationService, writeReadinessService);
    }

    @Test
    void exposesTypedSpeedPlanEndpointsAndRejectsInvalidSpeedBeforePlanning() throws Exception {
        when(operationPlanningService.planPortSpeed(eq("ether2"), eq(new SpeedLimit(50_000_000L, 10_000_000L))))
                .thenReturn(plan(PlannedOperationType.SET_PORT_SPEED));

        mockMvc.perform(post("/api/plans/port-speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + "\"interfaceName\":\"ether2\",\"downloadBps\":50000000,\"uploadBps\":10000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationType").value("SET_PORT_SPEED"))
                .andExpect(jsonPath("$.executable").value(false));

        mockMvc.perform(post("/api/plans/device-speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\",\"downloadBps\":-1,\"uploadBps\":10000000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verify(operationPlanningService).planPortSpeed("ether2", new SpeedLimit(50_000_000L, 10_000_000L));
        verify(operationPlanningService, never()).planDeviceSpeed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void planResponseDoesNotLeakRawRouterFieldsOrCredentials() throws Exception {
        when(operationPlanningService.planUnblockDevice(eq("AA:BB:CC:DD:EE:01"))).thenReturn(plan(PlannedOperationType.UNBLOCK_DEVICE));

        mockMvc.perform(post("/api/plans/unblock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"macAddress\":\"AA:BB:CC:DD:EE:01\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("router-secret"))))
                .andExpect(content().string(not(containsString("Authorization"))))
                .andExpect(jsonPath("$['.id']").doesNotExist());
    }

    private ReconciliationReport reconciliation() {
        ReconciliationResource resource = new ReconciliationResource("SIMPLE_QUEUE", "ether2", "Queue da porta ether2",
                ResourceOwnership.FOREIGN, ReconciliationStatus.CONFLICT, "mtmgr-port-ether2", "10.10.10.0/24",
                "Fila manual", "10.10.10.0/24", true, List.of(new ReconciliationFinding("FOREIGN_QUEUE_CONFLICT",
                "Existe configuração manual conflitante.", PlanSeverity.BLOCKING)));
        return new ReconciliationReport(GENERATED_AT, "fingerprint", 2, 1, 1, 1, true, List.of(resource),
                new ReconciliationSummary(0, 1, 0, 0, 0, 1, 0, 0));
    }

    private WriteReadinessReport readiness() {
        return new WriteReadinessReport(GENERATED_AT, false, false, false, false,
                WriteReadinessReport.PHASE_3_EXECUTION_DISABLED_NOTICE,
                List.of(new ReadinessCheck("ROUTEROS_CONNECTED", "RouterOS conectado", true, ReadinessSeverity.INFO,
                        "Conexão de leitura confirmada.")),
                new ReadinessSummary(0, 1, 0, 0, 0, 1, 0, 1, 1));
    }

    private OperationPlan plan(PlannedOperationType type) {
        return new OperationPlan(UUID.fromString("f9e93705-0e76-48d6-9f91-4fb2c2bf91cf"), type,
                new PlanTarget("AA:BB:CC:DD:EE:01", "Galaxy S25", "AA:BB:CC:DD:EE:01", "ether2"),
                new PlanState("Galaxy S25", "AA:BB:CC:DD:EE:01", "10.10.10.21", "ether2", "10.10.10.0/24",
                        new SpeedLimit(100_000_000L, 20_000_000L), false, BlockingStrategy.UNDECIDED),
                new PlanState("Galaxy S25", "AA:BB:CC:DD:EE:01", "10.10.10.21", "ether2", "10.10.10.0/24",
                        new SpeedLimit(50_000_000L, 10_000_000L), true, BlockingStrategy.UNDECIDED),
                ResourceOwnership.MANAGED,
                List.of(new PlanPrecondition("DEVICE_EXISTS", "O dispositivo existe.", true, PlanSeverity.INFO)),
                List.of(new PlanWarning("FASTTRACK_ACTIVE", "FastTrack exige revisão.", PlanSeverity.WARNING)),
                List.of(new PlanConflict("FOREIGN_QUEUE_CONFLICT", "SIMPLE_QUEUE", "Fila manual", "10.10.10.0/24",
                        ResourceOwnership.FOREIGN, "A fila manual não será adotada.", PlanSeverity.BLOCKING)),
                List.of(new PlanChange("CANDIDATE", "SIMPLE_QUEUE", "Candidata apenas para fase futura.")),
                true, false, false, "Phase 3 is dry-run only; RouterOS execution is not implemented.", GENERATED_AT, "fingerprint");
    }
}
