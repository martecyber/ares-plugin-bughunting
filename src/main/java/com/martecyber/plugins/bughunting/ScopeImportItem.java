package com.martecyber.plugins.bughunting;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Normalised scope item returned by each platform client.
 * Maps to an ProjectScopeEntry after import.
 */
public record ScopeImportItem(
    /** Ares scope kind: domain, domain_wildcard, url, url_wildcard, ip, ip_wildcard, cidr, other. */
    String kind,
    /** The target value (e.g. "*.example.com", "10.0.0.0/8"). */
    String value,
    /** Platform-native identifier — used as upsert key. */
    String externalId,
    /** Optional notes from the platform (e.g. bounty eligibility, instructions). */
    String notes,
    /** Whether this target is in scope on the platform. False = out of scope. */
    boolean inScope,
    /** When this scope entry was first created on the platform. Null if unavailable. */
    OffsetDateTime platformCreatedAt,
    /** When this scope entry was last updated on the platform. Null if unavailable. */
    OffsetDateTime platformUpdatedAt,
    /** Extra metadata stored as JSONB. May be null. */
    Map<String, Object> metadata
) {}
