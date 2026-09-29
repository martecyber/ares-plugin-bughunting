package com.martecyber.plugins.bughunting;

import com.martecyber.ares.plugins.PluginRestController;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The single source of truth ares-ui reads to know which Bug Hunting platforms currently exist —
 * every platform currently registered in {@link BugHuntingClientRegistry}, including ones
 * contributed by a dependent plugin (ares-plugin-bughunting-hackerone/-intigriti) this base
 * plugin never named directly. No platform name/id is ever hardcoded in the frontend for this;
 * see {@code ares-ui/src/api/vdp-integrations.ts}'s {@code loadBugHuntingPlatforms}.
 *
 * <p>Role check is programmatic ({@link #requireRole}), not {@code @PreAuthorize} — same reason
 * as {@link BugHuntingProgramController}'s own doc comment. Gated the same as the app's other
 * "what integration types exist" endpoint ({@code GET /integrations/types}): any MSSP_ADMIN or
 * MSSP_OPERATOR, not just admins — this is read-only catalog data, not a mutation.
 */
@RestController
@RequestMapping("/api/v1/bug-hunting")
public class BugHuntingPlatformController implements PluginRestController {

    private final BugHuntingClientRegistry registry;

    public BugHuntingPlatformController(BugHuntingClientRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/platforms")
    public List<PlatformDto> platforms(Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return registry.clients().stream()
            .map(c -> new PlatformDto(c.platform(), c.label(), c.researcherFacing(),
                c.credentialFields(), c.credentialHelpText()))
            .toList();
    }

    private static void requireRole(Authentication auth, String... anyOfRoles) {
        boolean allowed = auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> {
                for (String role : anyOfRoles) {
                    if (("ROLE_" + role).equals(a.getAuthority())) return true;
                }
                return false;
            });
        if (!allowed) {
            throw new AccessDeniedException("Requires role: " + String.join(" or ", anyOfRoles));
        }
    }

    public record PlatformDto(String id, String label, boolean researcherFacing,
                               List<CredentialField> credentialFields, String credentialHelpText) {}
}
