package com.martecyber.plugins.bughunting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.ares.projects.ScopeFacade;
import com.martecyber.ares.workflows.WorkflowFacade;
import com.martecyber.ares.workflows.WorkflowScope;
import com.martecyber.plugins.bughunting.dto.UpsertBugHuntingProgramRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only the Workflows Phase H+ sync behavior (managed workflow tracks sync_enabled/
 *  sync_interval_hours) — the pre-existing scope-entry/sync-trigger behavior isn't touched by
 *  this change and isn't re-tested here. */
class BugHuntingProgramServiceWorkflowSyncTest {

    private ProjectBugHuntingProgramRepository programRepo;
    private ProjectFacade projectFacade;
    private WorkflowFacade workflowFacade;
    private BugHuntingProgramService service;

    @BeforeEach
    void setUp() {
        programRepo = mock(ProjectBugHuntingProgramRepository.class);
        projectFacade = mock(ProjectFacade.class);
        workflowFacade = mock(WorkflowFacade.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowFacade> workflowFacadeProvider = mock(ObjectProvider.class);
        when(workflowFacadeProvider.getObject()).thenReturn(workflowFacade);
        @SuppressWarnings("unchecked")
        ObjectProvider<BugHuntingSyncJobHandler> syncJobHandlerProvider = mock(ObjectProvider.class);
        service = new BugHuntingProgramService(programRepo, projectFacade,
            mock(ScopeFacade.class), mock(JobFacade.class), new ObjectMapper(),
            syncJobHandlerProvider, workflowFacadeProvider, mock(PlatformTransactionManager.class));

        when(programRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubProjectType(Long projectId, String code) {
        when(projectFacade.exists(projectId)).thenReturn(true);
        when(projectFacade.getProjectTypeCode(projectId)).thenReturn(Optional.of(code));
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullyConfiguredProgramCreatesAProjectScopedCronPlusIntegrationCallGraph() {
        stubProjectType(1L, "BH_INTG");
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());
        when(workflowFacade.createOrReplace(eq("bug-hunting"), eq(WorkflowScope.PROJECT), eq(1L), any(), any(), any()))
            .thenReturn(50L);

        var req = new UpsertBugHuntingProgramRequest(9L, "acme/acme-program", true, 6);
        service.upsert(1L, req);

        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowFacade).createOrReplace(eq("bug-hunting"), eq(WorkflowScope.PROJECT), eq(1L), any(), any(), graphCaptor.capture());
        var nodes = (List<Map<String, Object>>) graphCaptor.getValue().get("nodes");
        assertEquals("TRIGGER_CRON", nodes.get(0).get("type"));
        var triggerConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(0).get("data")).get("config");
        assertEquals("0 */6 * * *", triggerConfig.get("cronExpression"));
        var actionConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(1).get("data")).get("config");
        assertEquals("bug-hunting", actionConfig.get("integrationType"));
        assertEquals("sync_scope", actionConfig.get("action"));

        verify(workflowFacade).setEnabled(50L, true);
    }

    @Test
    void everyTwentyFourHoursIsExpressedAsOnceDailyNotAnUnsupportedStepOfTwentyFour() {
        stubProjectType(1L, "BH_INTG");
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());
        when(workflowFacade.createOrReplace(any(), any(), any(), any(), any(), any())).thenReturn(50L);

        service.upsert(1L, new UpsertBugHuntingProgramRequest(9L, "acme/acme-program", true, 24));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowFacade).createOrReplace(any(), any(), any(), any(), any(), graphCaptor.capture());
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) graphCaptor.getValue().get("nodes");
        @SuppressWarnings("unchecked")
        var triggerConfig = (Map<String, Object>) ((Map<String, Object>) nodes.get(0).get("data")).get("config");
        assertEquals("0 0 * * *", triggerConfig.get("cronExpression"));
    }

    @Test
    void rejectsASyncIntervalThatIsNotOneOfTheAllowedCronSteps() {
        stubProjectType(1L, "BH_INTG");
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> service.upsert(1L, new UpsertBugHuntingProgramRequest(9L, "acme/acme-program", true, 5)));
        verifyNoInteractions(workflowFacade);
    }

    @Test
    void aGenericPlatformNeverCreatesAWorkflowEvenIfFullyConfigured() {
        stubProjectType(1L, "BH");
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());

        service.upsert(1L, new UpsertBugHuntingProgramRequest(null, null, true, 24));

        verifyNoInteractions(workflowFacade);
    }

    @Test
    void notYetFullyConfiguredNeverCreatesAWorkflow() {
        stubProjectType(1L, "BH_INTG");
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());

        // integrationId set but no programHandle yet — not fully configured.
        service.upsert(1L, new UpsertBugHuntingProgramRequest(9L, null, true, 24));

        verifyNoInteractions(workflowFacade);
    }

    @Test
    void disablingSyncKeepsTheWorkflowButDisablesIt() {
        stubProjectType(1L, "BH_INTG");
        ProjectBugHuntingProgram existing = new ProjectBugHuntingProgram();
        existing.setProjectId(1L);
        existing.setPlatform("intigriti");
        existing.setIntegrationId(9L);
        existing.setProgramHandle("acme/acme-program");
        existing.setSyncIntervalHours(6);
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(workflowFacade.createOrReplace(any(), any(), any(), any(), any(), any())).thenReturn(50L);

        service.upsert(1L, new UpsertBugHuntingProgramRequest(null, null, false, null));

        verify(workflowFacade).setEnabled(50L, false);
    }

    @Test
    void disconnectDeletesTheManagedWorkflowIfOnePresent() {
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());
        when(workflowFacade.find("bug-hunting", WorkflowScope.PROJECT, 1L)).thenReturn(Optional.of(50L));

        service.disconnect(1L);

        verify(workflowFacade).delete(50L);
    }

    @Test
    void disconnectIsANoOpWhenNoManagedWorkflowExists() {
        when(programRepo.findByProjectId(1L)).thenReturn(Optional.empty());
        when(workflowFacade.find("bug-hunting", WorkflowScope.PROJECT, 1L)).thenReturn(Optional.empty());

        service.disconnect(1L);

        verify(workflowFacade, never()).delete(any());
    }

    @Test
    void backfillOnlySyncsWorkflowsForSyncEnabledProgramsAndReturnsTheirCount() {
        ProjectBugHuntingProgram enabled = new ProjectBugHuntingProgram();
        enabled.setProjectId(1L);
        enabled.setPlatform("intigriti");
        enabled.setIntegrationId(9L);
        enabled.setProgramHandle("acme/acme-program");
        enabled.setSyncEnabled(true);
        enabled.setSyncIntervalHours(6);

        ProjectBugHuntingProgram disabled = new ProjectBugHuntingProgram();
        disabled.setProjectId(2L);
        disabled.setSyncEnabled(false);

        when(programRepo.findAll()).thenReturn(List.of(enabled, disabled));
        when(workflowFacade.createOrReplace(eq("bug-hunting"), eq(WorkflowScope.PROJECT), eq(1L), any(), any(), any()))
            .thenReturn(50L);

        int count = service.backfillManagedWorkflows();

        assertEquals(1, count);
        verify(workflowFacade).createOrReplace(eq("bug-hunting"), eq(WorkflowScope.PROJECT), eq(1L), any(), any(), any());
        verify(workflowFacade, never()).createOrReplace(eq("bug-hunting"), eq(WorkflowScope.PROJECT), eq(2L), any(), any(), any());
    }
}
