package com.bram.ratelimiter;

public class SystemTimeSource implements TimeSource {
  @Override
  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }
}
