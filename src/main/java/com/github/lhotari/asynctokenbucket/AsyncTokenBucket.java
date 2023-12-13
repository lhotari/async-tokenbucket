/*
 * Copyright (C) 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.lhotari.asynctokenbucket;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * An asynchronous token bucket algorithm implementation that is optimized for performance with highly concurrent
 * use. CAS (compare-and-swap) operations are used and multiple levels of CAS fields are used to minimize contention
 * when using CAS fields. The {@link LongAdder} class is used in the hot path to hold the sum of consumed tokens.
 * It is eventually consistent, meaning that the tokens are not updated on every call to the "consumeTokens" method.
 * <p>Main usage flow:
 * 1. Tokens are consumed by invoking the "consumeTokens" or "consumeTokensAndCheckIfContainsTokens" methods.
 * 2. The "consumeTokensAndCheckIfContainsTokens" or "containsTokens" methods return false if there are no
 * tokens available, indicating a need for throttling.
 * 3. In case of throttling, the application should throttle in a way that is suitable for the use case
 * and then call the "calculateThrottlingDuration" method to calculate the duration of the required pause.
 * 4. After the pause duration, the application should verify if there are any available tokens by invoking the
 * containsTokens method. If tokens are available, the application should cease throttling. However, if tokens are
 * not available, the application should maintain the throttling and recompute the throttling duration. In a
 * concurrent environment, it is advisable to use a throttling queue to ensure fair distribution of resources across
 * throttled connections or clients. Once the throttling duration has elapsed, the application should select the next
 * connection or client from the throttling queue to unthrottle. Before unthrottling, the application should check
 * for available tokens. If tokens are still not available, the application should continue with throttling and
 * repeat the throttling loop.
 * <p>This class does not produce side effects outside of its own scope. It functions similarly to a stateful function,
 * akin to a counter function. In essence, it is a sophisticated counter. It can serve as a foundational component for
 * constructing higher-level asynchronous rate limiter implementations, which require side effects for throttling.
 * <p>To achieve optimal performance, pass a {@link GranularMonotonicClockSource} as the clock source.
 */
