package com.bram.ratelimiter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterCleanupTest {

  private static final int MAX_REQUESTS = 10;
  private static final long WINDOW_SIZE_MINUTES = 1;
  private static final long WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(WINDOW_SIZE_MINUTES);
  private static final String TEST_USER_ID = "staleWindowUser";
  private static final long INITIAL_TIME_MS = 1678886400000L;

  @Mock // Mock the time source
  private TimeSource mockTimeSource;

  private SlidingWindowRateLimiter limiter;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // Initialize the limiter with the mock time source
    limiter = new SlidingWindowRateLimiter(MAX_REQUESTS, WINDOW_SIZE_MINUTES, mockTimeSource);
  }

  @Test
  void testDenialAfterMultipleStaleWindowShifts() {

    // --- Step 1: Fill the Initial Window ---
    when(mockTimeSource.currentTimeMillis()).thenReturn(INITIAL_TIME_MS);

      // Send 5 requests
    for (int i = 1; i <= 5; i++) {
      assertTrue(limiter.allowRequest(TEST_USER_ID), "Initial request unexpectedly denied.");
    }

    // --- Step 2: Simulate Long Time Skip (3 full windows) ---
    long futureTime = INITIAL_TIME_MS + (3 * WINDOW_SIZE_MS) + 1; // 3 minutes and 1 ms later
    when(mockTimeSource.currentTimeMillis()).thenReturn(futureTime);

    // Expect: Both previous and current counts are reset to 0.

    // --- Step 3: Fill the New Window to the Limit ---
    for (int i = 1; i <= MAX_REQUESTS; i++) {
      final int requestNumber = i;
      boolean result = limiter.allowRequest(TEST_USER_ID);
      assertTrue(result,
          () -> String.format("Request #%d in new window unexpectedly denied.", requestNumber));
    }

    // --- Step 4: The Denial Request (11th request in the new window) ---
    boolean denialResult = limiter.allowRequest(TEST_USER_ID);

    // Assert denial
    assertFalse(denialResult,
        "The 11th request in the new, non-stale window was unexpectedly allowed.");
  }
}
