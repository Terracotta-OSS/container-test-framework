package com.tc.test.server.util;

public class RetryException extends Exception {
  public RetryException(final String msg) {
    super(msg);
  }
}
