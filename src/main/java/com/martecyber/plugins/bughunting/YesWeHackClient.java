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
import java.util.List;
import java.util.Map;

/**
 * YesWeHack API client for programme scope retrieval.
 *
 * Auth: X-Auth-Token: <personal_access_token>
 *   NOT "Authorization: Bearer" — that's for the OAuth2/session-login flow used by YesWeHack's
 *   own web app and third-party apps registered via apps.yeswehack.com. A Personal Access
 *   Token (generated under Account > API tokens) is authenticated with this custom header
 *   instead — confirmed via YesWeHack's own PAT help article example:
 *     curl -X GET 'https://api.yeswehack.com/reports/48036' -H 'X-Auth-Token: <PAT>'
 *
 * Credentials map:
 *   api_token — YesWeHack Personal Access Token
 *
 * Scope endpoint:
 *   GET https://api.yeswehack.com/programs/{slug}
 *   Response includes: { scopes: [ { scope, scope_type, scope_type_name, asset_value, report_count } ] }
 *
 * Out-of-scope: YesWeHack doesn't expose a separate out-of-scope list via this endpoint —
 * anything present in "scopes" is in-scope by definition, so every entry is imported as such.
 *
 * Ref: https://helpcenter.yeswehack.io/en/articles/380967-api-personal-access-tokens
 *      https://apps.yeswehack.com/doc
 */
@Component
public class YesWeHackClient implements BugHuntingClient, IntegrationClient {

    private static final Logger log = LoggerFactory.getLogger(YesWeHackClient.class);
    private static final String BASE_URL = "https://api.yeswehack.com";

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final BugHuntingClientRegistry registry;

    public YesWeHackClient(ObjectMapper objectMapper, BugHuntingClientRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @PostConstruct
    void register() { registry.register(this); }

    @Override public String platform() { return "yeswehack"; }
    @Override public String label() { return "YesWeHack"; }
    // Same rationale as BugcrowdClient#researcherFacing — company-facing API, not a personal
    // researcher token.
    @Override public boolean researcherFacing() { return false; }
    @Override public String supports()  { return "yeswehack"; }

    @Override
    public void testConnection(String settingsJson, Map<String, String> credentials) throws Exception {
        get("/programs?page=1&nb_items_per_page=1", credentials);
    }

    @Override
    public List<ScopeImportItem> fetchScope(String programHandle, Map<String, String> credentials)
            throws Exception {
        JsonNode root = get("/programs/" + programHandle, credentials);
        List<ScopeImportItem> items = new ArrayList<>();
        JsonNode scopes = root.path("scopes");
        if (scopes.isArray()) {
            for (int i = 0; i < scopes.size(); i++) {
                JsonNode s = scopes.get(i);
                String value    = s.path("scope").asText("").trim();
                String type     = s.path("scope_type").asText(null);
                String notes    = s.path("scope_type_name").asText(null);
                boolean inScope = true; // see class doc — no out-of-scope list exists on this endpoint
                if (value.isEmpty()) continue;

                String kind = BugHuntingPlatformMapper.yesWeHackScopeTypeToKind(type, value);
                String externalId = programHandle + "::" + value;
                items.add(new ScopeImportItem(kind, value, externalId, notes, inScope, null, null, null));
            }
        }
        log.debug("YesWeHack fetchScope programme={} items={}", programHandle, items.size());
        return items;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private JsonNode get(String path, Map<String, String> credentials) throws Exception {
        String token = credentials.getOrDefault("api_token", "");
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("X-Auth-Token", token)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new PlatformApiException("YesWeHack", resp.statusCode(), resp.body());
        return objectMapper.readTree(resp.body());
    }
}
