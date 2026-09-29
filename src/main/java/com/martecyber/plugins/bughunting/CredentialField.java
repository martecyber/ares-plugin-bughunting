package com.martecyber.plugins.bughunting;

/**
 * One field of a platform's credential form — drives the generic "New integration" dialog in
 * ares-ui (see {@link BugHuntingClient#credentialFields}) instead of the frontend hardcoding
 * per-platform field layouts. A platform with unusual auth (HackerOne's username+token pair)
 * just declares an extra field; the dialog itself never changes.
 */
public record CredentialField(
    /** Key stored in the integration's encrypted credentials map (e.g. "api_token"). */
    String key,
    String label,
    /** "text" or "password" — anything else falls back to a plain text input. */
    String inputType,
    String placeholder,
    boolean required
) {}
