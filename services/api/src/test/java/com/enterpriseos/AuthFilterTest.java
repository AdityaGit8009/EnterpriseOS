package com.enterpriseos;

import java.util.List;
import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthFilterTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthFilter filter = new AuthFilter(jdbc, new ObjectMapper(), "http://localhost:5173");
    @Test void anonymousProtectedRequestIsDeniedBeforeController() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/org/users"), response, (r,s) -> fail("Controller must not execute"));
        assertEquals(401, response.getStatus()); verifyNoInteractions(jdbc);
    }
    @Test void missingOrForeignOriginCannotMutateEvenPublicLogin() throws Exception {
        for (String origin : List.of("", "https://attacker.example")) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            if (!origin.isEmpty()) request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (r,s) -> fail("Controller must not execute"));
            assertEquals(403, response.getStatus());
        }
        verifyNoInteractions(jdbc);
    }
    @Test void orgSessionCannotEnterPlatformAndPlatformCannotEnterOrg() throws Exception {
        var member = new Principal("u", "t", "Member", "member@example.test", "MEMBER");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Principal>>any(), anyString())).thenReturn(List.of(member));
        var request = new MockHttpServletRequest("GET", "/api/v1/platform/tenants"); request.setCookies(new Cookie("EOS_SESSION", "opaque"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r,s) -> fail("Controller must not execute"));
        assertEquals(403, response.getStatus());
        var platform = new Principal("p", null, "Platform", "platform@example.test", "PLATFORM_ADMIN");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Principal>>any(), anyString())).thenReturn(List.of(platform));
        request = new MockHttpServletRequest("GET", "/api/v1/org/users"); request.setCookies(new Cookie("EOS_SESSION", "opaque"));
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r,s) -> fail("Controller must not execute"));
        assertEquals(403, response.getStatus());
    }
    @Test void revokedOrInactiveDatabaseContextIsRejected() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Principal>>any(), anyString())).thenReturn(List.of());
        var request = new MockHttpServletRequest("GET", "/api/v1/me"); request.setCookies(new Cookie("EOS_SESSION", "opaque"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r,s) -> fail("Controller must not execute"));
        assertEquals(401, response.getStatus());
        verify(jdbc).query(contains("m.status='ACTIVE'"), org.mockito.ArgumentMatchers.<RowMapper<Principal>>any(), eq(Tokens.hash("opaque")));
    }
    @Test void rawSecretIsNotPersistedAsItsHash() {
        String token = Tokens.issue(); assertEquals(43, token.length());
        assertEquals(64, Tokens.hash(token).length()); assertNotEquals(token, Tokens.hash(token));
        assertNotEquals(token, Tokens.issue());
    }
}
