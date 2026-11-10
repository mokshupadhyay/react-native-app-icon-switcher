package com.flexappicon;

/** An expected failure with a stable code; see `AppIconErrorCode` in JavaScript. */
public final class FlexAppIconException extends Exception {
  public final String code;

  FlexAppIconException(String code, String message) {
    super(message);
    this.code = code;
  }
}
