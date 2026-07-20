package com.mango.products.adapter.in.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "products.security.jwt")
public record JwtSecurityProperties(Resource publicKeyLocation, String issuer, String audience) {

    public JwtSecurityProperties {
        if (publicKeyLocation == null) {
            throw new IllegalArgumentException("JWT public key location is required");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer is required");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience is required");
        }
    }
}
