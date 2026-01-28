package com.trendyol.stove.examples.java.spring.infra.boilerplate.http;

import com.trendyol.stove.recipes.shared.application.BusinessException;
import com.trendyol.stove.recipes.shared.application.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ControllerAdvice {

  private final Logger logger = LoggerFactory.getLogger(ControllerAdvice.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> handleException(BusinessException e) {
    logger.error("Business exception occurred", e);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse(e.getMessage(), "409"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> handleException(Exception e) {
    logger.error("Exception occurred", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse(e.getMessage(), "500"));
  }
}
