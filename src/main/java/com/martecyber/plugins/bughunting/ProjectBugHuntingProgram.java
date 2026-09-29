package com.martecyber.plugins.bughunting;

import java.time.OffsetDateTime;

/**
 * Plain POJO, not a JPA {@code @Entity} — Hibernate maps its entities once at boot, before any
 * plugin loads, so a plugin-owned table can't go through it. Persisted via {@link
 * ProjectBugHuntingProgramRepository} (hand-written {@code JdbcTemplate} SQL) against a table this
 * plugin creates itself in {@link BugHuntingPluginLifecycle#onInstall}.
 */
public class ProjectBugHuntingProgram {

    /** Same value as the project PK — one-to-one via shared PK. */
    private Long projectId;

    /** 'bugcrowd' | 'yeswehack' | 'intigriti' | 'hackerone' | 'generic' */
    private String platform;

    private Long integrationId;

    private String programHandle;

    private boolean syncEnabled = true;

    private int syncIntervalHours = 24;

    private OffsetDateTime lastSyncAt;

    /** 'success' | 'failed' | 'partial' | 'never' */
    private String lastSyncStatus;

    private String lastSyncError;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Long getIntegrationId() { return integrationId; }
    public void setIntegrationId(Long integrationId) { this.integrationId = integrationId; }

    public String getProgramHandle() { return programHandle; }
    public void setProgramHandle(String programHandle) { this.programHandle = programHandle; }

    public boolean isSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(boolean syncEnabled) { this.syncEnabled = syncEnabled; }

    public int getSyncIntervalHours() { return syncIntervalHours; }
    public void setSyncIntervalHours(int syncIntervalHours) { this.syncIntervalHours = syncIntervalHours; }

    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }

    public String getLastSyncError() { return lastSyncError; }
    public void setLastSyncError(String lastSyncError) { this.lastSyncError = lastSyncError; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
