package com.bram.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterEdgeCaseTest {

  private static final int MAX_REQUESTS = 10;
  private static final long WINDOW_SIZE_MINUTES = 1;
  private static final long WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(WINDOW_SIZE_MINUTES);
  private static final String TEST_USER_ID = "weightedDenialUser";
  private static final long INITIAL_TIME_MS = 1678886400000L;

  @Mock // 1. Declare the Mock TimeSource
  private TimeSource mockTimeSource;

  private SlidingWindowRateLimiter limiter;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // 2. Initialize the limiter with the mock time source
    limiter = new SlidingWindowRateLimiter(MAX_REQUESTS, WINDOW_SIZE_MINUTES, mockTimeSource);
  }

  @Test
  void testDenialByWeightedCountAfterPartialShift() {

    // --- Step 1: Fill the Previous Window (W1) ---

    // Set the initial time using the mock
    when(mockTimeSource.currentTimeMillis()).thenReturn(INITIAL_TIME_MS);

    // Send 9 requests (Prev Count = 9)
    for (int i = 1; i <= 9; i++) {
      assertTrue(limiter.allowRequest(TEST_USER_ID), "W1 Request #" + i + " unexpectedly denied.");
    }

    // --- Step 2: Force Window Shift (W1 -> Previous) ---
    // Advance time just past the window boundary (60 minutes + 1ms)
    long shiftTime = INITIAL_TIME_MS + WINDOW_SIZE_MS + 1;
    when(mockTimeSource.currentTimeMillis()).thenReturn(shiftTime);

    // Send 1st request of W2 to trigger the shift logic (Prev=9, Curr=0 before check)
    // Weighted check: (9 * 0) + 0 = 0. ALLOWED. Current Count -> 1.
    assertTrue(limiter.allowRequest(TEST_USER_ID), "W2 Request #1 (Shift trigger) unexpectedly denied.");

    // Now: Previous Count = 9, Current Count = 1 (and Current Window Start is now shiftTime)

    // --- Step 3: Advance Time into the new Current Window (W2) ---
    // This is the new time point where weighted calculation will occur (50% through W2)
    long middleTimeW2 = shiftTime + (WINDOW_SIZE_MS / 2);

    // Update the mock time
    when(mockTimeSource.currentTimeMillis()).thenReturn(middleTimeW2);

    // At this point (before next request):
    // Previous Count = 9, Current Count = 1, Weight = 0.5
    // Weighted Total before check for next request: Math.floor(9 * 0.5) + 1 = 4 + 1 = 5.

    // Send 4 more requests (Requests 2-5 of W2)
    // Current Count goes from 1 to 5. Weighted goes from 5 to 9.
    for (int i = 2; i <= 5; i++) {
      // Weighted count before check: 4 + (i-1). Max weighted check here is 4 + 4 = 8.
      assertTrue(limiter.allowRequest(TEST_USER_ID),
          "W2 Request #" + i + " unexpectedly denied (Weighted count before check: " + (4 + (i - 1)) + ").");
    }

    // Now: Current Count = 5. Weighted Total (before next request) = 4 + 5 = 9.

    // --- Step 4: The Limit Request (6th request in W2) ---
    // Weighted check: Math.floor(9 * 0.5) + 5 = 9. 9 < 10. ALLOWED. Current Count -> 6.
    assertTrue(limiter.allowRequest(TEST_USER_ID), "W2 Request #6 unexpectedly denied (Weighted: 9).");

    // Now: Current Count = 6. Weighted Total (before denial request) = 4 + 6 = 10.

    // --- Step 5: The Denial Request (7th request in W2) ---
    // Weighted check: Math.floor(9 * 0.5) + 6 = 10. 10 < 10 is FALSE. DENIED.
    boolean denialResult = limiter.allowRequest(TEST_USER_ID);

    // Assert denial
    assertFalse(denialResult,
        "W2 Request #7 was unexpectedly allowed (Weighted count should be 10).");
  }
}