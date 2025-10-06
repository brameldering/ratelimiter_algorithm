package com.bram.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterTest {

  // Configuration for the test
  private static final int MAX_REQUESTS = 10;
  private static final long WINDOW_SIZE_MINUTES = 1;
  private static final long WINDOW_SIZE_MS = TimeUnit.MINUTES.toMillis(WINDOW_SIZE_MINUTES);
  private static final String TEST_USER_ID = "testUser123";

  // Initial time for the test (arbitrary but fixed)
  private static final long INITIAL_TIME_MS = 1678886400000L; // Mar 15, 2023 12:00:00 AM UTC

  @Mock // 1. Use Mockito to create a mock instance of the interface
  private TimeSource mockTimeSource;

  private SlidingWindowRateLimiter limiter;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    // Initialize the rate limiter before each test
    limiter = new SlidingWindowRateLimiter(MAX_REQUESTS, WINDOW_SIZE_MINUTES, mockTimeSource);
  }

  /**
   * Test case to verify that multiple requests are allowed when they are
   * well under the limit and a partial window shift has occurred.
   * This uses Mockito's MockedStatic to control System.currentTimeMillis().
   */
  @Test
  void testMultipleAllowRequestCallsReturnTrue() {

    // The list of time points and expected results.
    // In this test, we expect TRUE for all of them.
    final long TIME_POINT_1 = INITIAL_TIME_MS;
    final long TIME_POINT_2 = INITIAL_TIME_MS + (WINDOW_SIZE_MS / 4); // 25% into the window
    final long TIME_POINT_3 = INITIAL_TIME_MS + (WINDOW_SIZE_MS / 2) + (WINDOW_SIZE_MS / 8); // 62.5% into the *next* window
    final long TIME_POINT_4 = INITIAL_TIME_MS + (WINDOW_SIZE_MS * 1) + 1; // Just past the first window shift

    // Define the sequence of expected calls and the time at which they occur.
    // We only send a total of 5 requests, which is < MAX_REQUESTS (10),
    // spread across two window shifts to ensure the weighted count stays low.
    final Object[][] testSequence = new Object[][]{
        {TIME_POINT_1, "R1 - New Window Start"},
        {TIME_POINT_1 + 1, "R2 - Immediately after R1"},
        {TIME_POINT_2, "R3 - 25% through W1"},
        {TIME_POINT_3, "R4 - 62.5% through W2 (W1 is now previous)"},
        {TIME_POINT_4, "R5 - Just into W2 (W1 is now fully previous)"}
    };

    for (Object[] call : testSequence) {
      long time = (long) call[0];
      String description = (String) call[1];

      // Mock time for the current call
      when(mockTimeSource.currentTimeMillis()).thenReturn(time);

      // Execute the method
      boolean result = limiter.allowRequest(TEST_USER_ID);

      // Assert that the result is TRUE, providing a detailed message if it fails
      assertTrue(result, () -> "Request '" + description + "' at time " + time + " was unexpectedly denied (returned FALSE).");
    }
  }

  /**
   * Test case to verify that a near-limit scenario correctly returns TRUE for the final allowed request.
   */
  @Test
  void testAllowedRequestUpToLimitReturnsTrue() {

    // Define a fixed time for all requests within the first window
    final long FIXED_TIME = INITIAL_TIME_MS;

    // Use Mockito to control System.currentTimeMillis()
    when(mockTimeSource.currentTimeMillis()).thenReturn(FIXED_TIME);

    // Send MAX_REQUESTS - 1 requests, all should be allowed
    for (int i = 1; i < MAX_REQUESTS; i++) {
      // 1. Create a final copy of the loop variable 'i'
      final int requestNumber = i;
      boolean result = limiter.allowRequest(TEST_USER_ID);
      assertTrue(result,
          () -> String.format("Request #%d (should be allowed) was unexpectedly denied.", requestNumber));
    }

    // The final request (Request #MAX_REQUESTS) should also be allowed
    boolean finalResult = limiter.allowRequest(TEST_USER_ID);
    assertTrue(finalResult,
        () -> String.format("Final Request #%d (should be allowed at limit) was unexpectedly denied.", MAX_REQUESTS));

    // Optional: The very next request (Request #MAX_REQUESTS + 1) should return FALSE
    boolean deniedResult = limiter.allowRequest(TEST_USER_ID);
    assertTrue(!deniedResult,"Request past the limit was unexpectedly ALLOWED.");
  }
}
