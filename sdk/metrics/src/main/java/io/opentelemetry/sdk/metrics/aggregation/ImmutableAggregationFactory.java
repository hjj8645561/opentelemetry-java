/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics.aggregation;

import io.opentelemetry.sdk.metrics.accumulation.Accumulation;
import io.opentelemetry.sdk.metrics.common.InstrumentValueType;
import javax.annotation.concurrent.Immutable;

@Immutable
final class ImmutableAggregationFactory<L extends Accumulation, D extends Accumulation>
    implements AggregationFactory {
  static final AggregationFactory SUM =
      new ImmutableAggregationFactory<>(
          SumAggregation.LONG_INSTANCE, SumAggregation.DOUBLE_INSTANCE);

  static final AggregationFactory COUNT =
      new ImmutableAggregationFactory<>(CountAggregation.INSTANCE, CountAggregation.INSTANCE);

  static final AggregationFactory LAST_VALUE =
      new ImmutableAggregationFactory<>(
          LastValueAggregation.LONG_INSTANCE, LastValueAggregation.DOUBLE_INSTANCE);

  static final AggregationFactory MIN_MAX_SUM_COUNT =
      new ImmutableAggregationFactory<>(
          MinMaxSumCountAggregation.LONG_INSTANCE, MinMaxSumCountAggregation.DOUBLE_INSTANCE);

  private final Aggregation<L> longAggregation;
  private final Aggregation<D> doubleAggregation;

  private ImmutableAggregationFactory(
      Aggregation<L> longAggregation, Aggregation<D> doubleAggregation) {
    this.longAggregation = longAggregation;
    this.doubleAggregation = doubleAggregation;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Accumulation> Aggregation<T> create(InstrumentValueType instrumentValueType) {
    switch (instrumentValueType) {
      case LONG:
        return (Aggregation<T>) longAggregation;
      case DOUBLE:
        return (Aggregation<T>) doubleAggregation;
    }
    throw new IllegalArgumentException("Invalid instrument value type");
  }
}
