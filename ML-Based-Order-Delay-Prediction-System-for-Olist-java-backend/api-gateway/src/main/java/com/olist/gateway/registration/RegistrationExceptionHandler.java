package com.olist.gateway.registration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice(assignableTypes = RegistrationController.class)
public class RegistrationExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    ResponseEntity<RegistrationController.MessageResponse> handleRegistration(RegistrationException error) {
        return ResponseEntity.status(error.getStatus())
                .body(new RegistrationController.MessageResponse(error.getMessage()));
    }

    @ExceptionHandler({WebExchangeBindException.class, ServerWebInputException.class})
    ResponseEntity<RegistrationController.MessageResponse> handleInvalidRequest(Exception error) {
        return ResponseEntity.badRequest()
                .body(new RegistrationController.MessageResponse("请检查填写内容后重试。"));
    }
}
