/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.opentelemetry.sdk.metrics.accumulation.Accumulation;
import io.opentelemetry.sdk.metrics.accumulation.MinMaxSumCountAccumulation;
import io.opentelemetry.sdk.metrics.aggregation.AggregationFactory;
import io.opentelemetry.sdk.metrics.common.InstrumentValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

class DoubleMinMaxSumCountAggregatorTest {
  @Test
  void createHandle() {
    assertThat(DoubleMinMaxSumCountAggregator.getInstance().createHandle())
        .isInstanceOf(DoubleMinMaxSumCountAggregator.Handle.class);
  }

  @Test
  void testRecordings() {
    AggregatorHandle<MinMaxSumCountAccumulation> aggregatorHandle =
        DoubleMinMaxSumCountAggregator.getInstance().createHandle();

    aggregatorHandle.recordDouble(100);
    assertThat(aggregatorHandle.accumulateThenReset())
        .isEqualTo(MinMaxSumCountAccumulation.create(1, 100, 100, 100));

    aggregatorHandle.recordDouble(200);
    assertThat(aggregatorHandle.accumulateThenReset())
        .isEqualTo(MinMaxSumCountAccumulation.create(1, 200, 200, 200));

    aggregatorHandle.recordDouble(-75);
    assertThat(aggregatorHandle.accumulateThenReset())
        .isEqualTo(MinMaxSumCountAccumulation.create(1, -75, -75, -75));
  }

  @Test
  void toAccumulationAndReset() {
    AggregatorHandle<MinMaxSumCountAccumulation> aggregatorHandle =
        DoubleMinMaxSumCountAggregator.getInstance().createHandle();
    assertThat(aggregatorHandle.accumulateThenReset()).isNull();

    aggregatorHandle.recordDouble(100);
    assertThat(aggregatorHandle.accumulateThenReset())
        .isEqualTo(MinMaxSumCountAccumulation.create(1, 100, 100, 100));
    assertThat(aggregatorHandle.accumulateThenReset()).isNull();

    aggregatorHandle.recordDouble(100);
    assertThat(aggregatorHandle.accumulateThenReset())
        .isEqualTo(MinMaxSumCountAccumulation.create(1, 100, 100, 100));
    assertThat(aggregatorHandle.accumulateThenReset()).isNull();
  }

  @Test
  void testMultithreadedUpdates() throws Exception {
    final AggregatorHandle<MinMaxSumCountAccumulation> aggregatorHandle =
        DoubleMinMaxSumCountAggregator.getInstance().createHandle();
    final Summary summarizer = new Summary();
    int numberOfThreads = 10;
    final double[] updates = new double[] {1, 2, 3, 5, 7, 11, 13, 17, 19, 23};
    final int numberOfUpdates = 1000;
    final CountDownLatch startingGun = new CountDownLatch(numberOfThreads);
    List<Thread> workers = new ArrayList<>();
    for (int i = 0; i < numberOfThreads; i++) {
      final int index = i;
      Thread t =
          new Thread(
              () -> {
                double update = updates[index];
                try {
                  startingGun.await();
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                for (int j = 0; j < numberOfUpdates; j++) {
                  aggregatorHandle.recordDouble(update);
                  if (ThreadLocalRandom.current().nextInt(10) == 0) {
                    summarizer.process(aggregatorHandle.accumulateThenReset());
                  }
                }
              });
      workers.add(t);
      t.start();
    }
    for (int i = 0; i <= numberOfThreads; i++) {
      startingGun.countDown();
    }

    for (Thread worker : workers) {
      worker.join();
    }
    // make sure everything gets merged when all the aggregation is done.
    summarizer.process(aggregatorHandle.accumulateThenReset());

    assertThat(summarizer.accumulation)
        .isEqualTo(
            MinMaxSumCountAccumulation.create(numberOfThreads * numberOfUpdates, 101000, 1, 23));
  }

  private static final class Summary {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @GuardedBy("lock")
    @Nullable
    private Accumulation accumulation;

    void process(@Nullable Accumulation other) {
      if (other == null) {
        return;
      }
      lock.writeLock().lock();
      try {
        if (accumulation == null) {
          accumulation = other;
          return;
        }
        accumulation =
            AggregationFactory.minMaxSumCount()
                .create(InstrumentValueType.DOUBLE)
                .merge(accumulation, other);
      } finally {
        lock.writeLock().unlock();
      }
    }
  }
}
