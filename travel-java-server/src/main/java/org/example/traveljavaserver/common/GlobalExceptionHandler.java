package org.example.traveljavaserver.common;

import java.util.stream.Collectors;
import org.example.traveljavaserver.vo.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Result<Void>> invalid(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .distinct()
            .sorted()
            .collect(Collectors.joining("；"));
    return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HandlerMethodValidationException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Result<Void>> malformed(Exception e) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求格式或参数类型不正确");
  }

  @ExceptionHandler(AppException.class)
  public ResponseEntity<Result<Void>> application(AppException e) {
    return error(e.getStatus(), e.getErrorCode(), e.getMessage());
  }

  @ExceptionHandler({
    org.springframework.web.HttpRequestMethodNotSupportedException.class,
    org.springframework.web.HttpMediaTypeNotSupportedException.class,
    org.springframework.web.HttpMediaTypeNotAcceptableException.class,
    org.springframework.web.servlet.resource.NoResourceFoundException.class
  })
  public ResponseEntity<Result<Void>> http(Exception e) {
    var response = (org.springframework.web.ErrorResponse) e;
    return error(
        HttpStatus.valueOf(response.getStatusCode().value()), "HTTP_ERROR", "请求路径、方法或媒体类型不支持");
  }

  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void disconnected(AsyncRequestNotUsableException e) {
    // The client has already disconnected; writing another response would fail again.
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> unexpected(Exception e) {
    log.error("Unhandled request failure: {}", e.getClass().getSimpleName());
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务发生异常，请稍后重试");
  }

  private ResponseEntity<Result<Void>> error(HttpStatus status, String code, String message) {
    Result<Void> body = Result.fail(status.value(), message);
    body.setErrorCode(code);
    return ResponseEntity.status(status)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(body);
  }
}
