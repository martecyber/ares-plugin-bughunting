package com.martecyber.plugins.bughunting.dto;

import com.martecyber.plugins.bughunting.ProjectBugHuntingProgram;

import java.time.OffsetDateTime;

public record BugHuntingProgramDto(
    Long projectId,
    String platform,
    Long integrationId,
    String programHandle,
    boolean syncEnabled,
    int syncIntervalHours,
    OffsetDateTime lastSyncAt,
    String lastSyncStatus,
    String lastSyncError,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static BugHuntingProgramDto from(ProjectBugHuntingProgram p) {
        return new BugHuntingProgramDto(
            p.getProjectId(), p.getPlatform(), p.getIntegrationId(),
            p.getProgramHandle(), p.isSyncEnabled(), p.getSyncIntervalHours(),
            p.getLastSyncAt(), p.getLastSyncStatus(), p.getLastSyncError(),
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
