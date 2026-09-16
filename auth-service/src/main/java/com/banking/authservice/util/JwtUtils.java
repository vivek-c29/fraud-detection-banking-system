package com.banking.authservice.util;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtUtils {

    private final KeyPairProvider keyPairProvider;

    @Value("${jwt.expiration-ms:86400000}") // 24 hours default
    private long jwtExpirationMs;

    @Value("${jwt.issuer:banking-auth-service}")
    private String jwtIssuer;

    public String generateToken(String userId, String email, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .issuer(jwtIssuer)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(keyPairProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }
}
