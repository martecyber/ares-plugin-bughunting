package com.martecyber.plugins.bughunting;

import com.martecyber.ares.plugins.PluginLifecycle;
import com.martecyber.ares.projects.ProjectTypeFacade;
import com.martecyber.ares.projects.ProjectTypeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates the {@code project_bug_hunting_program} table (raw SQL — see {@link
 * ProjectBugHuntingProgram}'s own doc for why not JPA) and (re-)enables the {@code BH} root
 * project type on install; backfills any pre-existing managed-workflow schedule that predates
 * this plugin existing (see {@link BugHuntingProgramService#backfillManagedWorkflows}).
 *
 * <p>{@link #onForget} only disables the project type again — it deliberately does NOT drop the
 * table, so an uninstall-then-reinstall (or a temporary uninstall while troubleshooting) never
 * loses a programme's sync history/configuration. A base {@code ares-core} instance that has never
 * had this plugin installed ships with the {@code BH} type already disabled (see core's own
 * {@code V198__bug_hunting_extracted_to_plugin.sql}), so this only matters for an instance that
 * previously had the plugin and is reinstalling it.
 */
public class BugHuntingPluginLifecycle implements PluginLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BugHuntingPluginLifecycle.class);

    private final ProjectTypeFacade projectTypeFacade;
    private final BugHuntingProgramService programService;

    public BugHuntingPluginLifecycle(ProjectTypeFacade projectTypeFacade, BugHuntingProgramService programService) {
        this.projectTypeFacade = projectTypeFacade;
        this.programService = programService;
    }

    @Override
    public void onInstall(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ares.project_bug_hunting_program (
                project_id           BIGINT       NOT NULL PRIMARY KEY
                                          REFERENCES ares.project(id) ON DELETE CASCADE,
                platform             VARCHAR(20)  NOT NULL,
                integration_id       BIGINT       REFERENCES ares.integration(id) ON DELETE SET NULL,
                program_handle       VARCHAR(255),
                sync_enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
                sync_interval_hours  INT          NOT NULL DEFAULT 24,
                last_sync_at         TIMESTAMPTZ,
                last_sync_status     VARCHAR(30),
                last_sync_error      TEXT,
                created_at           TIMESTAMPTZ  NOT NULL,
                updated_at           TIMESTAMPTZ  NOT NULL
            )
            """);

        projectTypeFacade.ensure(new ProjectTypeSpec("BH", "Bug Hunting", null, "bughunting", true, null));
        log.info("ares-plugin-bughunting: table ready, 'BH' project type enabled");

        int backfilled = programService.backfillManagedWorkflows();
        if (backfilled > 0) {
            log.info("ares-plugin-bughunting: backfilled {} managed workflow(s) for pre-existing programme(s)", backfilled);
        }
    }

    @Override
    public void onForget(JdbcTemplate jdbc) {
        projectTypeFacade.disable("BH");
        log.info("ares-plugin-bughunting: 'BH' project type disabled (table/data left in place)");
    }
}
