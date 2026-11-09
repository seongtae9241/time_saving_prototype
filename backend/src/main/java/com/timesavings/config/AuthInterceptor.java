package com.timesavings.config;

import com.timesavings.domain.Enums.Role;
import com.timesavings.domain.User;
import com.timesavings.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Authorization: Bearer 토큰 검사 + 경로별 역할 검사. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR = "currentUser";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    static boolean isPublic(String method, String path) {
        if ("OPTIONS".equals(method)) return true;
        if (path.startsWith("/api/auth/") && !path.equals("/api/auth/logout")) return true;
        if (path.equals("/api/orgs/apply")) return true;
        if (path.startsWith("/api/notices")) return true;
        return "GET".equals(method) && path.startsWith("/api/files/");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String path = req.getRequestURI();
        String h = req.getHeader("Authorization");
        User u = null;
        if (h != null && h.startsWith("Bearer ")) u = auth.userByToken(h.substring(7).trim());
        if (u != null) req.setAttribute(ATTR, u);
        if (isPublic(req.getMethod(), path)) return true;
        if (u == null) throw ApiException.unauthorized("로그인이 필요해요");
        if (path.startsWith("/api/admin/") && u.role != Role.ADMIN) throw ApiException.forbidden("운영자만 쓸 수 있어요");
        if (path.startsWith("/api/org/") && u.role != Role.ORG) throw ApiException.forbidden("기관 담당자만 쓸 수 있어요");
        return true;
    }
}
