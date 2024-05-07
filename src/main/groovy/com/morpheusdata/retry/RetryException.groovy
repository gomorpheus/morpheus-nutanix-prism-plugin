package com.morpheusdata.retry
/**
 * Thrown by retry utility.
 * @author Chris Taylor
 */
public class RetryException extends Exception {
  
  private final String code;

  public RetryException(String code) {
    super();
    this.code = code;
  }

  public RetryException(String message, Throwable cause, String code) {
    super(message, cause);
    this.code = code;
  }

  public RetryException(String message, String code) {
    super(message);
    this.code = code;
  }

  public RetryException(Throwable cause, String code) {
    super(cause);
    this.code = code;
  }

  public String getCode() {
    return this.code;
  }
}
