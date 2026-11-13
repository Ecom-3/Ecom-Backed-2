package com.metaarch.orgaccess.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserController.class)
public class UserApiErrors {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalidFields(MethodArgumentNotValidException error) {
    Map<String, String> fields = new LinkedHashMap<>();
    error.getBindingResult().getFieldErrors()
        .forEach(field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
    return ResponseEntity.badRequest().body(Map.of(
        "status", HttpStatus.BAD_REQUEST.value(),
        "message", "Correct the highlighted fields and try again.",
        "fields", fields));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException error) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage()));
  }

  @ExceptionHandler(UsernameNotFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(UsernameNotFoundException error) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
  }
  @ExceptionHandler(DuplicateUserContactException.class)
  ResponseEntity<Map<String, Object>> duplicateContact(DuplicateUserContactException error) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", error.getMessage());
    body.put("fields", error.fields());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, Object>> duplicateRace(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "message", "A username, email address, or mobile number was just registered. Check the values and try again."));
  }
}
