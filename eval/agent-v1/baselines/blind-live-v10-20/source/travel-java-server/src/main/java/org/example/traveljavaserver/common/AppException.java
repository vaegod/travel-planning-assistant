package org.example.traveljavaserver.common;

import org.springframework.http.HttpStatus;

/** Public, sanitized error contract; never include upstream response bodies or keys. */
public class AppException extends RuntimeException {
  private final HttpStatus status;
  private final String errorCode;

  public AppException(HttpStatus status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }
}
