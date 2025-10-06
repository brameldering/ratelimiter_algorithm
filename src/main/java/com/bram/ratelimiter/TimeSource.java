package com.bram.ratelimiter;

public interface TimeSource {
  long currentTimeMillis();
}
