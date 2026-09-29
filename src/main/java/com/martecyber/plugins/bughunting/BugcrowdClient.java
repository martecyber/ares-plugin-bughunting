package com.martecyber.plugins.bughunting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.tools.IntegrationClient;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bugcrowd API client (2024-02-12 "Security Program Management" JSON:API model).
 *
 * Auth: Authorization: Token <username>:<api_token>   (colon-joined pair — both are required;
 *       Bugcrowd issues a token tied to a specific account, not a bare secret on its own)
 *       Bugcrowd-Version: 2024-02-12
 *
 * Credentials map:
 *   api_username — Bugcrowd account email
 *   api_token    — Bugcrowd API token (Profile menu → API Credentials)
 *
 * Scope model: as of the SPM platform update, scope is NOT a flat "/programs/{code}/targets"
 * list — it's reached via a JSON:API relationship chain, with related resources returned in a
 * top-level "included" array (cross-referenced by "type"+"id", not nested inline):
 *
 *   Program --engagements--> Engagement --engagement_brief--> EngagementBrief
 *     --engagement_brief_target_groups--> TargetGroup{name, description, in_scope}
 *       --targets--> Target{name, category}
 *
 * "in_scope" lives on the TargetGroup, not the individual Target — every target in an
 * out-of-scope group is imported with inScope=false.
 *
 * Programme handle (as entered in Ares) = the program's "code" (the slug in its Bugcrowd URL).
 * Resolved to its JSON:API id by listing GET /programs and matching client-side — Bugcrowd's
 * filter query-param name for looking up a program by code isn't publicly documented, so this
 * avoids depending on unconfirmed filter syntax (the accessible-programs list is small).
 *
 * Ref: https://docs.bugcrowd.com/api/getting-started/
 *      https://bugcrowd.com/openapi/2024-02-12/openapi.yml
 */
@Component
public class BugcrowdClient implements BugHuntingClient, IntegrationClient {

    private static final Logger log = LoggerFactory.getLogger(BugcrowdClient.class);
    private static final String BASE_URL    = "https://api.bugcrowd.com";
    private static final String API_VERSION = "2024-02-12";
    private static final String INCLUDE_CHAIN =
        "engagements.engagement_brief.engagement_brief_target_groups.targets";

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final BugHuntingClientRegistry registry;

    public BugcrowdClient(ObjectMapper objectMapper, BugHuntingClientRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // This plugin's own bundled implementations of its own declared extension point aren't
    // auto-discovered by PluginLoader (only a DEPENDENT plugin's implementations are — see
    // BugHuntingClientRegistry's own doc) — self-register instead.
    @PostConstruct
    void register() { registry.register(this); }

    @Override public String platform() { return "bugcrowd"; }
    @Override public String label() { return "Bugcrowd"; }
    // Bugcrowd's public API is company/program-owner-facing, not an individual researcher
    // account — credentials for it are set up under Data Sources, not the researcher-token
    // "Bug Hunting Platforms" picker (see BugHuntingClient#researcherFacing's own doc).
    @Override public boolean researcherFacing() { return false; }
    @Override public String supports()  { return "bugcrowd"; }

    @Override
    public void testConnection(String settingsJson, Map<String, String> credentials) throws Exception {
        get("/programs?page[limit]=1", credentials);
    }

    @Override
    public List<ScopeImportItem> fetchScope(String programHandle, Map<String, String> credentials)
            throws Exception {
        String programId = resolveProgramId(programHandle, credentials);
        if (programId == null) {
            throw new PlatformApiException("Bugcrowd", 404,
                "{\"error\":\"Program '" + programHandle + "' not found or not accessible with these credentials.\"}");
        }

        JsonNode root = get("/programs/" + programId + "?include=" + INCLUDE_CHAIN, credentials);
        Map<String, JsonNode> included = indexIncluded(root.path("included"));
        JsonNode program = root.path("data");

        List<ScopeImportItem> items = new ArrayList<>();
        for (JsonNode engRef : relationshipRefs(program, "engagements")) {
            JsonNode engagement = resolve(included, engRef);
            if (engagement == null) continue;
            JsonNode brief = resolve(included, relationshipRef(engagement, "engagement_brief"));
            if (brief == null) continue;

            for (JsonNode groupRef : relationshipRefs(brief, "engagement_brief_target_groups")) {
                JsonNode group = resolve(included, groupRef);
                if (group == null) continue;
                JsonNode groupAttrs = group.path("attributes");
                boolean inScope  = groupAttrs.path("in_scope").asBoolean(true);
                String  groupNote = groupAttrs.path("description").asText(null);

                for (JsonNode targetRef : relationshipRefs(group, "targets")) {
                    JsonNode target = resolve(included, targetRef);
                    if (target == null) continue;
                    JsonNode attrs = target.path("attributes");
                    String value = attrs.path("name").asText("").trim();
                    if (value.isEmpty()) continue;

                    String category   = attrs.path("category").asText(null);
                    String kind       = BugHuntingPlatformMapper.bugcrowdTargetTypeToKind(category, value);
                    String externalId = target.path("id").asText(null);
                    items.add(new ScopeImportItem(kind, value, externalId, groupNote, inScope, null, null, null));
                }
            }
        }
        log.debug("Bugcrowd fetchScope programme={} items={}", programHandle, items.size());
        return items;
    }

    private String resolveProgramId(String programHandle, Map<String, String> credentials) throws Exception {
        JsonNode root = get("/programs?page[limit]=100", credentials);
        for (JsonNode p : root.path("data")) {
            String code = p.path("attributes").path("code").asText("");
            if (code.equalsIgnoreCase(programHandle)) return p.path("id").asText(null);
        }
        return null;
    }

    // ── JSON:API helpers ────────────────────────────────────────────────────────

    private static Map<String, JsonNode> indexIncluded(JsonNode includedArr) {
        Map<String, JsonNode> map = new HashMap<>();
        if (includedArr.isArray()) {
            for (JsonNode n : includedArr) {
                map.put(n.path("type").asText("") + ":" + n.path("id").asText(""), n);
            }
        }
        return map;
    }

    private static JsonNode relationshipRef(JsonNode resource, String relName) {
        JsonNode data = resource.path("relationships").path(relName).path("data");
        return data.isObject() ? data : null;
    }

    private static List<JsonNode> relationshipRefs(JsonNode resource, String relName) {
        JsonNode data = resource.path("relationships").path(relName).path("data");
        List<JsonNode> refs = new ArrayList<>();
        if (data.isArray()) data.forEach(refs::add);
        return refs;
    }

    private static JsonNode resolve(Map<String, JsonNode> included, JsonNode ref) {
        if (ref == null) return null;
        return included.get(ref.path("type").asText("") + ":" + ref.path("id").asText(""));
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private JsonNode get(String path, Map<String, String> credentials) throws Exception {
        String username = credentials.getOrDefault("api_username", "");
        String token    = credentials.getOrDefault("api_token", "");
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Token " + username + ":" + token)
            .header("Bugcrowd-Version", API_VERSION)
            .header("Accept", "application/vnd.bugcrowd+json")
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new PlatformApiException("Bugcrowd", resp.statusCode(), resp.body());
        return objectMapper.readTree(resp.body());
    }
}
