package com.martecyber.plugins.bughunting;

import com.martecyber.ares.plugins.PluginComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written {@code JdbcTemplate} repository over {@code ares.project_bug_hunting_program} — no
 * JPA/Hibernate (see {@link ProjectBugHuntingProgram}'s own doc for why). Registered as a {@link
 * PluginComponent} so {@link BugHuntingPluginLifecycle} can create the table before anything here
 * is ever queried.
 */
public class ProjectBugHuntingProgramRepository implements PluginComponent {

    private final JdbcTemplate jdbc;

    public ProjectBugHuntingProgramRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ProjectBugHuntingProgram> ROW_MAPPER = (rs, rowNum) -> {
        ProjectBugHuntingProgram p = new ProjectBugHuntingProgram();
        p.setProjectId(rs.getLong("project_id"));
        p.setPlatform(rs.getString("platform"));
        long integrationId = rs.getLong("integration_id");
        p.setIntegrationId(rs.wasNull() ? null : integrationId);
        p.setProgramHandle(rs.getString("program_handle"));
        p.setSyncEnabled(rs.getBoolean("sync_enabled"));
        p.setSyncIntervalHours(rs.getInt("sync_interval_hours"));
        p.setLastSyncAt(toOffsetDateTime(rs.getTimestamp("last_sync_at")));
        p.setLastSyncStatus(rs.getString("last_sync_status"));
        p.setLastSyncError(rs.getString("last_sync_error"));
        p.setCreatedAt(toOffsetDateTime(rs.getTimestamp("created_at")));
        p.setUpdatedAt(toOffsetDateTime(rs.getTimestamp("updated_at")));
        return p;
    };

    public Optional<ProjectBugHuntingProgram> findByProjectId(Long projectId) {
        List<ProjectBugHuntingProgram> rows = jdbc.query(
            "SELECT * FROM ares.project_bug_hunting_program WHERE project_id = ?", ROW_MAPPER, projectId);
        return rows.stream().findFirst();
    }

    public List<ProjectBugHuntingProgram> findAll() {
        return jdbc.query("SELECT * FROM ares.project_bug_hunting_program", ROW_MAPPER);
    }

    /** All programmes with sync enabled whose last sync is overdue (or never synced). */
    public List<ProjectBugHuntingProgram> findDueForSync(OffsetDateTime threshold) {
        return jdbc.query("""
            SELECT * FROM ares.project_bug_hunting_program
            WHERE sync_enabled = true
              AND platform <> 'generic'
              AND integration_id IS NOT NULL
              AND program_handle IS NOT NULL
              AND (last_sync_at IS NULL OR last_sync_at < ?)
            """, ROW_MAPPER, Timestamp.from(threshold.toInstant()));
    }

    public ProjectBugHuntingProgram save(ProjectBugHuntingProgram p) {
        jdbc.update("""
            INSERT INTO ares.project_bug_hunting_program
                (project_id, platform, integration_id, program_handle, sync_enabled, sync_interval_hours,
                 last_sync_at, last_sync_status, last_sync_error, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id) DO UPDATE SET
                platform = EXCLUDED.platform,
                integration_id = EXCLUDED.integration_id,
                program_handle = EXCLUDED.program_handle,
                sync_enabled = EXCLUDED.sync_enabled,
                sync_interval_hours = EXCLUDED.sync_interval_hours,
                last_sync_at = EXCLUDED.last_sync_at,
                last_sync_status = EXCLUDED.last_sync_status,
                last_sync_error = EXCLUDED.last_sync_error,
                updated_at = EXCLUDED.updated_at
            """,
            p.getProjectId(), p.getPlatform(), p.getIntegrationId(), p.getProgramHandle(),
            p.isSyncEnabled(), p.getSyncIntervalHours(),
            toTimestamp(p.getLastSyncAt()), p.getLastSyncStatus(), p.getLastSyncError(),
            toTimestamp(p.getCreatedAt()), toTimestamp(p.getUpdatedAt()));
        return p;
    }

    public void delete(ProjectBugHuntingProgram p) {
        jdbc.update("DELETE FROM ares.project_bug_hunting_program WHERE project_id = ?", p.getProjectId());
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime dt) {
        return dt == null ? null : Timestamp.from(dt.toInstant());
    }
}
