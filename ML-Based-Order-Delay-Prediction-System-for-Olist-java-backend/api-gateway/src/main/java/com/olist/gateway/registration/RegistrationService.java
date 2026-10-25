package com.olist.gateway.registration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class RegistrationService {

    private static final RedisScript<Long> VERIFY_CODE_SCRIPT = new DefaultRedisScript<>("""
            local saved = redis.call('GET', KEYS[1])
            if not saved then return -1 end
            local attempts = redis.call('INCR', KEYS[2])
            if attempts == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            if attempts > tonumber(ARGV[3]) then
              redis.call('DEL', KEYS[1])
              return -2
            end
            if saved == ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final String GENERIC_CODE_MESSAGE = "如果该邮箱可以注册，验证码已发送。";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_SPACE_SIZE = 1_000_000;
    private static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MIN_AGE = 13;
    private static final Set<String> GENDERS = Set.of("female", "male", "other", "unspecified");

    private final ReactiveStringRedisTemplate redis;
    private final RegistrationProperties properties;
    private final KeycloakRegistrationClient keycloak;
    private final RegistrationEmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    public RegistrationService(
            ReactiveStringRedisTemplate redis,
            RegistrationProperties properties,
            KeycloakRegistrationClient keycloak,
            RegistrationEmailSender emailSender) {
        this.redis = redis;
        this.properties = properties;
        this.keycloak = keycloak;
        this.emailSender = emailSender;
    }

    public Mono<String> requestCode(String rawEmail, String clientAddress) {
        String email = normalizeEmail(rawEmail);
        String emailHash = sha256(email);
        String cooldownKey = "registration:cooldown:" + emailHash;

        return requireConfiguration()
                .then(incrementIpCounter(clientAddress))
                .then(redis.opsForValue().setIfAbsent(cooldownKey, "1", properties.getResendDelay()))
                .flatMap(acquired -> {
                    if (!Boolean.TRUE.equals(acquired)) {
                        return Mono.error(new RegistrationException(HttpStatus.TOO_MANY_REQUESTS,
                                "请稍后再试。"));
                    }
                    return keycloak.userExists(email)
                            .flatMap(exists -> exists ? Mono.empty() : issueCode(email, emailHash, cooldownKey));
                })
                .thenReturn(GENERIC_CODE_MESSAGE)
                .onErrorResume(error -> clearFailedRequest(error, emailHash, cooldownKey));
    }

    public Mono<AccountProfile> lookupAccount(String rawEmail) {
        return keycloak.findUserByEmail(normalizeEmail(rawEmail));
    }

    public Mono<String> verifyCode(String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        String emailHash = sha256(email);
        String codeKey = codeKey(emailHash);
        String attemptsKey = attemptsKey(emailHash);
        String candidateHash = hmac(email + ":" + code);

        return requireConfiguration()
                .then(redis.execute(
                                VERIFY_CODE_SCRIPT,
                                List.of(codeKey, attemptsKey),
                                candidateHash,
                                Long.toString(properties.getCodeTtl().toSeconds()),
                                Integer.toString(properties.getMaxCodeAttempts()))
                        .next())
                .flatMap(result -> {
                    if (result != 1L) {
                        return Mono.error(new RegistrationException(HttpStatus.BAD_REQUEST,
                                "验证码无效或已过期，请重新获取。"));
                    }
                    String token = UUID.randomUUID().toString();
                    return redis.opsForValue()
                            .set(verifiedKey(token), email, properties.getVerifiedTokenTtl())
                            .thenReturn(token);
                });
    }

    public Mono<Void> register(String verificationToken, String rawDisplayName, String password) {
        return register(verificationToken, rawDisplayName, password, null, null);
    }

    public Mono<Void> register(
            String verificationToken,
            String rawDisplayName,
            String password,
            String rawBirthday,
            String rawGender) {
        String displayName = rawDisplayName == null ? "" : rawDisplayName.trim();
        if (displayName.isBlank() || displayName.length() > 80) {
            return Mono.error(new RegistrationException(HttpStatus.BAD_REQUEST,
                    "姓名长度需为 1 到 80 个字符。"));
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > 128) {
            return Mono.error(new RegistrationException(HttpStatus.BAD_REQUEST,
                    "密码长度需为 10 到 128 个字符。"));
        }
        if (verificationToken == null || verificationToken.isBlank() || verificationToken.length() > 64) {
            return Mono.error(invalidVerification());
        }
        String birthday = normalizeBirthday(rawBirthday);
        String gender = normalizeGender(rawGender);

        return requireConfiguration()
                .then(redis.opsForValue().get(verifiedKey(verificationToken)))
                .switchIfEmpty(Mono.error(invalidVerification()))
                .flatMap(email -> keycloak.createUser(email, displayName, password, birthday, gender)
                        .then(redis.delete(verifiedKey(verificationToken)))
                        .then());
    }

    private Mono<Void> issueCode(String email, String emailHash, String cooldownKey) {
        String code = String.format(Locale.ROOT, "%0" + CODE_LENGTH + "d", random.nextInt(CODE_SPACE_SIZE));
        String codeKey = codeKey(emailHash);
        return redis.opsForValue()
                .set(codeKey, hmac(email + ":" + code), properties.getCodeTtl())
                .then(Mono.fromRunnable(() -> emailSender.sendCode(email, code, properties.getCodeTtl()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(error -> redis.delete(codeKey, cooldownKey)
                        .then(Mono.error(new RegistrationException(HttpStatus.SERVICE_UNAVAILABLE,
                                "验证码邮件暂时无法发送，请稍后重试。"))))
                .then();
    }

    private Mono<Void> incrementIpCounter(String clientAddress) {
        String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        String key = "registration:ip:" + sha256(address);
        return redis.opsForValue().increment(key)
                .flatMap(count -> (count == 1
                        ? redis.expire(key, properties.getIpWindow()).thenReturn(count)
                        : Mono.just(count)))
                .flatMap(count -> count > properties.getMaxCodesPerIpWindow()
                        ? Mono.error(new RegistrationException(HttpStatus.TOO_MANY_REQUESTS,
                                "验证码请求过于频繁，请稍后重试。"))
                        : Mono.empty());
    }

    private <T> Mono<T> clearFailedRequest(Throwable error, String emailHash, String cooldownKey) {
        if (error instanceof RegistrationException exception
                && exception.getStatus().is4xxClientError()) {
            return Mono.error(error);
        }
        return redis.delete(codeKey(emailHash), cooldownKey)
                .onErrorReturn(0L)
                .then(Mono.error(error instanceof RegistrationException
                        ? error
                        : new RegistrationException(HttpStatus.SERVICE_UNAVAILABLE,
                                "注册服务暂时不可用，请稍后重试。")));
    }

    private Mono<Void> requireConfiguration() {
        if (properties.getOtpHmacSecret() == null || properties.getOtpHmacSecret().length() < 32
                || properties.getKeycloakClientSecret() == null || properties.getKeycloakClientSecret().isBlank()) {
            return Mono.error(new RegistrationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "注册服务尚未配置，请联系管理员。"));
        }
        return Mono.empty();
    }

    private RegistrationException invalidVerification() {
        return new RegistrationException(HttpStatus.BAD_REQUEST,
                "邮箱验证已过期，请重新获取验证码。" );
    }

    private String normalizeBirthday(String rawBirthday) {
        if (rawBirthday == null || rawBirthday.isBlank()) return null;
        try {
            LocalDate birthday = LocalDate.parse(rawBirthday.trim());
            LocalDate today = LocalDate.now();
            if (birthday.isAfter(today)) {
                throw new RegistrationException(HttpStatus.BAD_REQUEST, "出生日期不能晚于今天。");
            }
            if (birthday.isAfter(today.minusYears(MIN_AGE))) {
                throw new RegistrationException(HttpStatus.BAD_REQUEST, "您必须年满 13 岁才能创建账号。");
            }
            return birthday.toString();
        } catch (DateTimeException error) {
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "请输入有效的出生日期。");
        }
    }

    private String normalizeGender(String rawGender) {
        if (rawGender == null || rawGender.isBlank()) return null;
        String gender = rawGender.trim().toLowerCase(Locale.ROOT);
        if (!GENDERS.contains(gender)) {
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "请选择有效的性别。");
        }
        return gender;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String codeKey(String emailHash) { return "registration:code:" + emailHash; }
    private String attemptsKey(String emailHash) { return "registration:attempts:" + emailHash; }
    private String verifiedKey(String token) { return "registration:verified:" + token; }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash registration key.", error);
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getOtpHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to protect registration code.", error);
        }
    }
}
