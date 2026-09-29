package com.martecyber.plugins.bughunting;

/**
 * Thrown when a Bug Hunting platform API returns a non-2xx HTTP response.
 * Stack trace is suppressed for 4xx errors — those are configuration issues
 * (wrong handle, bad credentials) and don't need a stack trace in the logs.
 */
public class PlatformApiException extends RuntimeException {

    private final int statusCode;

    public PlatformApiException(String platform, int statusCode, String responseBody) {
        super(buildMessage(platform, statusCode, responseBody),
              null,
              true,
              statusCode >= 500); // only fill stack trace for server errors
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }

    /** True for 4xx — user configuration issue (wrong handle, bad credentials, etc.). */
    public boolean isClientError() { return statusCode >= 400 && statusCode < 500; }

    private static String buildMessage(String platform, int status, String body) {
        String detail = extractTitle(body);
        return platform + " API " + status + (detail.isEmpty() ? "" : " — " + detail);
    }

    /** Extracts the "title" or "message" field from a JSON error body without a full parser. */
    private static String extractTitle(String body) {
        if (body == null || body.isBlank()) return "";
        for (String key : new String[]{"\"title\":", "\"message\":", "\"error\":"}) {
            int idx = body.indexOf(key);
            if (idx >= 0) {
                int start = body.indexOf('"', idx + key.length()) + 1;
                int end   = body.indexOf('"', start);
                if (start > 0 && end > start) {
                    return body.substring(start, Math.min(end, start + 120));
                }
            }
        }
        return body.substring(0, Math.min(body.length(), 120));
    }
}
