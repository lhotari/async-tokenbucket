# async-tokenbucket

Proof-of-concept example of implementing an asynchronous token bucket
that can be used as a building block for rate limiters. This
proof-of-concept work is related to improving and replacing the current
rate limiter implementation in Pulsar core. There's more information
about the design in Apache Pulsar in ["PIP-322: Pulsar Rate Limiting
Refactoring"](https://github.com/apache/pulsar/blob/master/pip/pip-322.md).

## Why?

This is an asynchronous token bucket algorithm implementation that is
optimized for performance with highly concurrent use. There is no
synchronization or blocking. CAS (compare-and-swap) operations are used
and multiple levels of CAS fields are used to minimize contention when
using CAS fields. The JVM's LongAdder class is used in the hot path to
hold the sum of consumed tokens.

The performance of the token bucket calculations exceeds over 230M
operations per second on a single thread tested on a developer laptop
(Apple M3 Max). With 100 threads, the throughput was over 2500M ops/s.
This proves that the overhead of the token bucket is well suited for
Apache Pulsar's rate limiter use cases.

### Main usage flow of the AsyncTokenBucket class

source code:
[`AsyncTokenBucket.java`](src/main/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucket.java)\
unit test:
[`AsyncTokenBucketTest.java`](src/test/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketTest.java)\
performance test:
[`AsyncTokenBucketPerformanceTest.java`](src/performanceTest/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketPerformanceTest.java)
JMH benchmark:
[`AsyncTokenBucketBenchmark.java`](src/jmh/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketBenchmark.java)

 1. Tokens are consumed by invoking the `consumeTokens` or
    `consumeTokensAndCheckIfContainsTokens`` methods.
 2. The `consumeTokensAndCheckIfContainsTokens` or `containsTokens``
    methods return false if there are no tokens available, indicating a
    need for throttling.
 3. In case of throttling, the application should throttle in a way that
 is suitable for the use case and then call the
 `calculateThrottlingDuration`` method to calculate the duration of the
 required pause.
 4. After the pause duration, the application should verify if there are
 any available tokens by invoking the `containsTokens` method. If tokens
 are available, the application should cease throttling. However, if
 tokens are not available, the application should maintain the
 throttling and recompute the throttling duration. In a concurrent
 environment, it is advisable to use a throttling queue to ensure fair
 distribution of resources across throttled connections or clients. Once
 the throttling duration has elapsed, the application should select the
 next connection or client from the throttling queue to unthrottle.
 Before unthrottling, the application should check for available tokens.
 If tokens are still not available, the application should continue with
 throttling and repeat the throttling loop.

The `AsyncTokenBucket` class does not produce side effects outside of
its own scope. It functions similarly to a stateful function, akin to a
counter function. In essence, it is a sophisticated counter. It can
serve as a foundational component for constructing higher-level
asynchronous rate limiter implementations, which require side effects
for throttling.

### Running the performance test

This can be used to check the overhead of the token bucket calculations and how many operations are achieved with 1, 10 or 100 threads.

```
./gradlew performanceTest
```

example output with Apple M3 Max on MacOS:
```
❯ ./gradlew performanceTest

> Task :performanceTest

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 2362657156 tokens:-1164198755
    Achieved rate: 236,265,715 ops per second with 1 threads
    Consuming for 10 seconds...
    Counter value 2364051745 tokens:-1164134052
    Achieved rate: 236,405,174 ops per second with 1 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 20921678569 tokens:-19724854164
    Achieved rate: 2,092,167,856 ops per second with 10 threads
    Consuming for 10 seconds...
    Counter value 20969056612 tokens:-19770941187
    Achieved rate: 2,096,905,661 ops per second with 10 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 26525265079 tokens:-25326434199
    Achieved rate: 2,652,526,507 ops per second with 100 threads
    Consuming for 10 seconds...
    Counter value 26561718043 tokens:-25362435826
    Achieved rate: 2,656,171,804 ops per second with 100 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 PASSED

BUILD SUCCESSFUL in 1m
4 actionable tasks: 1 executed, 3 up-to-date
```

There's also a JMH benchmark that validates the results with JMH.

```
./gradlew jmh
```