package com.olist.gateway.registration;

import java.time.Duration;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class RegistrationEmailSender {

    private final JavaMailSender mailSender;
    private final RegistrationProperties properties;

    public RegistrationEmailSender(JavaMailSender mailSender, RegistrationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendCode(String email, String code, Duration codeTtl) {
        if (properties.getMailFrom() == null || properties.getMailFrom().isBlank()) {
            throw new IllegalStateException("Registration email sender is not configured.");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMailFrom());
        message.setTo(email);
        message.setSubject("SeekDateSet 注册验证码");
        message.setText("你的验证码是：" + code + "\n\n验证码有效期为 "
                + describeDuration(codeTtl) + "。请勿将验证码提供给他人。");
        mailSender.send(message);
    }

    private String describeDuration(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes > 0 ? minutes + " 分钟" : duration.toSeconds() + " 秒";
    }
}
