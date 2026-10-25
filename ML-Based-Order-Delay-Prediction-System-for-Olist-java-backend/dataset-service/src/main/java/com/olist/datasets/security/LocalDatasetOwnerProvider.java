package com.olist.datasets.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "mysql", "test"})
public class LocalDatasetOwnerProvider implements DatasetOwnerProvider {

    private static final String LEGACY_OWNER_ID = "legacy";

    @Override
    public String currentOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return LEGACY_OWNER_ID;
    }
}
