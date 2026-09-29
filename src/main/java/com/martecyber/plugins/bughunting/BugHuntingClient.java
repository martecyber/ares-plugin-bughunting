package com.martecyber.plugins.bughunting;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for Bug Hunting platform API clients — the extension point this plugin
 * declares via {@code providesExtensionPoints} in {@code plugin.json}, implemented directly by
 * {@link BugcrowdClient}/{@link YesWeHackClient} (bundled here) and by whatever platform-specific
 * plugin (ares-plugin-bughunting-hackerone, ares-plugin-bughunting-intigriti) depends on this one.
 */
public interface BugHuntingClient {

    /** Platform discriminator matching ProjectBugHuntingProgram.platform. */
    String platform();

    /** Human-readable display name — feeds {@code GET /api/v1/bug-hunting/platforms}, the only
     *  thing the frontend's "Bug Hunting Platforms" picker and project-scope source labels read
     *  to know which platforms exist and what to call them (see {@code BugHuntingProgramController
     *  #platforms}) — no platform name is ever hardcoded in ares-ui. Default just title-cases
     *  {@link #platform}; override for a proper brand name ("HackerOne", "Intigriti", ...). */
    default String label() {
        String p = platform();
        return p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    /** True for a platform whose API is a personal researcher token (shown in the "Bug Hunting
     *  Platforms" credential picker) — override to {@code false} for a company-facing platform
     *  (e.g. Bugcrowd, YesWeHack) whose credentials are set up under Data Sources instead, but
     *  which can still be picked as a project's Bug Hunting Program sync source. */
    default boolean researcherFacing() {
        return true;
    }

    /** Which fields the "New integration" credential form should show for this platform, in
     *  order — the dialog itself (ares-ui's VdpIntegrationFormDialog.vue) is generic and renders
     *  whatever this returns; a platform with unusual auth just declares a different field list,
     *  no frontend change needed. Default: a single API token field, matching every platform
     *  except HackerOne's username+token pair. */
    default List<CredentialField> credentialFields() {
        return List.of(new CredentialField("api_token", "API Token", "password", "API token", true));
    }

    /** Optional short instructions shown under the credential form (e.g. where to generate a
     *  token) — null/blank for no hint. */
    default String credentialHelpText() {
        return null;
    }

    /**
     * Fetches all in-scope and out-of-scope targets for the given programme.
     *
     * @param programHandle programme slug or ID on the platform
     * @param credentials   decrypted credential map (keys depend on platform)
     * @return normalised list of scope items ready for upsert
     */
    List<ScopeImportItem> fetchScope(String programHandle, Map<String, String> credentials) throws Exception;

    /**
     * Fetches the platform's rules-of-engagement testing requirements for the given programme,
     * if the platform exposes any (used to auto-populate the project's required_user_agent /
     * required_header rules). Default no-op for platforms without a known way to fetch this yet.
     *
     * @return a map with optional keys "userAgent" and "requestHeader" (the latter as a raw
     *         "Name: value" style string) — missing/absent keys mean that requirement isn't set.
     */
    default Map<String, String> fetchTestingRequirements(String programHandle, Map<String, String> credentials) throws Exception {
        return Map.of();
    }

}
