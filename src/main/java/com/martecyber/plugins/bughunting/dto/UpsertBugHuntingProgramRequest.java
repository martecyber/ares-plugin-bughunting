package com.martecyber.plugins.bughunting.dto;

public record UpsertBugHuntingProgramRequest(
    Long integrationId,
    String programHandle,
    Boolean syncEnabled,
    Integer syncIntervalHours
) {}
