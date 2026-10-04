package com.seatbook.auth;


import com.seatbook.error.ErrorCode;
import com.seatbook.dto.responses.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final TokenAuthenticator tokenAuthenticator;

    private final ObjectMapper objectMapper;

    public AuthFilter(TokenAuthenticator tokenAuthenticator, ObjectMapper objectMapper) {
        this.tokenAuthenticator = tokenAuthenticator;
        this.objectMapper = objectMapper;
    }

    private static final Set<String> PUBLIC_PATHS = Set.of("/healthz", "/readyz", "/metrics");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        String authToken = request.getHeader("Authorization");
        if (authToken == null || !authToken.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            createErrorResponse(response);
            return;
        }

        Optional<AuthenticationUser> authenticationUser = tokenAuthenticator.authenticate(authToken.substring(7));
        if (authenticationUser.isEmpty()) {
            createErrorResponse(response);
            return;
        }
        request.setAttribute("authUser", authenticationUser.get());

        filterChain.doFilter(request, response);
    }

    private void createErrorResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse payload = new ErrorResponse(ErrorCode.UNAUTHORIZED.getValue(), "Invalid Token");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
        response.getWriter().flush();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getServletPath());
    }
}
