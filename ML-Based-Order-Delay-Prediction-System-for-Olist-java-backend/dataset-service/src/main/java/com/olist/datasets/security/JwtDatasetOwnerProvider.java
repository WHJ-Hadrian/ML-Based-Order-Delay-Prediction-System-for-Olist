package com.olist.datasets.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !mysql & !test")
public class JwtDatasetOwnerProvider implements DatasetOwnerProvider {

    @Override
    public String currentOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String subject = jwtAuthentication.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        throw new AccessDeniedException("有效的用户身份令牌是必需的");
    }
}
