package com.martecyber.plugins.bughunting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.ares.projects.ProjectFacade;
import com.martecyber.ares.projects.ScopeFacade;
import com.martecyber.ares.projects.dto.ScopeEntryDto;
import com.martecyber.plugins.bughunting.dto.BugHuntingProgramDto;
import com.martecyber.plugins.bughunting.dto.UpsertBugHuntingProgramRequest;
import com.martecyber.ares.workflows.WorkflowFacade;
import com.martecyber.ares.workflows.WorkflowScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BugHuntingProgramService implements PluginComponent {

    private final ProjectBugHuntingProgramRepository programRepo;
    private final ProjectFacade projectFacade;
    private final ScopeFacade scopeFacade;
    private final JobFacade jobFacade;
    private final ObjectMapper objectMapper;

    // ObjectProvider (not a plain @Autowired @Lazy field) to break the circular dependency:
    // BugHuntingProgramService <-> BugHuntingSyncJobHandler. A @Lazy field builds an eager CGLIB
    // proxy subclassing the declared field type at injection time — for a field typed to a
    // plugin-loaded class (BugHuntingSyncJobHandler lives only in this plugin's own
    // PluginClassLoader), Spring generates that proxy using the APP's classloader, which can't
    // even see the class, let alone subclass it: fails with "ClassNotFoundException:
    // BugHuntingSyncJobHandler$$SpringCGLIB$$0" the moment this bean is created. ObjectProvider
    // needs no proxy at all — getObject() just performs a live lookup when actually called,
    // by which point (this plugin's own PluginComponent load order — repo, this class, then
    // BugHuntingSyncJobHandler, then BugHuntingSyncScheduler) the target bean already exists.
    private final ObjectProvider<BugHuntingSyncJobHandler> syncJobHandlerProvider;

    // Same fix for a second cycle introduced by Workflows Phase H+: BugHuntingProgramService ->
    // WorkflowFacade's core-side impl -> ... -> IntegrationActionRegistry (collects every
    // IntegrationActionHandler bean) -> BugHuntingIntegrationActionHandler ->
    // BugHuntingProgramService (its own constructor dependency, to call triggerSync).
    // WorkflowFacade's own impl is a core class (visible to the app classloader), so @Lazy would
    // actually have worked here — kept as ObjectProvider anyway for the same reason as above:
    // this bean is always instantiated by PluginLoader.createBean(), never by a normal component
    // scan, so nothing here should depend on CGLIB being able to proxy whatever the field type
    // happens to be.
    private final ObjectProvider<WorkflowFacade> workflowFacadeProvider;

    // Programmatic (TransactionTemplate), not @Transactional — for the same underlying reason as
    // the ObjectProvider fields above, but hitting a DIFFERENT Spring mechanism: declarative
    // @Transactional needs a CGLIB self-proxy of THIS class (it implements no interface), and
    // that proxy is built by AbstractAutoProxyCreator using a classloader it cached ONCE, at
    // application startup (via BeanClassLoaderAware), from the core app's own bean factory —
    // permanently fixed before any plugin ever loads, and never re-derived per proxy. It can't
    // see a plugin-only class then any more than the app's classloader itself can, so it fails
    // the same way @Lazy did: `ClassNotFoundException: BugHuntingProgramService$$SpringCGLIB$$0`.
    // TransactionTemplate needs no proxy of the calling class at all — it manages the transaction
    // around an explicit callback instead. Facade calls made from inside one of these callbacks
    // still join it (their own @Transactional impls default to REQUIRED propagation), so
    // multi-step operations spanning this plugin's own table + a facade call stay one atomic unit.
    private final TransactionTemplate transactionTemplate;

    /** {@code syncIntervalHours} maps onto {@code TRIGGER_CRON} (Workflows Phase H+), so it's
     *  restricted to values that convert to a clean, evenly-spaced cron — see {@link #hoursToCron}. */
    private static final Set<Integer> ALLOWED_SYNC_INTERVAL_HOURS = Set.of(1, 2, 3, 4, 6, 8, 12, 24);

    public BugHuntingProgramService(ProjectBugHuntingProgramRepository programRepo,
                                    ProjectFacade projectFacade,
                                    ScopeFacade scopeFacade,
                                    JobFacade jobFacade,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<BugHuntingSyncJobHandler> syncJobHandlerProvider,
                                    ObjectProvider<WorkflowFacade> workflowFacadeProvider,
                                    PlatformTransactionManager transactionManager) {
        this.programRepo = programRepo;
        this.projectFacade = projectFacade;
        this.scopeFacade = scopeFacade;
        this.jobFacade = jobFacade;
        this.objectMapper = objectMapper;
        this.syncJobHandlerProvider = syncJobHandlerProvider;
        this.workflowFacadeProvider = workflowFacadeProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    public Optional<BugHuntingProgramDto> find(Long projectId) {
        return programRepo.findByProjectId(projectId).map(BugHuntingProgramDto::from);
    }

    // ── Upsert ────────────────────────────────────────────────────────────────

    public BugHuntingProgramDto upsert(Long projectId, UpsertBugHuntingProgramRequest req) {
        return transactionTemplate.execute(status -> {
            if (!projectFacade.exists(projectId)) throw NotFoundException.of("project", projectId);

            String platform = resolvePlatform(projectId);

            ProjectBugHuntingProgram program = programRepo.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectBugHuntingProgram p = new ProjectBugHuntingProgram();
                    p.setProjectId(projectId);
                    p.setPlatform(platform);
                    p.setCreatedAt(OffsetDateTime.now());
                    return p;
                });

            if (req.integrationId() != null) program.setIntegrationId(req.integrationId());
            if (req.programHandle() != null) program.setProgramHandle(req.programHandle());
            if (req.syncEnabled() != null) program.setSyncEnabled(req.syncEnabled());
            if (req.syncIntervalHours() != null) {
                if (!ALLOWED_SYNC_INTERVAL_HOURS.contains(req.syncIntervalHours())) {
                    throw new IllegalArgumentException(
                        "syncIntervalHours must be one of " + ALLOWED_SYNC_INTERVAL_HOURS);
                }
                program.setSyncIntervalHours(req.syncIntervalHours());
            }
            program.setUpdatedAt(OffsetDateTime.now());

            ProjectBugHuntingProgram saved = programRepo.save(program);
            syncManagedWorkflow(saved);
            return BugHuntingProgramDto.from(saved);
        });
    }

    /** Workflows Phase H+: keeps a locked, project-scoped Workflow (managedBy {@code
     *  "bug-hunting"}) in sync with this program's own sync_enabled/sync_interval_hours — actually
     *  fired by {@code WorkflowCronPoller} + {@code ACTION_INTEGRATION_CALL} through {@link
     *  BugHuntingIntegrationActionHandler}, not by the legacy hourly {@link BugHuntingSyncScheduler}
     *  poller (left in place but effectively drained going forward — no new program ever sets a
     *  state that poller's own due-check can act on differently than the workflow already does). A
     *  "generic" platform or a not-yet-fully-configured program (missing integration/program
     *  handle) has nothing meaningful to schedule, so no workflow is created for either case. */
    private void syncManagedWorkflow(ProjectBugHuntingProgram program) {
        if ("generic".equals(program.getPlatform())
            || program.getIntegrationId() == null || program.getProgramHandle() == null) {
            return;
        }
        Map<String, Object> graph = Map.of(
            "nodes", List.of(
                Map.of("id", "trigger", "type", "TRIGGER_CRON", "position", Map.of("x", 0, "y", 0),
                    "data", Map.of("label", "Schedule", "config", Map.of("cronExpression", hoursToCron(program.getSyncIntervalHours())))),
                Map.of("id", "sync", "type", "ACTION_INTEGRATION_CALL", "position", Map.of("x", 0, "y", 150),
                    "data", Map.of("label", "Sync program scope", "config", Map.of(
                        "integrationType", "bug-hunting", "integrationId", 0, "action", "sync_scope")))),
            "edges", List.of(Map.of("id", "e1", "source", "trigger", "target", "sync")));

        WorkflowFacade workflowFacade = workflowFacadeProvider.getObject();
        Long workflowId = workflowFacade.createOrReplace("bug-hunting", WorkflowScope.PROJECT, program.getProjectId(),
            "Bug Hunting sync schedule", null, graph);
        workflowFacade.setEnabled(workflowId, program.isSyncEnabled());
    }

    /** Backfills a managed Workflow for every {@code sync_enabled} program that predates this
     *  feature and hasn't been re-saved since (a fresh {@link #upsert} already does this on every
     *  call — this only covers the gap for rows nobody has touched). Called once from {@link
     *  BugHuntingPluginLifecycle#onInstall} rather than on every boot (unlike when this lived in
     *  ares-core's own startup migration pass) — {@link #syncManagedWorkflow} is itself an upsert
     *  keyed on {@code (managedBy, scope)}, so calling it again for an already-migrated program is
     *  a safe no-op regardless of how many times install/enable runs. */
    public int backfillManagedWorkflows() {
        return transactionTemplate.execute(status -> {
            List<ProjectBugHuntingProgram> enabled = programRepo.findAll().stream()
                .filter(ProjectBugHuntingProgram::isSyncEnabled)
                .toList();
            enabled.forEach(this::syncManagedWorkflow);
            return enabled.size();
        });
    }

    /** Every hour value in {@link #ALLOWED_SYNC_INTERVAL_HOURS} evenly divides 24, so an
     *  "every N hours" step cron lands on the same wall-clock hours every day — 24 is the one
     *  exception (a step of 24 isn't expressible, the hour field only goes up to 23), so it's
     *  spelled out as "once daily at midnight" instead. */
    private static String hoursToCron(int hours) {
        return hours == 24 ? "0 0 * * *" : "0 */" + hours + " * * *";
    }

    // ── Trigger sync ──────────────────────────────────────────────────────────

    public Long triggerSync(Long projectId) {
        ProjectBugHuntingProgram program = programRepo.findByProjectId(projectId)
            .orElseThrow(() -> NotFoundException.of("bug_hunting_program", projectId));

        if ("generic".equals(program.getPlatform()))
            throw new IllegalStateException("Generic Bug Hunting programmes have no API to sync from");
        if (program.getIntegrationId() == null || program.getProgramHandle() == null)
            throw new IllegalStateException("Programme is not fully configured (missing integration or program handle)");

        Long orgId = projectFacade.getOrganizationId(projectId);

        String payload = buildPayload(projectId, program.getIntegrationId(), program.getPlatform(),
            program.getProgramHandle(), orgId);
        var job = jobFacade.create("BH_SYNC_SCOPE", orgId, null, payload);
        syncJobHandlerProvider.getObject().syncScopeAsync(job.id(), projectId);
        return job.id();
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    public void disconnect(Long projectId) {
        transactionTemplate.executeWithoutResult(status -> {
            programRepo.findByProjectId(projectId).ifPresent(p -> {
                // Remove all imported scope entries for this project
                scopeFacade.deleteAllExceptSource(projectId, "manual");
                programRepo.delete(p);
            });
            WorkflowFacade workflowFacade = workflowFacadeProvider.getObject();
            workflowFacade.find("bug-hunting", WorkflowScope.PROJECT, projectId)
                .ifPresent(workflowFacade::delete);
        });
    }

    // ── Upsert scope entries (called from sync handler) ───────────────────────

    public ScopeEntryDto upsertScopeEntry(Long projectId, String source, String externalId,
                                          String kind, String value, String notes, boolean inScope,
                                          OffsetDateTime platformCreatedAt, OffsetDateTime platformUpdatedAt,
                                          String metadata) {
        return scopeFacade.upsert(projectId, source, externalId, kind, value, notes, inScope,
            platformCreatedAt, platformUpdatedAt, metadata);
    }

    public void deleteObsoleteScopeEntries(Long projectId, String source,
                                           Set<String> activeExternalIds) {
        scopeFacade.deleteObsolete(projectId, source, activeExternalIds);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Derives the platform string from the project's type code. */
    private String resolvePlatform(Long projectId) {
        return projectFacade.getProjectTypeCode(projectId)
            .map(code -> switch (code) {
                case "BH_BC"   -> "bugcrowd";
                case "BH_YWH"  -> "yeswehack";
                case "BH_INTG" -> "intigriti";
                case "BH_H1"   -> "hackerone";
                default        -> "generic";
            })
            .orElse("generic");
    }

    private String buildPayload(Long projectId, Long integrationId, String platform,
                                String programHandle, Long orgId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "projectId",   projectId,
                "integrationId",  integrationId,
                "platform",       platform,
                "programHandle",  programHandle,
                "orgId",          orgId != null ? orgId : 0
            ));
        } catch (Exception e) { return "{}"; }
    }
}
