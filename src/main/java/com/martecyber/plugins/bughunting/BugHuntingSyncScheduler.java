package com.martecyber.plugins.bughunting;

import com.martecyber.ares.jobs.JobFacade;
import com.martecyber.ares.plugins.PluginComponent;
import com.martecyber.ares.projects.ProjectFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Periodic scheduler that enqueues BH_SYNC_SCOPE jobs for Bug Hunting programmes
 * whose scope sync is overdue.
 *
 * Runs every hour; each programme controls its own interval via sync_interval_hours.
 * The query in ProjectBugHuntingProgramRepository.findDueForSync computes the
 * threshold as NOW() - min(sync_interval_hours) — in practice the scheduler checks
 * each programme individually after the query returns the overdue list.
 *
 * <p>Effectively superseded by the Workflows-Phase-H+ {@code TRIGGER_CRON} mechanism (see {@link
 * BugHuntingProgramService#syncManagedWorkflow}) but left in place — see that class's own doc
 * comment for why draining it isn't needed.
 */
public class BugHuntingSyncScheduler implements PluginComponent {

    private static final Logger log = LoggerFactory.getLogger(BugHuntingSyncScheduler.class);

    private final ProjectBugHuntingProgramRepository programRepo;
    private final ProjectFacade projectFacade;
    private final JobFacade jobFacade;
    private final BugHuntingSyncJobHandler syncJobHandler;

    public BugHuntingSyncScheduler(ProjectBugHuntingProgramRepository programRepo,
                                   ProjectFacade projectFacade,
                                   JobFacade jobFacade,
                                   BugHuntingSyncJobHandler syncJobHandler) {
        this.programRepo = programRepo;
        this.projectFacade = projectFacade;
        this.jobFacade = jobFacade;
        this.syncJobHandler = syncJobHandler;
    }

    /** Runs at the top of every hour (e.g. 00:00, 01:00, …). */
    @Scheduled(cron = "0 0 * * * *")
    public void syncOverdueProgrammes() {
        // Use a conservative threshold of 1 hour; the query filters by each
        // programme's own sync_interval_hours so programmes with 24h interval
        // won't be selected until their time is actually up.
        OffsetDateTime threshold = OffsetDateTime.now().minusHours(1);
        List<ProjectBugHuntingProgram> due = programRepo.findDueForSync(threshold);
        if (due.isEmpty()) return;

        log.info("BH sync scheduler: {} programme(s) due for scope refresh", due.size());
        for (ProjectBugHuntingProgram program : due) {
            // Re-check per-programme interval to avoid syncing too early
            if (isActuallyDue(program)) {
                enqueueSync(program);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isActuallyDue(ProjectBugHuntingProgram program) {
        if (program.getLastSyncAt() == null) return true;
        OffsetDateTime nextDue = program.getLastSyncAt().plusHours(program.getSyncIntervalHours());
        return OffsetDateTime.now().isAfter(nextDue);
    }

    private void enqueueSync(ProjectBugHuntingProgram program) {
        try {
            Long orgId = projectFacade.getOrganizationId(program.getProjectId());
            var job = jobFacade.create("BH_SYNC_SCOPE", orgId, null, null);
            syncJobHandler.syncScopeAsync(job.id(), program.getProjectId());
            log.debug("BH sync scheduled job={} project={} platform={}",
                job.id(), program.getProjectId(), program.getPlatform());
        } catch (Exception ex) {
            log.error("Failed to enqueue BH sync for project {}: {}",
                program.getProjectId(), ex.getMessage());
        }
    }
}
