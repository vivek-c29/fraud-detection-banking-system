package com.banking.authservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

@Component
@Slf4j
public class KeyPairProvider {

    private final KeyPair keyPair;

    public KeyPairProvider() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            this.keyPair = keyPairGenerator.generateKeyPair();
            log.info("RSA 2048-bit KeyPair successfully generated for Auth Service");
        } catch (Exception e) {
            log.error("Failed to generate RSA KeyPair", e);
            throw new RuntimeException("Could not initialize RSA KeyPair", e);
        }
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public String getPublicKeyPem() {
        String base64PublicKey = Base64.getEncoder().encodeToString(getPublicKey().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" +
                base64PublicKey.replaceAll("(.{64})", "$1\n") +
                "\n-----END PUBLIC KEY-----";
    }
}
