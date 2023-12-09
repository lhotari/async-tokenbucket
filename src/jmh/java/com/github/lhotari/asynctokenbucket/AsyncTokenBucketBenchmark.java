package com.github.lhotari.asynctokenbucket;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class AsyncTokenBucketBenchmark {
    private AsyncTokenBucket asyncTokenBucket;
    private AsyncTokenBucket.GranularMonotonicClockSource clockSource =
            new AsyncTokenBucket.GranularMonotonicClockSource(
                    TimeUnit.MILLISECONDS.toNanos(8),
                    System::nanoTime);

    @Setup(Level.Iteration)
    public void setup() {
        long ratePerSecond = 100_000_000;
        asyncTokenBucket = AsyncTokenBucket.builder()
                .rate(ratePerSecond)
                .clockSource(clockSource)
                .initialTokens(2 * ratePerSecond)
                .capacity(2 * ratePerSecond)
                .build();
    }

    @TearDown(Level.Iteration)
    public void teardown() {
        clockSource.close();
    }

    @Threads(1)
    @Benchmark
    @Measurement(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    @Warmup(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    public void consumeTokensBenchmark001Threads() {
        asyncTokenBucket.consumeTokens(1);
    }

    @Threads(10)
    @Benchmark
    @Measurement(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    @Warmup(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    public void consumeTokensBenchmark010Threads() {
        asyncTokenBucket.consumeTokens(1);
    }

    @Threads(100)
    @Benchmark
    @Measurement(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    @Warmup(time = 10, timeUnit = TimeUnit.SECONDS, iterations = 1)
    public void consumeTokensBenchmark100Threads() {
        asyncTokenBucket.consumeTokens(1);
    }
}
