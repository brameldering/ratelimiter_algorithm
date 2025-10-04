# RateLimiter Demo

1. Core Concept
   The rate limiter tracks requests for a user over a single time period, or window (e.g., 1 minute). To prevent a "burst" of requests at the edge of the window (a weakness of the simple Fixed Window Counter), it uses two buckets:

Current Count (C): Requests in the current time window.

Previous Count (P): Requests in the previous, entirely finished time window.

Weighted Count (W): A calculated, approximate total number of requests in the true sliding window, where W=(P×Weight)+C.

The request is allowed only if the Weighted Count<MAX_REQUESTS.

2. Request Processing (allowRequest Method)
   The process is broken down into three main steps:

A. Initialization and Locking
Initialization: When a user ID is seen for the first time, their windowStartTimes, currentWindowCounts (C), and previousWindowCounts (P) are initialized. C and P start at 0, and the window start time is the current time.

Per-User Lock: A ReentrantLock is acquired for the specific userId. This ensures that window shifts (when the current window expires) are atomic and thread-safe for that user, preventing race conditions where multiple threads could try to shift the window simultaneously.

B. Window Shift Check (The Key Logic)
The code checks if the current time is greater than or equal to the current window's start time plus the defined WINDOW_SIZE_MS.

If the window has fully expired: A Window Shift occurs:

The currentCount (C) is moved to become the new previousWindowCounts (P).

A new currentWindowCounts (C) is initialized with a value of 1, because the current request is the first one in the new window.

The windowStartTimes is updated to be the start time of the next full window (old start time + WINDOW\_SIZE\_MS).

The request that triggered the shift is ALLOWED and the method returns true.

If the window has NOT expired: The lock is released, and the process proceeds to the weighted calculation.

C. Weighted Calculation and Decision
This step is executed if no window shift occurred.

Time Elapsed: The time passed since the currentWindowStart is calculated: timeElapsed=currentTime−currentWindowStart.

Previous Window Weight: The percentage of the previous window's requests (P) that overlap with the current sliding window is calculated. This is the core of the sliding window approximation.

Weight=1.0−(
WINDOW_SIZE_MS
timeElapsed
​
)

A small timeElapsed (just starting the window) results in a weight close to 1.0, meaning the previous count is almost fully counted. A large timeElapsed (near the end of the window) results in a weight close to 0.0, meaning the previous count is almost fully ignored.

Weighted Count: The total count is approximated:

Weighted Count=round(P×Weight)+C
Decision:

If Weighted Count<MAX_REQUESTS: The request is ALLOWED. The currentCount (C) is incremented.

If Weighted Count≥MAX_REQUESTS: The request is DENIED. The counts remain unchanged.

3. Cleanup (cleanupExpiredEntries Method)
   This method provides essential memory management. It iterates through all tracked user IDs and removes any associated entries (window start time, C, P, and Lock) if the current window's start time is older than the cleanup threshold (currentTime−WINDOW_SIZE_MS). This prevents memory leaks by removing data for inactive users.