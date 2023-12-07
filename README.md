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
unit test: [`AsyncTokenBucketTest.java`](src/test/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketTest.java)\
performance test: [`AsyncTokenBucketPerformanceTest.java`](src/performanceTest/java/com/github/lhotari/asynctokenbucket/AsyncTokenBucketPerformanceTest.java)

1. tokens are consumed by calling the `consumeTokens` method.
2. the `calculateThrottlingDuration` method is called to calculate the duration of a possible needed pause when the tokens are fully consumed.

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

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 242885402 tokens:199906063
    Achieved rate: 24,288,540 ops per second with 1 threads
    Consuming for 10 seconds...
    Counter value 233032728 tokens:199797294
    Achieved rate: 23,303,272 ops per second with 1 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 1859380530 tokens:-669249705
    Achieved rate: 185,938,053 ops per second with 10 threads
    Consuming for 10 seconds...
    Counter value 1851200123 tokens:-652321610
    Achieved rate: 185,120,012 ops per second with 10 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 2419541599 tokens:-1220363322
    Achieved rate: 241,954,159 ops per second with 100 threads
    Consuming for 10 seconds...
    Counter value 2327767683 tokens:-1128965391
    Achieved rate: 232,776,768 ops per second with 100 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 PASSED
```

#### Known issue: performance on Apple M2/M3 hardware is significantly lower than on Intel 64-bit hardware

On Apple M2/M3 hardware, the performance results are significantly lower. Here's an example output from a Mac with Apple M3 Max CPU:

```
❯ ./gradlew performanceTest

> Task :performanceTest

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 463170669 tokens:199401129
    Achieved rate: 46,317,066 ops per second with 1 threads
    Consuming for 10 seconds...
    Counter value 463248753 tokens:199290547
    Achieved rate: 46,324,875 ops per second with 1 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [1] 1 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 116056013 tokens:199912361
    Achieved rate: 11,605,601 ops per second with 10 threads
    Consuming for 10 seconds...
    Counter value 116230314 tokens:199909024
    Achieved rate: 11,623,031 ops per second with 10 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [2] 10 PASSED

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 STANDARD_OUT
    Consuming for 10 seconds...
    Counter value 90589397 tokens:199913442
    Achieved rate: 9,058,939 ops per second with 100 threads
    Consuming for 10 seconds...
    Counter value 90459274 tokens:199908562
    Achieved rate: 9,045,927 ops per second with 100 threads

AsyncTokenBucketPerformanceTest > shouldPerformanceOfConsumeTokensBeSufficient(int) > [3] 100 PASSED
```

The throughput is about 3x with GraalVM 21.0.1 native image on the same hardware:

```
> Task :nativePerformanceTest
JUnit Platform on Native Image - report
----------------------------------------

Consuming for 10 seconds...
Counter value 413797238 tokens:199610232
Achieved rate: 41,379,723 ops per second with 1 threads
Consuming for 10 seconds...
Counter value 460472930 tokens:199481644
Achieved rate: 46,047,293 ops per second with 1 threads
Consuming for 10 seconds...
Counter value 262045629 tokens:199870668
Achieved rate: 26,204,562 ops per second with 10 threads
Consuming for 10 seconds...
Counter value 264312328 tokens:199868059
Achieved rate: 26,431,232 ops per second with 10 threads
Consuming for 10 seconds...
Counter value 270598198 tokens:199857211
Achieved rate: 27,059,819 ops per second with 100 threads
Consuming for 10 seconds...
Counter value 270151795 tokens:199847535
Achieved rate: 27,015,179 ops per second with 100 threads
com.github.lhotari.asynctokenbucket.AsyncTokenBucketPerformanceTest > [1] 1 SUCCESSFUL

com.github.lhotari.asynctokenbucket.AsyncTokenBucketPerformanceTest > [2] 10 SUCCESSFUL

com.github.lhotari.asynctokenbucket.AsyncTokenBucketPerformanceTest > [3] 100 SUCCESSFUL
```
