package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.query.WorkspaceRolePolicy;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthRouteNormalizationTest {
    @Test void matrix_parameters_cannot_bypass_workspace_authorization() {
        for (var entry : Map.of("/api/errands;x=1", "/api/errands",
                "/api/errands;x=1/42/grab", "/api/errands/{errandId}/grab").entrySet()) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, entry.getValue());
            var request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (p, m, a) -> switch (m.getName()) {
                case "getHeader" -> "Authorization".equals(a[0]) ? "Bearer test" : null;
                case "getAttribute" -> attributes.get(a[0]);
                case "setAttribute" -> { attributes.put((String)a[0], a[1]); yield null; }
                case "getMethod" -> entry.getValue().endsWith("grab") ? "POST" : "GET";
                case "getRequestURI" -> entry.getKey();
                case "getContextPath" -> "";
                case "getParameter" -> null;
                default -> null;
            });
            var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (p,m,a) -> null);
            AuthPort auth = new AuthPort() {
                public String login(long id) { return "test"; }
                public Optional<Long> resolve(String token) { return Optional.of(1001L); }
                public void logout(String token) {}
            };
            var interceptor = new AuthInterceptor(auth, new WorkspaceAccess(new WorkspaceRolePolicy(9001, "1001")), false);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                    () -> interceptor.preHandle(request, response, new Object())).code());
        }
    }
}
