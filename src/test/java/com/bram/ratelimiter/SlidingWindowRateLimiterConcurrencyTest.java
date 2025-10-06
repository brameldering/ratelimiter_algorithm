package com.bram.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class SlidingWindowRateLimiterConcurrencyTest {

  private static final int MAX_REQUESTS = 10;
  private static final long WINDOW_SIZE_MINUTES = 1;
  private static final String TEST_USER_ID = "concurrentUser";
  private static final int NUM_CONCURRENT_REQUESTS = 20;
  private static final int NUM_THREADS = 20;
  private static final long INITIAL_TIME_MS = 1678886400000L;

  @Mock
  private TimeSource mockTimeSource;

  private SlidingWindowRateLimiter limiter;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    // Inject the mocked time source into the limiter
    limiter = new SlidingWindowRateLimiter(MAX_REQUESTS, WINDOW_SIZE_MINUTES, mockTimeSource);
  }

  @Test
  void testConcurrencyHandlesLimitExactly() throws InterruptedException {

    when(mockTimeSource.currentTimeMillis()).thenReturn(INITIAL_TIME_MS);

    // Atomic counters to safely track results from multiple threads
    AtomicInteger allowedRequests = new AtomicInteger(0);
    AtomicInteger deniedRequests = new AtomicInteger(0);

    // Latch to hold threads until they are all ready to fire
    CountDownLatch readyLatch = new CountDownLatch(NUM_THREADS);
    // Latch to wait until all threads have completed their work
    CountDownLatch finishedLatch = new CountDownLatch(NUM_THREADS);

    ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);

    try {
      for (int i = 0; i < NUM_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Signal that this thread is ready
            readyLatch.countDown();

            // Wait for all threads to be ready
            readyLatch.await();

            // Execute the core logic
            boolean result = limiter.allowRequest(TEST_USER_ID);

            if (result) {
              allowedRequests.incrementAndGet();
            } else {
              deniedRequests.incrementAndGet();
            }

          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            // Signal that this thread is finished
            finishedLatch.countDown();
          }
        });
      }

      // Wait for all threads to finish (timeout after 5 seconds)
      assertTrue(finishedLatch.await(5, TimeUnit.SECONDS),
          "Threads did not complete within the timeout.");

      // 1. Verify the exact number of allowed requests
      assertEquals(MAX_REQUESTS, allowedRequests.get(),
          "The number of allowed requests must exactly match the MAX_REQUESTS limit.");

      // 2. Verify the exact number of denied requests
      int expectedDenied = NUM_CONCURRENT_REQUESTS - MAX_REQUESTS;
      assertEquals(expectedDenied, deniedRequests.get(),
          "The number of denied requests must be (Total - Limit).");

    } finally {
      // Always shut down the executor service
      executor.shutdownNow();
    }
  }
}
