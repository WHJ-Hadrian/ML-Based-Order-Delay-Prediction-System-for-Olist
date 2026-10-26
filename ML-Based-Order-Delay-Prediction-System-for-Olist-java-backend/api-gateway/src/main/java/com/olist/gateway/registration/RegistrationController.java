package com.olist.gateway.registration;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/registration-codes")
    public Mono<ResponseEntity<MessageResponse>> requestCode(
            @Valid @RequestBody CodeRequest request,
            ServerWebExchange exchange) {
        String address = exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : resolveClientAddress(exchange.getRequest().getRemoteAddress());
        return registrationService.requestCode(request.email(), address)
                .map(message -> ResponseEntity.accepted().body(new MessageResponse(message)));
    }

    @PostMapping("/registration-codes/verify")
    public Mono<VerificationResponse> verifyCode(@Valid @RequestBody VerifyCodeRequest request) {
        return registrationService.verifyCode(request.email(), request.code())
                .map(VerificationResponse::new);
    }

    @PostMapping("/registrations")
    public Mono<ResponseEntity<MessageResponse>> register(@Valid @RequestBody CreateAccountRequest request) {
        return registrationService.register(
                        request.verificationToken(),
                        request.displayName(),
                        request.password(),
                        request.birthday(),
                        request.gender())
                .thenReturn(ResponseEntity.status(HttpStatus.CREATED)
                        .body(new MessageResponse("账号已创建，请登录。")));
    }

    public record CodeRequest(
            @NotBlank @Email @Size(max = 254) String email) { }

    public record VerifyCodeRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) { }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 64) String verificationToken,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(min = 10, max = 128) String password,
            @Size(max = 10) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String birthday,
            @Pattern(regexp = "female|male|other|unspecified") String gender) { }

    public record VerificationResponse(String verificationToken) { }
    public record MessageResponse(String message) { }

    private String resolveClientAddress(SocketAddress remoteAddress) {
        if (remoteAddress instanceof InetSocketAddress inetAddress && inetAddress.getAddress() != null) {
            return inetAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
