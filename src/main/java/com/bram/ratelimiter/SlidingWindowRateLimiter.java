package com.bram.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SlidingWindowRateLimiter {

  // Key: User ID, Value: start time of current window
  private final ConcurrentHashMap<String, Long> windowStartTimes = new ConcurrentHashMap<>();
  // Key: User ID, Value: counter for the current bucket
  private final ConcurrentHashMap<String, AtomicInteger>  currentWindowCounts = new ConcurrentHashMap<>();
  // Key: User ID, Value: counter for the previous bucket
  private final ConcurrentHashMap<String, AtomicInteger> previousWindowCounts = new ConcurrentHashMap<>();

  private final int MAX_REQUESTS;
  private final long WINDOW_SIZE_MS;

  /**
   * Initializes the rate limiter.
   * @param maxRequests The maximum number of requests allowed.
   * @param windowSizeMinutes The time window duration in minutes.
   */
  public SlidingWindowRateLimiter(int maxRequests, long windowSizeMinutes) {
    this.MAX_REQUESTS = maxRequests;
    this.WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(windowSizeMinutes);
  }

  /**
   * Checks if a request from the given user should be allowed.
   * @param userId The unique identifier for the user.
   * @return true if the request is allowed, false otherwise.
   */
  public boolean allowRequest(String userId) {
    long currentTime = System.currentTimeMillis();

    // 1. Get or initialize the current window and its start time
    long currentWindowStart = windowStartTimes.computeIfAbsent(userId, k -> currentTime);
    AtomicInteger currentCount = currentWindowCounts.computeIfAbsent(userId, k -> new AtomicInteger());
    AtomicInteger previousCount = previousWindowCounts.computeIfAbsent(userId, k -> new AtomicInteger());

    // 2. Check if the current window has expired (time has moved to the next bucket)
    if (currentTime >= currentWindowStart + WINDOW_SIZE_MS) {
      // Shift the windows: Current becomes Previous and a new Current starts

      // Set previous count to the current count
      previousWindowCounts.put(userId, currentCount);

      // Start new current window
      windowStartTimes.put(userId, currentWindowStart + WINDOW_SIZE_MS);
      currentWindowCounts.put(userId, new AtomicInteger(1)); // New request starts the count at 1

      // Reset the old previous counter (which is now completely expired) to 0,
      // but this is handled naturally in the weight calculation for the *next* rotation.
      // For simplicity, we can clear the expired previous window's count.

      return true;
    }

    // 3. Calculate the approximate request count in the sliding window
    long timeElapsed = currentTime - currentWindowStart;
    double previousWindowWeight = 1.0 - ((double) timeElapsed / (double) WINDOW_SIZE_MS);

    // Weighted count = (Previous Count * Weight) + Current Count
    int weightedCount = (int) (previousCount.get() * previousWindowWeight) + currentCount.get();

    // 4. Decision: Check against the limit
    if (weightedCount < MAX_REQUESTS) {
      // Request allowed: increment the current window count
      currentCount.incrementAndGet();
      return true;
    } else {
      // request denied
      return false;
    }
  }

}
