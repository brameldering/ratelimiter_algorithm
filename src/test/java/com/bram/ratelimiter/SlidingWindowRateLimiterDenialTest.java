package com.bram.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterDenialTest {

  private static final int MAX_REQUESTS = 10;
  private static final long WINDOW_SIZE_MINUTES = 1;
  private static final long WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(WINDOW_SIZE_MINUTES);
  private static final String TEST_USER_ID = "weightedDenialUser";
  private static final long INITIAL_TIME_MS = 1678886400000L;

  @Mock // Mock the time source interface
  private TimeSource mockTimeSource;

  private SlidingWindowRateLimiter limiter;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // Inject the mocked time source into the limiter
    limiter = new SlidingWindowRateLimiter(MAX_REQUESTS, WINDOW_SIZE_MINUTES, mockTimeSource);
  }

  @Test
  void testDenialByWeightedCountAfterPartialShift() {

    // --- Step 1: Fill the Previous Window (W1) ---
    // Time is fixed at INITIAL_TIME_MS.
    when(mockTimeSource.currentTimeMillis()).thenReturn(INITIAL_TIME_MS);

    // Send 9 requests (Prev Count = 9)
    for (int i = 1; i <= 9; i++) {
      assertTrue(limiter.allowRequest(TEST_USER_ID), "W1 Request #" + i + " unexpectedly denied.");
    }

    // --- Step 2: Force Window Shift (W1 -> Previous) ---
    // Advance time just past the window boundary (60 minutes + 1ms)
    long shiftTime = INITIAL_TIME_MS + WINDOW_SIZE_MS + 1;
    when(mockTimeSource.currentTimeMillis()).thenReturn(shiftTime);

    // Send one request to trigger the shift logic inside the limiter
    assertTrue(limiter.allowRequest(TEST_USER_ID), "Shift trigger request denied.");

    // Now: Previous Count = 9, Current Count = 1 (and Current Window Start is now shiftTime)

    // --- Step 3: Advance Time into the new Current Window (W2) ---
    // This is the new time point where weighted calculation will occur (50% through W2)
    long middleTimeW2 = shiftTime + (WINDOW_SIZE_MS / 2);

    // Update the mock time
    when(mockTimeSource.currentTimeMillis()).thenReturn(middleTimeW2);

    // Send 6 requests into the new window (Curr Count = 6)
    // Expected Weighted Total after 6 requests: (9 * 0.5) + 6 = 10.5
    // Since 10.5 >= MAX_REQUESTS (10), the 6th request should be DENIED.

    // Send the first 5 allowed requests in W2
    for (int i = 1; i <= 5; i++) {
      assertTrue(limiter.allowRequest(TEST_USER_ID), "W2 Request #" + i + " unexpectedly denied (Weighted: 9.5).");
    }

    // --- Step 3: The Denial Request (6th request in W2) ---
    // Weighted count goes from 9.5 to 10.5. This request should be DENIED.
    boolean denialResult = limiter.allowRequest(TEST_USER_ID);

    // Assert denial
    assertFalse(denialResult,
        "W2 Request #6 was unexpectedly allowed (Weighted count should be 10.5 >= 10).");
  }
}