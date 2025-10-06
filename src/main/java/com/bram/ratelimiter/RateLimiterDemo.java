package com.bram.ratelimiter;

public class RateLimiterDemo {
  public static void main(String[] args) throws InterruptedException {

    TimeSource timer = new SystemTimeSource();

    // Limit: 10 requests per minute
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(10, 1, timer);
    String userA = "user_456";
    int requestsAllowed = 0;

    System.out.println("--- Test 1: Burst at the start of the minute ---");
    // Try to make 15 requests instantly
    for (int i = 0; i < 15; i++) {
      if (limiter.allowRequest(userA)) {
        requestsAllowed++;
        System.out.println("Request " + (i + 1) + ": ALLOWED");
      } else {
        System.out.println("Request " + (i + 1) + ": DENIED (Rate Limited)");
      }
    }
    // Result: Only 10 requests are allowed immediately.

    // Wait for 30 seconds (half the window)
    System.out.println("\n--- Waiting 30 seconds... ---");
    Thread.sleep(30000);

    System.out.println("\n--- Test 2: Requests after half the minute ---");
    // Try to make 5 more requests
    // Weighted count = (Prev Count * 0.5) + (Current Count)
    // Since Prev Count is 0 (as the first window just started), it's just Current Count
    // Wait, the logic is simplified for demonstration. In a true SWC:
    // Initial 10 requests are in the current bucket.
    // After 30s:
    // Prev Count (P) = 0.
    // Current Count (C) = 10.
    // Weighted Count = (0 * 0.5) + 10 = 10.
    // The first request in Test 2 will try to increment C to 11 and will be DENIED.

    for (int i = 0; i < 5; i++) {
      if (limiter.allowRequest(userA)) {
        requestsAllowed++;
        System.out.println("Request " + (i + 1) + ": ALLOWED");
      } else {
        System.out.println("Request " + (i + 1) + ": DENIED (Rate Limited)");
      }
    }
    // Result: All 5 are likely DENIED because the 10 requests from Test 1 are still fully counted
    // in the current window. The weighted previous count is 0.

    // Wait for 31 seconds more (total time passed is ~61 seconds, forcing a window shift)
    System.out.println("\n--- Waiting 31 seconds (Window Shift) ---");
    Thread.sleep(31000);

    System.out.println("\n--- Test 3: Requests after window shift ---");
    // After the shift:
    // Previous Count (P) is now 10 (from Test 1).
    // Current Count (C) is 0 (new bucket).
    // Try to make 10 requests.
    for (int i = 0; i < 11; i++) {
      if (limiter.allowRequest(userA)) {
        requestsAllowed++;
        System.out.println("Request " + (i + 1) + ": ALLOWED");
      } else {
        System.out.println("Request " + (i + 1) + ": DENIED (Rate Limited)");
      }
      // Wait for 3 seconds to let the window slide and free up capacity.
      Thread.sleep(3000);
    }
    // Result: All 10 are allowed as the previous window's requests have expired.
  }

}
