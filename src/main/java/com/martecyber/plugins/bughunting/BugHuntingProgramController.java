package com.martecyber.plugins.bughunting;

import com.martecyber.ares.plugins.PluginRestController;
import com.martecyber.plugins.bughunting.dto.BugHuntingProgramDto;
import com.martecyber.plugins.bughunting.dto.UpsertBugHuntingProgramRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authorization here is checked programmatically ({@link #requireRole}) rather than via
 * {@code @PreAuthorize} — Spring Security's method security needs a CGLIB self-proxy of this
 * class for the same reason {@code @Transactional} does on {@link BugHuntingProgramService} (see
 * that class's own doc comment for the full explanation): this class is plugin-loaded and
 * implements no interface, and the proxy factory backing {@code @PreAuthorize} is bound to the
 * core app's own classloader, fixed once at container startup, long before any plugin exists.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/bug-hunting-program")
public class BugHuntingProgramController implements PluginRestController {

    private final BugHuntingProgramService service;

    public BugHuntingProgramController(BugHuntingProgramService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<BugHuntingProgramDto> get(@PathVariable Long projectId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return service.find(projectId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping
    public BugHuntingProgramDto upsert(@PathVariable Long projectId,
                                       @RequestBody UpsertBugHuntingProgramRequest req,
                                       Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        return service.upsert(projectId, req);
    }

    @PostMapping("/sync")
    public Map<String, Long> triggerSync(@PathVariable Long projectId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN", "MSSP_OPERATOR");
        Long jobId = service.triggerSync(projectId);
        return Map.of("jobId", jobId);
    }

    @DeleteMapping
    public ResponseEntity<Void> disconnect(@PathVariable Long projectId, Authentication auth) {
        requireRole(auth, "MSSP_ADMIN");
        service.disconnect(projectId);
        return ResponseEntity.noContent().build();
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
}
