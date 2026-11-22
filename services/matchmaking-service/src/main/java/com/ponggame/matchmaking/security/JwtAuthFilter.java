package com.ponggame.matchmaking.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final KmsPublicKeyProvider publicKeyProvider;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtAuthFilter(
            KmsPublicKeyProvider publicKeyProvider,
            @Value("${app.jwt.issuer:PongGame.Auth}") String expectedIssuer,
            @Value("${app.jwt.audience:PongGame}") String expectedAudience) {
        this.publicKeyProvider = publicKeyProvider;
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.equals("/api/matchmaking/status")
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            unauthorized(response, "Missing Bearer token");
            return;
        }

        String token = authHeader.substring(7).trim();
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                unauthorized(response, "Unsupported JWT algorithm");
                return;
            }

            if (!verifySignatureWithRefresh(jwt)) {
                unauthorized(response, "Invalid JWT signature");
                return;
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date now = new Date();
            if (!expectedIssuer.equals(claims.getIssuer())) {
                unauthorized(response, "Invalid JWT issuer");
                return;
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
                unauthorized(response, "Invalid JWT audience");
                return;
            }
            if (claims.getExpirationTime() == null || now.after(claims.getExpirationTime())) {
                unauthorized(response, "Expired JWT");
                return;
            }
            if (claims.getNotBeforeTime() != null && now.before(new Date(claims.getNotBeforeTime().getTime() - 5000))) {
                unauthorized(response, "JWT not yet valid");
                return;
            }

            UUID playerId = UUID.fromString(claims.getSubject());
            String username = claims.getStringClaim("preferred_username");
            if (username == null || username.isBlank()) {
                unauthorized(response, "Missing preferred_username claim");
                return;
            }

            request.setAttribute(AuthenticatedPlayer.REQUEST_ATTR, new AuthenticatedPlayer(playerId, username));
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            log.debug("JWT authentication failed: {}", ex.getMessage());
            unauthorized(response, "Invalid authentication token");
        }
    }

    private boolean verifySignatureWithRefresh(SignedJWT jwt) throws Exception {
        RSAPublicKey key = publicKeyProvider.getPublicKey();
        if (jwt.verify(new RSASSAVerifier(key))) {
            return true;
        }
        RSAPublicKey refreshed = publicKeyProvider.forceRefresh();
        return jwt.verify(new RSASSAVerifier(refreshed));
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
