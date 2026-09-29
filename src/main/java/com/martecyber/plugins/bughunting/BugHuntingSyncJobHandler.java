package com.martecyber.plugins.bughunting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.IntegrationFacade;
import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.ares.projects.rules.ProjectRulesFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Handles BH_SYNC_SCOPE jobs: fetches programme scope from the external platform
 * and upserts it into project_scope_entry.
 *
 * Triggered by:
 *   - BugHuntingProgramController POST /{projectId}/bug-hunting-program/sync  (manual)
 *   - BugHuntingSyncScheduler (periodic, every hour, respects sync_interval_hours)
 */
public class BugHuntingSyncJobHandler implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(BugHuntingSyncJobHandler.class);

    private final JobFacade jobFacade;
    private final IntegrationFacade integrationFacade;
    private final BugHuntingProgramService programService;
    private final ProjectBugHuntingProgramRepository programRepo;
    private final BugHuntingClientRegistry clientRegistry;
    private final ObjectMapper objectMapper;
    private final ProjectRulesFacade projectRulesFacade;
    private final Executor executor;

    public BugHuntingSyncJobHandler(JobFacade jobFacade,
                                    IntegrationFacade integrationFacade,
                                    BugHuntingProgramService programService,
                                    ProjectBugHuntingProgramRepository programRepo,
                                    BugHuntingClientRegistry clientRegistry,
                                    ObjectMapper objectMapper,
                                    ProjectRulesFacade projectRulesFacade,
                                    @Qualifier("applicationTaskExecutor") Executor executor) {
        this.jobFacade = jobFacade;
        this.integrationFacade = integrationFacade;
        this.programService = programService;
        this.programRepo = programRepo;
        this.clientRegistry = clientRegistry;
        this.objectMapper = objectMapper;
        this.projectRulesFacade = projectRulesFacade;
        this.executor = executor;
    }

    // ── Async execution ───────────────────────────────────────────────────────

    // Submits to the app's own auto-configured Executor bean explicitly, rather than @Async —
    // @Async needs a CGLIB self-proxy of this class the same way @Transactional does on
    // BugHuntingProgramService (see that class's own doc for the full explanation); this class
    // has the same problem (it's plugin-loaded, implements no interface), so the same fix applies:
    // no proxy of the calling class needed when the async dispatch is explicit instead of
    // annotation-driven.
    public void syncScopeAsync(Long jobId, Long projectId) {
        executor.execute(() -> syncScopeNow(jobId, projectId));
    }

    private void syncScopeNow(Long jobId, Long projectId) {
        setStatus(jobId, "running", 0);
        ProjectBugHuntingProgram program = programRepo.findByProjectId(projectId).orElse(null);
        if (program == null) {
            safeFailJob(jobId, "Bug hunting programme not found for project " + projectId);
            return;
        }
        try {
            BugHuntingClient client = resolveClient(program.getPlatform());
            Map<String, String> creds = integrationFacade.loadCredentials(program.getIntegrationId());
            setStatus(jobId, null, 20);

            log.info("BH scope sync start job={} project={} platform={} programme={}",
                jobId, projectId, program.getPlatform(), program.getProgramHandle());

            List<ScopeImportItem> items = client.fetchScope(program.getProgramHandle(), creds);
            setStatus(jobId, null, 60);

            // Upsert each item (both in-scope and out-of-scope)
            for (ScopeImportItem item : items) {
                programService.upsertScopeEntry(
                    projectId, program.getPlatform(), item.externalId(),
                    item.kind(), item.value(), item.notes(), item.inScope(),
                    item.platformCreatedAt(), item.platformUpdatedAt(),
                    item.metadata() != null ? objectMapper.writeValueAsString(item.metadata()) : null
                );
            }
            setStatus(jobId, null, 85);

            // Remove entries that no longer appear in the platform response
            Set<String> activeIds = items.stream()
                .map(ScopeImportItem::externalId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
            programService.deleteObsoleteScopeEntries(projectId, program.getPlatform(), activeIds);

            // Sync rules-of-engagement testing requirements (User-Agent / request header) into
            // the project's own rules — non-fatal: the scope sync above already succeeded, and
            // not every platform exposes this (default no-op on the interface).
            try {
                Map<String, String> testingReqs = client.fetchTestingRequirements(program.getProgramHandle(), creds);
                projectRulesFacade.syncPlatformTestingRequirements(projectId, program.getPlatform(), testingReqs);
            } catch (Exception e) {
                log.warn("BH testing-requirements sync failed job={} project={} platform={}: {}",
                    jobId, projectId, program.getPlatform(), e.getMessage());
            }

            // Update programme sync metadata
            program.setLastSyncAt(OffsetDateTime.now());
            program.setLastSyncStatus("success");
            program.setLastSyncError(null);
            program.setUpdatedAt(OffsetDateTime.now());
            programRepo.save(program);

            completeJob(jobId, Map.of(
                "projectId", projectId,
                "platform",     program.getPlatform(),
                "itemsSynced",  items.size()
            ));
            log.info("BH scope sync done job={} project={} items={}", jobId, projectId, items.size());

        } catch (PlatformApiException apiEx) {
            // 4xx = configuration issue (wrong handle, bad credentials) — WARN, no stack trace
            String msg = apiEx.getMessage();
            if (apiEx.isClientError()) {
                log.warn("BH scope sync config error job={} project={}: {}", jobId, projectId, msg);
            } else {
                log.error("BH scope sync API error job={} project={}: {}", jobId, projectId, msg);
            }
            program.setLastSyncStatus("failed");
            program.setLastSyncError(msg);
            program.setUpdatedAt(OffsetDateTime.now());
            programRepo.save(program);
            safeFailJob(jobId, msg);
        } catch (Exception ex) {
            log.error("BH scope sync unexpected error job={} project={}", jobId, projectId, ex);
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            program.setLastSyncStatus("failed");
            program.setLastSyncError(msg);
            program.setUpdatedAt(OffsetDateTime.now());
            programRepo.save(program);
            safeFailJob(jobId, msg);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BugHuntingClient resolveClient(String platform) {
        return clientRegistry.forPlatform(platform)
            .orElseThrow(() -> new IllegalArgumentException("No BH client for platform: " + platform));
    }

    private void setStatus(Long jobId, String status, Integer progress) {
        try { jobFacade.update(jobId, status, progress, null, null); }
        catch (Exception e) { log.warn("job status update failed: {}", e.getMessage()); }
    }

    private void completeJob(Long jobId, Map<String, Object> resultData) {
        try {
            String json = objectMapper.writeValueAsString(resultData);
            jobFacade.update(jobId, "completed", 100, json, null);
        } catch (Exception e) { log.warn("Could not complete job {}: {}", jobId, e.getMessage()); }
    }

    private void safeFailJob(Long jobId, String error) {
        try { jobFacade.update(jobId, "failed", null, null, error); }
        catch (Exception ignored) {}
    }
}
