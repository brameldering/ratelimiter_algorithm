package com.bram.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe Sliding Window Rate Limiter.
 *
 * The limiter maintains two time buckets (previous and current)
 * and calculates a weighted count to approximate a sliding window.
 *
 * Algorithm summary:
 *  - Requests are counted within a fixed-size time window (e.g., 1 minute).
 *  - When time passes into the next window, the old window’s count becomes the “previous window.”
 *  - A weighted combination of the two counts determines if new requests are allowed.
 */
public class SlidingWindowRateLimiter {

  // --- Internal state per user -------------------------------------------------

  // Key: User ID, Value: start time of current window
  private final ConcurrentHashMap<String, Long> windowStartTimes = new ConcurrentHashMap<>();
  // Key: User ID, Value: counter for the current bucket
  private final ConcurrentHashMap<String, AtomicInteger>  currentWindowCounts = new ConcurrentHashMap<>();
  // Key: User ID, Value: counter for the previous bucket
  private final ConcurrentHashMap<String, AtomicInteger> previousWindowCounts = new ConcurrentHashMap<>();
  // Key: User ID, Value: Lock (per-user lock for atomic window shifts)
  private final ConcurrentHashMap<String, Lock> userLocks = new ConcurrentHashMap<>();

  // --- Configuration ------------------------------------------------------------

  /** Maximum number of requests allowed within one full window. */
  private final int MAX_REQUESTS;

  /** Duration of one window in milliseconds. */
  private final long WINDOW_SIZE_MS;

  private final TimeSource timeSource;

  /**
   * Initializes the rate limiter.
   * @param maxRequests The maximum number of requests allowed.
   * @param windowSizeMinutes The time window duration in minutes.
   */
  public SlidingWindowRateLimiter(int maxRequests, long windowSizeMinutes, TimeSource timeSource) {
    this.MAX_REQUESTS = maxRequests;
    this.WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(windowSizeMinutes); this.timeSource = timeSource;
  }


  // --- Core logic ---------------------------------------------------------------

  /**
   * Determines whether a given user’s request should be allowed
   * according to the sliding window rate limit algorithm.
   *
   * @param userId Unique identifier for the user
   * @return true if allowed, false if rate-limited
   */
  public boolean allowRequest(String userId) {
    long currentTime = timeSource.currentTimeMillis();

    // 1. Get or initialize the current window and its start time and counters
    long currentWindowStart = windowStartTimes.computeIfAbsent(userId, k -> currentTime);
    currentWindowCounts.computeIfAbsent(userId, k -> new AtomicInteger());
    previousWindowCounts.computeIfAbsent(userId, k -> new AtomicInteger());

    // Use a per-user lock for thread-safe window shifting
    Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    userLock.lock();

    try {
      // 2. Check if the current window has expired (time has moved to the next bucket)
      if (currentTime >= currentWindowStart + WINDOW_SIZE_MS) {
        long windowsPassed = (currentTime - currentWindowStart) / WINDOW_SIZE_MS;

        if (windowsPassed >= 2) {
          // If more than one window has passed, both buckets are stale.
          // Reset both previous and current counts.
          previousWindowCounts.put(userId, new AtomicInteger(0));
          currentWindowCounts.put(userId, new AtomicInteger(0));
        } else {
          // Normal single-window shift:
          // Move current → previous, and reset current.
          AtomicInteger oldCurrentCount = currentWindowCounts.get(userId);
          int previousValue = (oldCurrentCount != null) ? oldCurrentCount.get() : 0;
          // Move old current → previous
          previousWindowCounts.put(userId, new AtomicInteger(previousValue));
          currentWindowCounts.put(userId, new AtomicInteger(0));
        }
        // Calculate the new window start time based on the old one
        long newWindowStart = currentWindowStart + (windowsPassed * WINDOW_SIZE_MS);
        // Set the new window start and current window start times
        windowStartTimes.put(userId, newWindowStart);
        currentWindowStart = newWindowStart;
      }

      // 3. Fetch the current counts AFTER the critical section
      // Use .get() here instead of computeIfAbsent because the map guarantees the entries exist
      // (They were either created in step 1 or inside the lock).
      AtomicInteger currentCount = currentWindowCounts.get(userId);
      AtomicInteger previousCount = previousWindowCounts.get(userId);

      // If for some reason one of these is null (e.g., race condition in cleanup), deny the request.
      if (currentCount == null || previousCount == null) {
        return false;
      }

      // 4. Calculate the approximate request count in the sliding window
      long timeElapsedInWindow = currentTime - currentWindowStart;

      // Calculate the weight of the previous window that overlaps with the current sliding window
      // Note: The weight should be between 0.0 and 1.0.
      // If timeElapsed > WINDOW_SIZE_MS, the previous window has fully expired, and the weight will be <= 0.
      double previousWindowWeight = 1.0 - ((double) timeElapsedInWindow / WINDOW_SIZE_MS);
      // Ensure weight is not negative (in case of significant clock skew or delay)
      previousWindowWeight = Math.max(0.0, previousWindowWeight);

      // Weighted count = (Previous Count * Weight) + Current Count
      // Use Math.round() for a more accurate integer approximation before casting
      int weightedCount = (int) Math.floor(previousCount.get() * previousWindowWeight) + currentCount.get();
      long msRemaining = Math.max(0, WINDOW_SIZE_MS - timeElapsedInWindow);

      // Debug info
      System.out.printf(
          "[DEBUG] User=%s | Prev=%d | Curr=%d | Weight=%.2f | Weighted=%d | Limit=%d | %dms left%n",
          userId,
          previousCount.get(),
          currentCount.get(),
          previousWindowWeight,
          weightedCount,
          MAX_REQUESTS,
          msRemaining
      );

      // 4. Decision: Check against the limit
      if (weightedCount < MAX_REQUESTS) {
        // Request allowed: increment the current window count
        currentCount.incrementAndGet();
        return true;
      } else {
        // request denied
        return false;
      }
    } finally {
      userLock.unlock();
    }
  }

  /**
   * Cleans up expired user entries from all maps to prevent memory leak.
   * This method should be called periodically (e.g., via a scheduled task).
   */
  public void cleanupExpiredEntries() {
    long cleanupTime = System.currentTimeMillis() - WINDOW_SIZE_MS;
    windowStartTimes.keySet().removeIf(userId -> {
      // Check the start time of the current window for this user
      Long startTime = windowStartTimes.get(userId);

      // If the start time is long past (e.g., more than a full window ago),
      // it's likely an abandoned entry or a window that should have shifted multiple times.
      // A more conservative check: remove if the current window is very old.
      if (startTime != null && startTime < cleanupTime) {
        // Remove from all maps
        windowStartTimes.remove(userId);
        currentWindowCounts.remove(userId);
        previousWindowCounts.remove(userId);
        userLocks.remove(userId);
        return true; // Indicates the entry was removed
      }
      return false; // Indicates the entry was kept
    });
  }
}

