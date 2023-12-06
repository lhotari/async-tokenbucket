# async-tokenbucket

Proof-of-concept example of implementing an asynchronous token bucket that can be used as a building block 
for rate limiters. This proof-of-concept work is related to improving and replacing the current rate limiter implementation in Pulsar core.
There's a [related mailing list thread on the dev@pulsar.apache.org mailing list](https://lists.apache.org/thread/13ncst2nc311vxok1s75thl2gtnk7w1t).

## Why?

This is an asynchronous token bucket algorithm implementation that is optimized for performance with highly concurrent
use. There is no synchronization or blocking. CAS (compare-and-swap) operations are used and multiple levels of CAS 
fields are used to minimize contention when using CAS fields. The JVM's LongAdder class is used in the hot path to 
hold the sum of consumed tokens.

The performance of the token bucket calculations exceeds over 20M operations per second on a single thread tested on a developer laptop. With 100 threads, the throughput was about 240M ops/s. This proves that the overhead of the token bucket is well suited for Apache Pulsar's rate limiter use cases.

### Main usage flow of the AsyncTokenBucket class

source code: [`AsyncTokenBucket.java`](src/main/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucket.java)\
unit test: [`AsyncTokenBucketTest.java`](src/test/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketTest.java) 

1. tokens are consumed by calling the `consumeTokens` method.
2. the `calculatePauseNanos` method is called to calculate the duration of a possible needed pause when the tokens are fully consumed.

The `AsyncTokenBucket` class doesn't have side effects, it's like a stateful function, just like a counter function is a stateful function.
Indeed, it is just a sophisticated counter. It can be used as a building block for implementing higher level asynchronous rate limiter 
implementations which do need side effects.

### Running the performance test

This can be used to check the overhead of the token bucket calculations and how many operations are achieved with 1, 10 or 100 threads.

```
./gradlew performanceTest
```

example output:
```
❯ ./gradlew performanceTest

> Task :performanceTest

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 235871897 tokens:199807416
    Achieved rate: 23,587,189 ops per second with 1 threads
    Consuming for 10 seconds...
    Counter value 247271385 tokens:199828871
    Achieved rate: 24,727,138 ops per second with 1 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 PASSED

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 1913229472 tokens:-716253506
    Achieved rate: 191,322,947 ops per second with 10 threads
    Consuming for 10 seconds...
    Counter value 1852105604 tokens:-653720507
    Achieved rate: 185,210,560 ops per second with 10 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 PASSED

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 2491281782 tokens:-1292454774
    Achieved rate: 249,128,178 ops per second with 100 threads
    Consuming for 10 seconds...
    Counter value 2392603274 tokens:-1193641232
    Achieved rate: 239,260,327 ops per second with 100 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 PASSED

BUILD SUCCESSFUL in 1m
3 actionable tasks: 1 executed, 2 up-to-date
```
