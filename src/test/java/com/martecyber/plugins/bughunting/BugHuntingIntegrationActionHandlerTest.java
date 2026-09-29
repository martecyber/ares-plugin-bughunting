package com.martecyber.plugins.bughunting;

import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.jobs.JobView;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BugHuntingIntegrationActionHandlerTest {

    private BugHuntingProgramService programService;
    private ProjectFacade projectFacade;
    private JobFacade jobFacade;
    private BugHuntingIntegrationActionHandler handler;

    @BeforeEach
    void setUp() {
        programService = mock(BugHuntingProgramService.class);
        projectFacade = mock(ProjectFacade.class);
        jobFacade = mock(JobFacade.class);
        handler = new BugHuntingIntegrationActionHandler(programService, projectFacade, jobFacade);
    }

    @Test
    void supportsOnlyProjectScope() {
        assertEquals(Set.of("project"), handler.supportedScopes());
    }

    @Test
    void describesOneAction() {
        var codes = handler.describeActions().stream().map(a -> a.code()).toList();
        assertEquals(java.util.List.of("sync_scope"), codes);
    }

    @Test
    void listInstancesReturnsOneSyntheticEntry() {
        assertEquals(1, handler.listInstances("project", 1L).size());
    }

    @Test
    void startDelegatesToProgramServiceAndReturnsTheJobId() {
        when(programService.triggerSync(7L)).thenReturn(42L);
        assertEquals(42L, handler.start("sync_scope", 0L, "project", 7L, Map.of()));
    }

    @Test
    void startRejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class, () -> handler.start("bogus", 0L, "project", 7L, Map.of()));
    }

    @Test
    void checkStatusMapsCompletedJobToCompletedResult() {
        when(jobFacade.get(11L)).thenReturn(new JobView(11L, "completed", "{\"scopeCreated\":5}", null, 100));

        IntegrationActionResult result = handler.checkStatus(11L);

        assertEquals(IntegrationActionResult.COMPLETED, result.state());
        assertEquals("{\"scopeCreated\":5}", result.outputJson());
    }

    @Test
    void checkStatusMapsFailedJobToFailedResult() {
        when(jobFacade.get(11L)).thenReturn(new JobView(11L, "failed", null, "platform API unreachable", null));

        IntegrationActionResult result = handler.checkStatus(11L);

        assertEquals(IntegrationActionResult.FAILED, result.state());
        assertEquals("platform API unreachable", result.error());
    }

    @Test
    void checkStatusMapsMissingJobToFailedResult() {
        when(jobFacade.get(99L)).thenThrow(new RuntimeException("job 99 not found"));
        assertEquals(IntegrationActionResult.FAILED, handler.checkStatus(99L).state());
    }

    // ── isAvailableForScope — the security-relevant gate ────────────────────────

    @Test
    void availableForTheGenericBugHuntingType() {
        when(projectFacade.getProjectTypeCode(1L)).thenReturn(Optional.of("BH"));
        assertTrue(handler.isAvailableForScope("project", 1L));
    }

    @Test
    void availableForABugHuntingSubtype() {
        when(projectFacade.getProjectTypeCode(1L)).thenReturn(Optional.of("BH_INTG"));
        assertTrue(handler.isAvailableForScope("project", 1L));
    }

    /** Security regression test — a pentest/red-team/monitoring project must never see or be able
     *  to trigger a Bug Hunting sync just because it's project-scoped like this action is. */
    @Test
    void notAvailableForANonBugHuntingProjectType() {
        when(projectFacade.getProjectTypeCode(1L)).thenReturn(Optional.of("PENTEST"));
        assertFalse(handler.isAvailableForScope("project", 1L));
    }

    @Test
    void notAvailableForATypeCodeThatMerelyContainsBhButDoesNotStartWithIt() {
        when(projectFacade.getProjectTypeCode(1L)).thenReturn(Optional.of("SUB_BH"));
        assertFalse(handler.isAvailableForScope("project", 1L));
    }

    @Test
    void notAvailableAtNonProjectScope() {
        assertFalse(handler.isAvailableForScope("organization", 1L));
        assertFalse(handler.isAvailableForScope("platform", 0L));
    }

    @Test
    void notAvailableForANonexistentProject() {
        when(projectFacade.getProjectTypeCode(999L)).thenReturn(Optional.empty());
        assertFalse(handler.isAvailableForScope("project", 999L));
    }
}
