# async-tokenbucket

Proof-of-concept example of implementing an asynchronous token bucket that can be used as a building block 
for rate limiters. This proof-of-concept work is related to improving and replacing the current rate limiter implementation in Pulsar core.
There's a [related mailing list thread on the dev@pulsar.apache.org mailing list](https://lists.apache.org/thread/13ncst2nc311vxok1s75thl2gtnk7w1t).

## Why?

This is an asynchronous token bucket algorithm implementation that is optimized for performance with highly concurrent
use. There is no synchronization or blocking. CAS (compare-and-swap) operations are used and multiple levels of CAS 
fields are used to minimize contention when using CAS fields. The JVM's LongAdder class is used in the hot path to 
hold the sum of consumed tokens.

The performance of the token bucket calculations exceeds over 10M operations per second per core tested on a developer
laptop. This proves that the overhead of the token bucket is well suited for Apache Pulsar's rate limiter use cases. 

### Main usage flow of the AsyncTokenBucket class

source code: [`AsyncTokenBucket.java`](src/main/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucket.java)\
unit test: [`AsyncTokenBucketTest.java`](src/test/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketTest.java) 

1. tokens are consumed by calling the `consumeTokens` method.
2. the `calculatePauseNanos` method is called to calculate the duration of a possible needed pause when the tokens are fully consumed.

The `AsyncTokenBucket` class doesn't have side effects, it's a pure function. It can be used as a building block for implementing
higher level asynchronous rate limiter implementations which do need side effects.

### Running the performance test

This can be used to check the overhead of the token bucket calculations and how many operations are achieved with a
single core on a single thread.

```
./gradlew performanceTest
```

example output:
```
❯ ./gradlew performanceTest

> Task :performanceTest

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 125128028 tokens:199941612
    Achieved rate: 12,512,802 ops per second with 1 threads
    Consuming for 10 seconds...
    Counter value 126059043 tokens:199920507
    Achieved rate: 12,605,904 ops per second with 1 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 PASSED

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 1150055476 tokens:45309290
    Achieved rate: 115,005,547 ops per second with 10 threads
    Consuming for 10 seconds...
    Counter value 1152924215 tokens:45692611
    Achieved rate: 115,292,421 ops per second with 10 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 PASSED

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 1650149177 tokens:-451095706
    Achieved rate: 165,014,917 ops per second with 100 threads
    Consuming for 10 seconds...
    Counter value 1664288687 tokens:-462912837
    Achieved rate: 166,428,868 ops per second with 100 threads

AsyncTokenBucketTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 PASSED

BUILD SUCCESSFUL in 1m 15s
3 actionable tasks: 3 executed
```