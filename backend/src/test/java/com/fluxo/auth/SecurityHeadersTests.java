package com.fluxo.auth;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** documentation/SECURITY-ARCHITECTURE.md: browser-side defences on every response. */
class SecurityHeadersTests extends IntegrationTest {

    @Test
    void responsesCarryCspAndFramingProtection() {
        var r = get(admin(), "/api/me").getResponse();
        assertThat(r.getHeader("Content-Security-Policy")).contains("script-src 'self'", "frame-ancestors 'none'", "object-src 'none'");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("same-origin");
        assertThat(r.getHeader("Permissions-Policy")).contains("camera=()");
    }
}
