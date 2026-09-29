package com.martecyber.plugins.bughunting;

import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.jobs.JobView;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires {@link BugHuntingProgramService#triggerSync} into {@code ACTION_INTEGRATION_CALL} — the
 * same one-shot sync the project's own "Sync now" button and the legacy hourly
 * {@link BugHuntingSyncScheduler} both already trigger, just reachable from a workflow too.
 * {@code triggerSync} already returns a job id (the {@code BH_SYNC_SCOPE} job type), so
 * {@link #checkStatus} just reads it directly via {@link JobFacade}, mirroring {@code
 * KbSyncIntegrationActionHandler}.
 *
 * <p>{@link #isAvailableForScope} is the one thing that makes this handler different from every
 * other one registered so far: a bug-hunting program is only meaningful for a project whose own
 * type actually IS a bug-hunting type ({@code BH}/{@code BH_*} — see {@link
 * ProjectBugHuntingProgram}/{@code resolvePlatform}). A pentest/red-team/monitoring project has no
 * program to sync at all, so this action must never even be offered there — checked both at save
 * time and run time (see {@code IntegrationActionHandler#isAvailableForScope}'s own doc comment
 * for why both), mirroring the frontend's own {@code isBugHunting()} helper (api/projects.ts) so
 * the two never drift.
 */
public class BugHuntingIntegrationActionHandler implements IntegrationActionHandler {

    // Bug Hunting has no "configured instance" concept distinct from the project itself (one
    // program per project, 1:1) — mirrors KbSyncIntegrationActionHandler's own single synthetic
    // instance for the same reason.
    private static final IntegrationInstanceDescriptor SINGLE_INSTANCE = new IntegrationInstanceDescriptor(0L, "Bug Hunting program");

    private final BugHuntingProgramService programService;
    private final ProjectFacade projectFacade;
    private final JobFacade jobFacade;

    public BugHuntingIntegrationActionHandler(BugHuntingProgramService programService, ProjectFacade projectFacade,
                                               JobFacade jobFacade) {
        this.programService = programService;
        this.projectFacade = projectFacade;
        this.jobFacade = jobFacade;
    }

    @Override
    public String integrationType() { return "bug-hunting"; }

    @Override
    public String integrationTypeLabel() { return "Bug Hunting Program"; }

    @Override
    public boolean isDataSourceIntegration() { return false; }

    @Override
    public Set<String> supportedScopes() { return Set.of("project"); }

    @Override
    public boolean isAvailableForScope(String scopeKind, Long scopeId) {
        if (!"project".equals(scopeKind) || scopeId == null) return false;
        return projectFacade.getProjectTypeCode(scopeId)
            .map(BugHuntingIntegrationActionHandler::isBugHuntingCode)
            .orElse(false);
    }

    private static boolean isBugHuntingCode(String code) {
        return "BH".equals(code) || (code != null && code.startsWith("BH_"));
    }

    @Override
    public List<IntegrationActionDescriptor> describeActions() {
        return List.of(new IntegrationActionDescriptor("sync_scope", "Sync program scope"));
    }

    @Override
    public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) {
        return List.of(SINGLE_INSTANCE);
    }

    @Override
    public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) {
        if (!"sync_scope".equals(actionCode)) {
            throw new IllegalArgumentException("Unknown Bug Hunting action: " + actionCode);
        }
        return programService.triggerSync(scopeId);
    }

    @Override
    public IntegrationActionResult checkStatus(Long refId) {
        JobView job;
        try {
            job = jobFacade.get(refId);
        } catch (Exception e) {
            return new IntegrationActionResult(IntegrationActionResult.FAILED, null, "Bug Hunting sync job " + refId + " no longer exists");
        }
        return switch (job.status()) {
            case "completed" -> new IntegrationActionResult(IntegrationActionResult.COMPLETED, job.result() != null ? job.result() : "{}", null);
            case "failed", "cancelled" -> new IntegrationActionResult(IntegrationActionResult.FAILED, null,
                job.error() != null ? job.error() : "Bug Hunting sync job " + job.status());
            default -> new IntegrationActionResult(IntegrationActionResult.RUNNING, null, null);
        };
    }
}