public abstract class AsyncTokenBucket {
    public static final MonotonicClockSource DEFAULT_CLOCK_SOURCE = highPrecision -> System.nanoTime();
    private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);
    // 2^24 nanoseconds is 16 milliseconds
    private static final long DEFAULT_RESOLUTION_NANOS = TimeUnit.MILLISECONDS.toNanos(16);

    // The default resolution is 16 milliseconds. This means that the consumed tokens are subtracted from the
    // current amount of tokens about every 16 milliseconds. This solution helps prevent a CAS loop what could cause
    // extra CPU usage when a single CAS field is updated at a high rate from multiple threads.
    private static long defaultResolutionNanos = DEFAULT_RESOLUTION_NANOS;

    // used in tests to disable the optimization and instead use a consistent view of the tokens
    public static void switchToConsistentTokensView() {
        defaultResolutionNanos = 0;
    }

    public static void resetToDefaultEventualConsistentTokensView() {
        defaultResolutionNanos = DEFAULT_RESOLUTION_NANOS;
    }

    // atomic field updaters for the volatile fields in this class

    private static final AtomicLongFieldUpdater<AsyncTokenBucket> LAST_NANOS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(AsyncTokenBucket.class, "lastNanos");

    private static final AtomicLongFieldUpdater<AsyncTokenBucket> LAST_INCREMENT_UPDATER =
            AtomicLongFieldUpdater.newUpdater(AsyncTokenBucket.class, "lastIncrement");

    private static final AtomicLongFieldUpdater<AsyncTokenBucket> TOKENS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(AsyncTokenBucket.class, "tokens");

    private static final AtomicLongFieldUpdater<AsyncTokenBucket> REMAINDER_NANOS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(AsyncTokenBucket.class, "remainderNanos");

    /**
     * This field represents the number of tokens in the bucket. It is eventually consistent, as the
     * pendingConsumedTokens are subtracted from the total number of tokens at most once during each "tick" or
     * "increment", when time advances according to the configured resolution.
     */
    protected volatile long tokens;
    /**
     * This field represents the last time the tokens were updated, in nanoseconds.
     * The configured clockSource is used to obtain the current nanoseconds.
     * By default, a monotonic clock (System.nanoTime()) is used.
     */
    private volatile long lastNanos;
    /**
     * This field represents the last time the tokens were updated, in increments.
     */
    private volatile long lastIncrement;
    /**
     * As time progresses, tokens are added to the bucket. When the rate is low, significant rounding errors could
     * accumulate over time if the remainder nanoseconds are not accounted for in the calculations. This field is used
     * to carry forward the leftover nanoseconds in the update calculation.
     */
    private volatile long remainderNanos;

    /**
     * The resolution in nanoseconds. This is the amount of time that must pass before the tokens are updated.
     */
    protected final long resolutionNanos;
    /**
     * This field is used to obtain the current monotonic clock time in nanoseconds.
     */
    private final MonotonicClockSource clockSource;
    /**
     * This field is used to hold the sum of consumed tokens that are pending to be subtracted from the total amount of
     * tokens. This solution is to prevent CAS loop contention problem. pendingConsumedTokens used JVM's LongAdder
     * which has a complex solution to prevent the CAS loop content problem.
     */
    private final LongAdder pendingConsumedTokens = new LongAdder();

    /**
     * A subclass of {@link AsyncTokenBucket} that represents a token bucket with a rate which is final.
     * The rate and capacity of the token bucket are constant and do not change over time.
     */
    private static class FinalRateAsyncTokenBucket extends AsyncTokenBucket {
        private final long capacity;
        private final long rate;
        private final long ratePeriodNanos;
        private final long targetAmountOfTokensAfterThrottling;

        protected FinalRateAsyncTokenBucket(long capacity, long rate, MonotonicClockSource clockSource,
                                            long ratePeriodNanos, long resolutionNanos, long initialTokens) {
            super(clockSource, resolutionNanos);
            this.capacity = capacity;
            this.rate = rate;
            this.ratePeriodNanos = ratePeriodNanos != -1 ? ratePeriodNanos : ONE_SECOND_NANOS;
            // The target amount of tokens is the amount of tokens made available in the resolution duration
            this.targetAmountOfTokensAfterThrottling = Math.max(this.resolutionNanos * rate / ratePeriodNanos, 1);
            this.tokens = initialTokens;
            tokens(false);
        }

        @Override
        protected final long getRatePeriodNanos() {
            return ratePeriodNanos;
        }

        @Override
        protected final long getTargetAmountOfTokensAfterThrottling() {
            return targetAmountOfTokensAfterThrottling;
        }

        @Override
        public final long getCapacity() {
            return capacity;
        }

        @Override
        public final long getRate() {
            return rate;
        }
    }

    protected AsyncTokenBucket(MonotonicClockSource clockSource, long resolutionNanos) {
        this.clockSource = clockSource;
        this.resolutionNanos = resolutionNanos;
    }

    public static FinalRateAsyncTokenBucketBuilder builder() {
        return new FinalRateAsyncTokenBucketBuilder();
    }

    public static DynamicRateAsyncTokenBucketBuilder builderForDynamicRate() {
        return new DynamicRateAsyncTokenBucketBuilder();
    }

    protected abstract long getRatePeriodNanos();

    protected abstract long getTargetAmountOfTokensAfterThrottling();

    /**
     * Consumes tokens and possibly updates the tokens balance. New tokens are calculated and added to the current
     * tokens balance each time the update takes place. The update takes place once in every interval of the configured
     * resolutionNanos or when the forceUpdateTokens parameter is true.
     * When the tokens balance isn't updated, the consumed tokens are added to the pendingConsumedTokens LongAdder
     * counter which gets flushed the next time the tokens are updated. This makes the tokens balance
     * eventually consistent. The reason for this design choice is to optimize performance by preventing CAS loop
     * contention which could cause excessive CPU consumption.
     *
     * @param consumeTokens     number of tokens to consume, can be 0 to update the tokens balance
     * @param forceUpdateTokens if true, the tokens are updated even if the configured resolution hasn't passed
     * @return the current number of tokens in the bucket or Long.MIN_VALUE when the number of tokens is unknown due
     * to eventual consistency
     */
    private long consumeTokensAndMaybeUpdateTokensBalance(long consumeTokens, boolean forceUpdateTokens) {
        if (consumeTokens < 0) {
            throw new IllegalArgumentException("consumeTokens must be >= 0");
        }
        long currentNanos = clockSource.getNanos(forceUpdateTokens);
        // check if the tokens should be updated immediately
        if (shouldUpdateTokensImmediately(currentNanos, forceUpdateTokens)) {
            // calculate the number of new tokens since the last update
            long newTokens = calculateNewTokensSinceLastUpdate(currentNanos);
            // calculate the total amount of tokens to consume in this update
            // flush the pendingConsumedTokens by calling "sumThenReset"
            long totalConsumedTokens = consumeTokens + pendingConsumedTokens.sumThenReset();
            // update the tokens and return the current token value
            return TOKENS_UPDATER.updateAndGet(this,
                    currentTokens ->
                            // after adding new tokens, limit the tokens to the capacity
                            Math.min(currentTokens + newTokens, getCapacity())
                                    // subtract the consumed tokens
                                    - totalConsumedTokens);
        } else {
            // eventual consistent fast path, tokens are not updated immediately

            // add the consumed tokens to the pendingConsumedTokens LongAdder counter
            if (consumeTokens > 0) {
                pendingConsumedTokens.add(consumeTokens);
            }

            // return Long.MIN_VALUE if the current value of tokens is unknown due to the eventual consistency
            return Long.MIN_VALUE;
        }
    }

    /**
     * Check if the tokens should be updated immediately.
     *
     * The tokens will be updated once every resolutionNanos nanoseconds.
     * This method checks if the configured resolutionNanos has passed since the last update.
     * If the forceUpdateTokens is true, the tokens will be updated immediately.
     *
     * @param currentNanos the current monotonic clock time in nanoseconds
     * @param forceUpdateTokens if true, the tokens will be updated immediately
     * @return true if the tokens should be updated immediately, false otherwise
     */
    private boolean shouldUpdateTokensImmediately(long currentNanos, boolean forceUpdateTokens) {
        long currentIncrement = resolutionNanos != 0 ? currentNanos / resolutionNanos : 0;
        long currentLastIncrement = lastIncrement;
        return currentIncrement == 0
                || (currentIncrement > currentLastIncrement
                && LAST_INCREMENT_UPDATER.compareAndSet(this, currentLastIncrement, currentIncrement))
                || forceUpdateTokens;
    }

    /**
     * Calculate the number of new tokens since the last update.
     * This will carry forward the remainder nanos so that a possible rounding error is eliminated.
     *
     * @param currentNanos the current monotonic clock time in nanoseconds
     * @return the number of new tokens to add since the last update
     */
    private long calculateNewTokensSinceLastUpdate(long currentNanos) {
        long newTokens;
        long previousLastNanos = LAST_NANOS_UPDATER.getAndSet(this, currentNanos);
        if (previousLastNanos == 0) {
            newTokens = 0;
        } else {
            long durationNanos = currentNanos - previousLastNanos + REMAINDER_NANOS_UPDATER.getAndSet(this, 0);
            long currentRate = getRate();
            long currentRatePeriodNanos = getRatePeriodNanos();
            // new tokens is the amount of tokens that are created in the duration since the last update
            // with the configured rate
            newTokens = (durationNanos * currentRate) / currentRatePeriodNanos;
            // carry forward the remainder nanos so that the rounding error is eliminated
            long remainderNanos = durationNanos - ((newTokens * currentRatePeriodNanos) / currentRate);
            if (remainderNanos > 0) {
                REMAINDER_NANOS_UPDATER.addAndGet(this, remainderNanos);
            }
        }
        return newTokens;
    }

    /**
     * Eventually consume tokens from the bucket.
     * The number of tokens is eventually consistent with the configured granularity of resolutionNanos.
     *
     * @param consumeTokens the number of tokens to consume
     */
    public void consumeTokens(long consumeTokens) {
        consumeTokensAndMaybeUpdateTokensBalance(consumeTokens, false);
    }

    /**
     * Eventually consume tokens from the bucket and check if tokens remain available.
     * The number of tokens is eventually consistent with the configured granularity of resolutionNanos.
     * Therefore, the returned result is not definite.
     *
     * @param consumeTokens the number of tokens to consume
     * @return true if there is tokens remains, false if tokens are all consumed. The answer isn't definite since the
     * comparison is made with eventually consistent token value.
     */
    public boolean consumeTokensAndCheckIfContainsTokens(long consumeTokens) {
        long currentTokens = consumeTokensAndMaybeUpdateTokensBalance(consumeTokens, false);
        if (currentTokens > 0) {
            // tokens remain in the bucket
            return true;
        } else if (currentTokens == Long.MIN_VALUE) {
            // when currentTokens is Long.MIN_VALUE, the current tokens balance is unknown since consumed tokens
            // was added to the pendingConsumedTokens LongAdder counter. In this case, assume that tokens balance
            // hasn't been updated yet and calculate a best guess of the current value by substracting the consumed
            // tokens from the current tokens balance
            return tokens - consumeTokens > 0;
        } else {
            // no tokens remain in the bucket
            return false;
        }
    }

    /**
     * Returns the current token balance. When forceUpdateTokens is true, the tokens balance is updated before
     * returning. If forceUpdateTokens is false, the tokens balance could be updated if the last updated happened
     * more than resolutionNanos nanoseconds ago.
     *
     * @param forceUpdateTokens if true, the tokens balance is updated before returning
     * @return the current token balance
     */
    protected long tokens(boolean forceUpdateTokens) {
        long currentTokens = consumeTokensAndMaybeUpdateTokensBalance(0, forceUpdateTokens);
        if (currentTokens != Long.MIN_VALUE) {
            // when currentTokens isn't Long.MIN_VALUE, the current tokens balance is known
            return currentTokens;
        } else {
            // return the current tokens balance, ignore the possible pendingConsumedTokens LongAdder counter
            return tokens;
        }
    }

    /**
     * Calculate the required throttling duration in nanoseconds to fill up the bucket with the minimum amount of
     * tokens.
     * This method shouldn't be called from the hot path since it calculates a consistent value for the tokens which
     * isn't necessary on the hotpath.
     */
    public long calculateThrottlingDuration() {
        long currentTokens = consumeTokensAndMaybeUpdateTokensBalance(0, true);
        if (currentTokens == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "Unexpected result from updateAndConsumeTokens with forceUpdateTokens set to true");
        }
        long needTokens = getTargetAmountOfTokensAfterThrottling() - currentTokens;
        if (needTokens <= 0) {
            return 0;
        }
        return (needTokens * getRatePeriodNanos()) / getRate();
    }

    public abstract long getCapacity();

    /**
     * Returns the current number of tokens in the bucket.
     * The token balance is updated if the configured resolutionNanos has passed since the last update.
     */
    public final long getTokens() {
        return tokens(false);
    }

    public abstract long getRate();

    /**
     * Checks if the bucket contains tokens.
     * The token balance is updated before the comparison if the configured resolutionNanos has passed since the last
     * update. It's possible that the returned result is not definite since the token balance is eventually consistent.
     *
     * @return true if the bucket contains tokens, false otherwise
     */
    public boolean containsTokens() {
        return containsTokens(false);
    }

    /**
     * Checks if the bucket contains tokens.
     * The token balance is updated before the comparison if the configured resolutionNanos has passed since the last
     * update. The token balance is also updated when forceUpdateTokens is true.
     * It's possible that the returned result is not definite since the token balance is eventually consistent.
     *
     * @param forceUpdateTokens if true, the token balance is updated before the comparison
     * @return true if the bucket contains tokens, false otherwise
     */
    public boolean containsTokens(boolean forceUpdateTokens) {
        return tokens(forceUpdateTokens) > 0;
    }

    // CHECKSTYLE.OFF: ClassTypeParameterName
    public abstract static class AsyncTokenBucketBuilder<SELF extends AsyncTokenBucketBuilder<SELF>> {
        protected MonotonicClockSource clockSource = DEFAULT_CLOCK_SOURCE;
        protected long resolutionNanos = defaultResolutionNanos;

        protected AsyncTokenBucketBuilder() {
        }

        protected SELF self() {
            return (SELF) this;
        }

        public SELF clockSource(MonotonicClockSource clockSource) {
            this.clockSource = clockSource;
            return self();
        }

        public SELF resolutionNanos(long resolutionNanos) {
            this.resolutionNanos = resolutionNanos;
            return self();
        }

        public abstract AsyncTokenBucket build();
    }

    /**
     * A builder class for creating instances of {@link FinalRateAsyncTokenBucket}.
     */
    public static class FinalRateAsyncTokenBucketBuilder
            extends AsyncTokenBucketBuilder<FinalRateAsyncTokenBucketBuilder> {
        protected Long capacity;
        protected Long initialTokens;
        protected Long rate;
        protected long ratePeriodNanos = ONE_SECOND_NANOS;

        protected FinalRateAsyncTokenBucketBuilder() {
        }

        public FinalRateAsyncTokenBucketBuilder rate(long rate) {
            this.rate = rate;
            return this;
        }

        public FinalRateAsyncTokenBucketBuilder ratePeriodNanos(long ratePeriodNanos) {
            this.ratePeriodNanos = ratePeriodNanos;
            return this;
        }

        public FinalRateAsyncTokenBucketBuilder capacity(long capacity) {
            this.capacity = capacity;
            return this;
        }

        public FinalRateAsyncTokenBucketBuilder initialTokens(long initialTokens) {
            this.initialTokens = initialTokens;
            return this;
        }

        public AsyncTokenBucket build() {
            return new FinalRateAsyncTokenBucket(this.capacity != null ? this.capacity : this.rate, this.rate,
                    this.clockSource,
                    this.ratePeriodNanos, this.resolutionNanos,
                    this.initialTokens != null ? this.initialTokens : this.rate
            );
        }
    }

    /**
     * A subclass of {@link AsyncTokenBucket} that represents a token bucket with a dynamic rate.
     * The rate and capacity of the token bucket can change over time based on the rate function and capacity factor.
     */
    public static class DynamicRateAsyncTokenBucket extends AsyncTokenBucket {
        private final LongSupplier rateFunction;
        private final LongSupplier ratePeriodNanosFunction;
        private final double capacityFactor;

        private final double targetFillFactorAfterThrottling;

        protected DynamicRateAsyncTokenBucket(double capacityFactor, LongSupplier rateFunction,
                                              MonotonicClockSource clockSource, LongSupplier ratePeriodNanosFunction,
                                              long resolutionNanos, double initialTokensFactor,
                                              double targetFillFactorAfterThrottling) {
            super(clockSource, resolutionNanos);
            this.capacityFactor = capacityFactor;
            this.rateFunction = rateFunction;
            this.ratePeriodNanosFunction = ratePeriodNanosFunction;
            this.targetFillFactorAfterThrottling = targetFillFactorAfterThrottling;
            this.tokens = (long) (rateFunction.getAsLong() * initialTokensFactor);
            tokens(false);
        }

        @Override
        protected long getRatePeriodNanos() {
            return ratePeriodNanosFunction.getAsLong();
        }

        @Override
        protected long getTargetAmountOfTokensAfterThrottling() {
            return (long) (getRate() * targetFillFactorAfterThrottling);
        }

        @Override
        public long getCapacity() {
            return capacityFactor == 1.0d ? getRate() : (long) (getRate() * capacityFactor);
        }

        @Override
        public long getRate() {
            return rateFunction.getAsLong();
        }
    }

    /**
     * A builder class for creating instances of {@link DynamicRateAsyncTokenBucket}.
     */
    public static class DynamicRateAsyncTokenBucketBuilder
            extends AsyncTokenBucketBuilder<DynamicRateAsyncTokenBucketBuilder> {
        protected LongSupplier rateFunction;
        protected double capacityFactor = 1.0d;
        protected double initialFillFactor = 1.0d;
        protected LongSupplier ratePeriodNanosFunction;
        protected double targetFillFactorAfterThrottling = 0.01d;

        protected DynamicRateAsyncTokenBucketBuilder() {
        }

        public DynamicRateAsyncTokenBucketBuilder rateFunction(LongSupplier rateFunction) {
            this.rateFunction = rateFunction;
            return this;
        }

        public DynamicRateAsyncTokenBucketBuilder ratePeriodNanosFunction(LongSupplier ratePeriodNanosFunction) {
            this.ratePeriodNanosFunction = ratePeriodNanosFunction;
            return this;
        }

        public DynamicRateAsyncTokenBucketBuilder capacityFactor(double capacityFactor) {
            this.capacityFactor = capacityFactor;
            return this;
        }

        public DynamicRateAsyncTokenBucketBuilder initialFillFactor(double initialFillFactor) {
            this.initialFillFactor = initialFillFactor;
            return this;
        }

        public DynamicRateAsyncTokenBucketBuilder targetFillFactorAfterThrottling(
                double targetFillFactorAfterThrottling) {
            this.targetFillFactorAfterThrottling = targetFillFactorAfterThrottling;
            return this;
        }

        @Override
        public AsyncTokenBucket build() {
            return new DynamicRateAsyncTokenBucket(this.capacityFactor, this.rateFunction,
                    this.clockSource,
                    this.ratePeriodNanosFunction, this.resolutionNanos,
                    this.initialFillFactor,
                    targetFillFactorAfterThrottling);
        }
    }

    /**
     * Interface for a clock source that returns a monotonic time in nanoseconds with a required precision.
     */
    public interface MonotonicClockSource {
        /**
         * Returns the current monotonic clock time in nanoseconds.
         *
         * @param highPrecision if true, the returned value must be a high precision monotonic time in nanoseconds.
         *                      if false, the returned value can be a granular precision monotonic time in nanoseconds.
         * @return the current monotonic clock time in nanoseconds
         */
        long getNanos(boolean highPrecision);
    }

    /**
     * A clock source that is optimized for performance by updating the returned low precision monotonic
     * time in a separate thread with a configurable resolution.
     * This resolves a performance bottleneck on platforms where calls to System.nanoTime() are relatively
     * costly. For example, this happens on MacOS with Apple silicon CPUs (M1,M2,M3).
     * Instantiating this class creates a daemon thread that updates the monotonic time. The close method
     * should be called to stop the thread.
     */
    public static class GranularMonotonicClockSource implements MonotonicClockSource, AutoCloseable {
        private final long sleepMillis;
        private final int sleepNanos;
        private final LongSupplier clockSource;
        private volatile long lastNanos;
        private volatile boolean closed;

        public GranularMonotonicClockSource(long granularityNanos, LongSupplier clockSource) {
            this.sleepMillis = TimeUnit.NANOSECONDS.toMillis(granularityNanos);
            this.sleepNanos = (int) (granularityNanos - TimeUnit.MILLISECONDS.toNanos(sleepMillis));
            this.clockSource = clockSource;
            this.lastNanos = clockSource.getAsLong();
            Thread thread = new Thread(this::updateLoop, getClass().getSimpleName() + "-update-loop");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public long getNanos(boolean highPrecision) {
            if (highPrecision) {
                long currentNanos = clockSource.getAsLong();
                lastNanos = currentNanos;
                return currentNanos;
            }
            return lastNanos;
        }

        private void updateLoop() {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                lastNanos = clockSource.getAsLong();
                try {
                    Thread.sleep(sleepMillis, sleepNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}