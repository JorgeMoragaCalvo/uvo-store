package org.uvo.uvostore.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.uvo.uvostore.config.RequiredSecret;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret:}") String secret, @Value("${jwt.expiration-ms}") long expirationMs) {
        // Validated here, before Keys.hmacShaKeyFor: on a short secret jjwt raises a WeakKeyException
        // that names neither the property nor the env var behind it. 32 bytes is HS256's minimum.
        RequiredSecret.require(secret, "JWT_SECRET");
        RequiredSecret.rejectLegacyDefault(secret, RequiredSecret.LEGACY_JWT_SECRET, "JWT_SECRET");
        RequiredSecret.minBytes(secret, 32, "JWT_SECRET");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // `tv` is the principal's token version at issue time (A5). JwtAuthenticationFilter compares it
    // against the current one, so bumping the version invalidates every token already out there
    // without keeping any server-side session state.
    public String generateToken(Long principalId, String subject, String principalType, Long storeId,
                                List<String> authorities, int tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(subject)
                .claim("uid", principalId)
                .claim("type", principalType)
                .claim("sid", storeId)
                .claim("authorities", authorities)
                .claim("tv", tokenVersion)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
