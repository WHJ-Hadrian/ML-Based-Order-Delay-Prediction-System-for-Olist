package com.olist.gateway.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

class RegistrationServiceTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final RegistrationProperties properties = mock(RegistrationProperties.class);
    private final KeycloakRegistrationClient keycloak = mock(KeycloakRegistrationClient.class);
    private final RegistrationEmailSender emailSender = mock(RegistrationEmailSender.class);
    private final RegistrationService service = new RegistrationService(redis, properties, keycloak, emailSender);

    @Test
    void rejectsBlankDisplayNameBeforeAccessingDependencies() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.register("verified-token", "  ", "long-enough-password").block());
        assertBadRequest(error);

        verifyNoInteractions(redis, properties, keycloak, emailSender);
    }

    @Test
    void rejectsShortPasswordBeforeAccessingDependencies() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.register("verified-token", "SeekDateSet User", "short").block());
        assertBadRequest(error);

        verifyNoInteractions(redis, properties, keycloak, emailSender);
    }

    @Test
    void rejectsInvalidBirthdayBeforeAccessingDependencies() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.register(
                        "verified-token",
                        "SeekDateSet User",
                        "long-enough-password",
                        "not-a-date",
                        "female").block());
        assertBadRequest(error);

        verifyNoInteractions(redis, properties, keycloak, emailSender);
    }

    @Test
    void looksUpExistingAccountProfile() {
        AccountProfile profile = new AccountProfile(
                "user-id", "user@example.com", "SeekDateSet User", "https://example.com/avatar.png");
        when(keycloak.findUserByEmail("user@example.com")).thenReturn(Mono.just(profile));

        assertEquals(profile, service.lookupAccount("User@Example.com").block());
    }

    @Test
    void returnsEmptyLookupForUnknownAccount() {
        when(keycloak.findUserByEmail("missing@example.com")).thenReturn(Mono.empty());

        assertNull(service.lookupAccount("missing@example.com").block());
    }

    private void assertBadRequest(Throwable error) {
        RegistrationException exception = assertInstanceOf(RegistrationException.class, error);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }
}
