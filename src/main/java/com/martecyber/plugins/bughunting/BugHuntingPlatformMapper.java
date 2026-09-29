package com.martecyber.plugins.bughunting;

/**
 * Maps platform-native target types and asset identifiers to Ares scope entry kinds, for the two
 * platforms bundled directly in this plugin. HackerOne/Intigriti have their own small mapping
 * methods inlined in their own plugins instead of a shared class — not worth a shared library for
 * ~15 lines each.
 *
 * Ares scope kinds:
 *   domain          — single domain, e.g. "example.com"
 *   domain_wildcard — domain wildcard, e.g. "*.example.com"
 *   url             — specific URL, e.g. "https://example.com/api"
 *   url_wildcard    — URL with wildcard path, e.g. "https://example.com/*"
 *   ip              — single IP address, e.g. "1.2.3.4"
 *   ip_wildcard     — IP with wildcard octet, e.g. "1.2.3.*"
 *   cidr            — IP range in CIDR notation, e.g. "10.0.0.0/8"
 *   other           — Free text; no asset validation
 *
 * ── Bugcrowd (target.target_type) ────────────────────────────────────────────
 *   website, api → url / url_wildcard
 *   source_code  → url
 *   mobile, hardware, other → other
 *
 * ── YesWeHack (scope.scope_type) ─────────────────────────────────────────────
 *   web-application, api → url / url_wildcard
 *   ip-address           → ip / ip_wildcard / cidr (by value format)
 *   mobile-application, other → other
 */
public final class BugHuntingPlatformMapper {

    private BugHuntingPlatformMapper() {}

    // ── Bugcrowd ──────────────────────────────────────────────────────────────

    public static String bugcrowdTargetTypeToKind(String type, String value) {
        if (type == null) return "other";
        return switch (type.toLowerCase()) {
            case "website", "api" -> isWildcard(value) ? "url_wildcard" : "url";
            case "source_code"    -> "url";
            default               -> "other"; // mobile, hardware, other
        };
    }

    // ── YesWeHack ─────────────────────────────────────────────────────────────

    public static String yesWeHackScopeTypeToKind(String scopeType, String value) {
        if (scopeType == null) return "other";
        return switch (scopeType.toLowerCase()) {
            case "web-application", "api" -> isWildcard(value) ? "url_wildcard" : "url";
            case "ip-address"             -> inferIpKind(value);
            default                       -> "other"; // mobile-application, other
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isWildcard(String value) {
        return value != null && value.contains("*");
    }

    private static String inferIpKind(String value) {
        if (value == null) return "ip";
        if (value.contains("/")) return "cidr";
        if (value.contains("*")) return "ip_wildcard";
        return "ip";
    }
}
