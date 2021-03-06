/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import io.opentelemetry.api.common.Labels;
import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.metrics.accumulation.Accumulation;
import io.opentelemetry.sdk.metrics.aggregation.Aggregation;
import io.opentelemetry.sdk.metrics.common.InstrumentDescriptor;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@code InstrumentProcessor} represents an internal instance of an {@code Accumulator} for a
 * specific {code Instrument}. It records individual measurements (via the {@code Aggregator}). It
 * batches together {@code Aggregator}s for the similar sets of labels.
 *
 * <p>An entire collection cycle must be protected by a lock. A collection cycle is defined by
 * multiple calls to {@link #batch(Labels, Accumulation)} followed by one {@link
 * #completeCollectionCycle()};
 */
final class InstrumentProcessor<T extends Accumulation> {
  private final InstrumentDescriptor descriptor;
  private final Aggregation<T> aggregation;
  private final Resource resource;
  private final InstrumentationLibraryInfo instrumentationLibraryInfo;
  private final Clock clock;
  private Map<Labels, T> accumulationMap;
  private long startEpochNanos;
  private final boolean delta;

  /**
   * Create a InstrumentAccumulator that uses the "cumulative" Temporality and uses all labels for
   * aggregation. "Cumulative" means that all metrics that are generated will be considered for the
   * lifetime of the Instrument being aggregated.
   */
  static <T extends Accumulation> InstrumentProcessor<T> getCumulativeAllLabels(
      InstrumentDescriptor descriptor,
      MeterProviderSharedState meterProviderSharedState,
      MeterSharedState meterSharedState,
      Aggregation<T> aggregation) {
    return new InstrumentProcessor<>(
        descriptor,
        aggregation,
        meterProviderSharedState.getResource(),
        meterSharedState.getInstrumentationLibraryInfo(),
        meterProviderSharedState.getClock(),
        /* delta= */ false);
  }

  /**
   * Create a InstrumentAccumulator that uses the "delta" Temporality and uses all labels for
   * aggregation. "Delta" means that all metrics that are generated are only for the most recent
   * collection interval.
   */
  static <T extends Accumulation> InstrumentProcessor<T> getDeltaAllLabels(
      InstrumentDescriptor descriptor,
      MeterProviderSharedState meterProviderSharedState,
      MeterSharedState meterSharedState,
      Aggregation<T> aggregation) {
    return new InstrumentProcessor<>(
        descriptor,
        aggregation,
        meterProviderSharedState.getResource(),
        meterSharedState.getInstrumentationLibraryInfo(),
        meterProviderSharedState.getClock(),
        /* delta= */ true);
  }

  private InstrumentProcessor(
      InstrumentDescriptor descriptor,
      Aggregation<T> aggregation,
      Resource resource,
      InstrumentationLibraryInfo instrumentationLibraryInfo,
      Clock clock,
      boolean delta) {
    this.descriptor = descriptor;
    this.aggregation = aggregation;
    this.resource = resource;
    this.instrumentationLibraryInfo = instrumentationLibraryInfo;
    this.clock = clock;
    this.delta = delta;
    this.accumulationMap = new HashMap<>();
    startEpochNanos = clock.now();
  }

  /**
   * Batches multiple entries together that are part of the same metric. It may remove labels from
   * the {@link Labels} and merge aggregations together.
   *
   * @param labelSet the {@link Labels} associated with this {@code Aggregator}.
   * @param accumulation the {@link Accumulation} produced by this instrument.
   */
  void batch(Labels labelSet, T accumulation) {
    T currentAccumulation = accumulationMap.get(labelSet);
    if (currentAccumulation == null) {
      accumulationMap.put(labelSet, accumulation);
      return;
    }
    accumulationMap.put(labelSet, aggregation.merge(currentAccumulation, accumulation));
  }

  /**
   * Ends the current collection cycle and returns the list of metrics batched in this Batcher.
   *
   * <p>There may be more than one MetricData in case a multi aggregator is configured.
   *
   * <p>Based on the configured options this method may reset the internal state to produce deltas,
   * or keep the internal state to produce cumulative metrics.
   *
   * @return the list of metrics batched in this Batcher.
   */
  List<MetricData> completeCollectionCycle() {
    long epochNanos = clock.now();
    if (accumulationMap.isEmpty()) {
      return Collections.emptyList();
    }

    MetricData metricData =
        aggregation.toMetricData(
            resource,
            instrumentationLibraryInfo,
            descriptor,
            accumulationMap,
            startEpochNanos,
            epochNanos);

    if (delta) {
      startEpochNanos = epochNanos;
      accumulationMap = new HashMap<>();
    }

    return metricData == null ? Collections.emptyList() : Collections.singletonList(metricData);
  }

  Aggregation<T> getAggregation() {
    return this.aggregation;
  }

  /**
   * Returns whether this batcher generate "delta" style metrics. The alternative is "cumulative".
   */
  boolean generatesDeltas() {
    return delta;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    InstrumentProcessor<T> allLabels = (InstrumentProcessor<T>) o;

    if (startEpochNanos != allLabels.startEpochNanos) {
      return false;
    }
    if (delta != allLabels.delta) {
      return false;
    }
    if (!Objects.equals(descriptor, allLabels.descriptor)) {
      return false;
    }
    if (!Objects.equals(aggregation, allLabels.aggregation)) {
      return false;
    }
    if (!Objects.equals(resource, allLabels.resource)) {
      return false;
    }
    if (!Objects.equals(instrumentationLibraryInfo, allLabels.instrumentationLibraryInfo)) {
      return false;
    }
    if (!Objects.equals(clock, allLabels.clock)) {
      return false;
    }
    return Objects.equals(accumulationMap, allLabels.accumulationMap);
  }

  @Override
  public int hashCode() {
    int result = descriptor != null ? descriptor.hashCode() : 0;
    result = 31 * result + (aggregation != null ? aggregation.hashCode() : 0);
    result = 31 * result + (resource != null ? resource.hashCode() : 0);
    result =
        31 * result
            + (instrumentationLibraryInfo != null ? instrumentationLibraryInfo.hashCode() : 0);
    result = 31 * result + (clock != null ? clock.hashCode() : 0);
    result = 31 * result + (accumulationMap != null ? accumulationMap.hashCode() : 0);
    result = 31 * result + (int) (startEpochNanos ^ (startEpochNanos >>> 32));
    result = 31 * result + (delta ? 1 : 0);
    return result;
  }
}
