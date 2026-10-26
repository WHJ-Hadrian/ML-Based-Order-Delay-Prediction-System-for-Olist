package com.olist.gateway.registration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class KeycloakRegistrationClient {

    private static final long TOKEN_REFRESH_SKEW_SECONDS = 30;
    private static final long MINIMUM_ACCESS_TOKEN_TTL_SECONDS = 60;
    private static final ParameterizedTypeReference<List<Map<String, Object>>> USER_LIST =
            new ParameterizedTypeReference<>() { };

    private final RegistrationProperties properties;
    private final WebClient webClient;
    private volatile String cachedAccessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private Mono<String> tokenRequest;

    public KeycloakRegistrationClient(RegistrationProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(trimTrailingSlash(properties.getKeycloakBaseUrl())).build();
    }

    public Mono<Boolean> userExists(String email) {
        return accessToken().flatMap(token -> webClient.get()
                .uri(uri -> uri.path("/admin/realms/{realm}/users")
                        .queryParam("email", email)
                        .queryParam("exact", true)
                        .queryParam("max", 1)
                        .build(properties.getKeycloakRealm()))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(USER_LIST)
                .map(users -> !users.isEmpty())
                .defaultIfEmpty(false)
                .onErrorMap(this::isUnavailable, error -> unavailable()));
    }

    public Mono<Void> createUser(String email, String displayName, String password) {
        return createUser(email, displayName, password, null, null);
    }

    public Mono<Void> createUser(
            String email,
            String displayName,
            String password,
            String birthday,
            String gender) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", email);
        user.put("email", email);
        user.put("firstName", displayName);
        user.put("lastName", "");
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("credentials", List.of(Map.of(
                "type", "password",
                "value", password,
                "temporary", false)));
        if (birthday != null || gender != null) {
            Map<String, List<String>> attributes = new LinkedHashMap<>();
            if (birthday != null) attributes.put("birthday", List.of(birthday));
            if (gender != null) attributes.put("gender", List.of(gender));
            user.put("attributes", attributes);
        }

        return accessToken().flatMap(token -> webClient.post()
                .uri("/admin/realms/{realm}/users", properties.getKeycloakRealm())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(user)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), response ->
                        Mono.error(new RegistrationException(HttpStatus.CONFLICT,
                                "该邮箱已注册，请返回登录。")))
                .toBodilessEntity()
                .then()
                .onErrorMap(this::isUnavailable, error -> unavailable()));
    }

    private Mono<String> accessToken() {
        if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(TOKEN_REFRESH_SKEW_SECONDS))) {
            return Mono.just(cachedAccessToken);
        }

        synchronized (this) {
            if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(TOKEN_REFRESH_SKEW_SECONDS))) {
                return Mono.just(cachedAccessToken);
            }
            if (tokenRequest != null) {
                return tokenRequest;
            }
            if (isBlank(properties.getKeycloakClientSecret())) {
                return Mono.error(unavailable());
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.getKeycloakClientId());
            form.add("client_secret", properties.getKeycloakClientSecret());

            tokenRequest = webClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.getKeycloakRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .map(response -> {
                        cachedAccessToken = response.accessToken();
                        tokenExpiresAt = Instant.now().plusSeconds(
                                Math.max(response.expiresIn(), MINIMUM_ACCESS_TOKEN_TTL_SECONDS));
                        return cachedAccessToken;
                    })
                    .onErrorMap(error -> unavailable())
                    .doFinally(signal -> {
                        synchronized (this) {
                            tokenRequest = null;
                        }
                    })
                    .cache();
            return tokenRequest;
        }
    }

    private boolean isUnavailable(Throwable error) {
        return !(error instanceof RegistrationException);
    }

    private RegistrationException unavailable() {
        return new RegistrationException(HttpStatus.SERVICE_UNAVAILABLE,
                "注册服务暂时不可用，请稍后重试。");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:8180";
        return value.replaceAll("/+$", "");
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) { }
}
