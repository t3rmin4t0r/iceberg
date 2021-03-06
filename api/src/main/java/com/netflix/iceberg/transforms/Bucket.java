/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.transforms;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.netflix.iceberg.expressions.BoundPredicate;
import com.netflix.iceberg.expressions.Expressions;
import com.netflix.iceberg.expressions.UnboundPredicate;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.Types;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.UUID;

import static com.netflix.iceberg.types.Type.TypeID;

abstract class Bucket<T> implements Transform<T, Integer> {
  private static final HashFunction MURMUR3 = Hashing.murmur3_32();

  @SuppressWarnings("unchecked")
  static <T> Bucket<T> get(Type type, int N) {
    switch (type.typeId()) {
      case DATE:
      case INTEGER:
        return (Bucket<T>) new BucketInteger(N);
      case TIME:
      case TIMESTAMP:
      case LONG:
        return (Bucket<T>) new BucketLong(N);
      case DECIMAL:
        return (Bucket<T>) new BucketDecimal(N);
      case STRING:
        return (Bucket<T>) new BucketString(N);
      case FIXED:
      case BINARY:
        return (Bucket<T>) new BucketByteBuffer(N);
      case UUID:
        return (Bucket<T>) new BucketUUID(N);
      default:
        throw new IllegalArgumentException("Cannot bucket by type: " + type);
    }
  }

  private final int N;

  private Bucket(int N) {
    this.N = N;
  }

  @VisibleForTesting
  abstract int hash(T value);

  @Override
  public Integer apply(T value) {
    return (hash(value) & Integer.MAX_VALUE) % N;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Bucket<?> bucket = (Bucket<?>) o;
    return N == bucket.N;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(N);
  }

  @Override
  public String toString() {
    return "bucket[" + N + "]";
  }

  @Override
  public UnboundPredicate<Integer> project(String name, BoundPredicate<T> predicate) {
    switch (predicate.op()) {
      case EQ:
        return Expressions.predicate(
            predicate.op(), name, apply(predicate.literal().value()));
//      case IN:
//        return Expressions.predicate();
      default:
        // comparison predicates can't be projected, notEq can't be projected
        return null;
    }
  }

  @Override
  public UnboundPredicate<Integer> projectStrict(String name, BoundPredicate<T> predicate) {
    switch (predicate.op()) {
      case NOT_EQ: // TODO: need to translate not(eq(...)) into notEq in expressions
        return Expressions.predicate(predicate.op(), name, apply(predicate.literal().value()));
//      case NOT_IN:
//        return null;
      default:
        // no strict projection for comparison or equality
        return null;
    }
  }

  @Override
  public Type getResultType(Type sourceType) {
    return Types.IntegerType.get();
  }

  private static class BucketInteger extends Bucket<Integer> {
    private BucketInteger(int N) {
      super(N);
    }

    public int hash(Integer value) {
      return MURMUR3.hashLong(value.longValue()).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.INTEGER || type.typeId() == TypeID.DATE;
    }
  }

  private static class BucketLong extends Bucket<Long> {
    private BucketLong(int N) {
      super(N);
    }

    public int hash(Long value) {
      return MURMUR3.hashLong(value).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return (
          type.typeId() == TypeID.LONG ||
          type.typeId() == TypeID.TIME ||
          type.typeId() == TypeID.TIMESTAMP
      );
    }
  }

  // bucketing by Double is not allowed by the spec, but this has the float hash implementation
  static class BucketFloat extends Bucket<Float> {
    // used by tests because the factory method will not instantiate a bucket function for floats
    BucketFloat(int N) {
      super(N);
    }

    public int hash(Float value) {
      return MURMUR3.hashLong(Double.doubleToRawLongBits((double) value)).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.FLOAT;
    }
  }

  // bucketing by Double is not allowed by the spec, but this has the double hash implementation
  static class BucketDouble extends Bucket<Double> {
    // used by tests because the factory method will not instantiate a bucket function for doubles
    BucketDouble(int N) {
      super(N);
    }

    public int hash(Double value) {
      return MURMUR3.hashLong(Double.doubleToRawLongBits(value)).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.DOUBLE;
    }
  }

  private static class BucketString extends Bucket<CharSequence> {
    private BucketString(int N) {
      super(N);
    }

    public int hash(CharSequence value) {
      return MURMUR3.hashString(value, Charsets.UTF_8).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.STRING;
    }
  }

  private static class BucketBytes extends Bucket<byte[]> {
    private static final Set<TypeID> SUPPORTED_TYPES = Sets.newHashSet(
        TypeID.BINARY, TypeID.FIXED);

    private BucketBytes(int N) {
      super(N);
    }

    public int hash(byte[] value) {
      return MURMUR3.hashBytes(value).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return SUPPORTED_TYPES.contains(type.typeId());
    }
  }

  private static class BucketByteBuffer extends Bucket<ByteBuffer> {
    private static final Set<TypeID> SUPPORTED_TYPES = Sets.newHashSet(
        TypeID.BINARY, TypeID.FIXED);

    private BucketByteBuffer(int N) {
      super(N);
    }

    public int hash(ByteBuffer value) {
      if (value.hasArray()) {
        return MURMUR3.hashBytes(value.array(),
            value.arrayOffset() + value.position(),
            value.arrayOffset() + value.remaining()).asInt();
      } else {
        int position = value.position();
        byte[] copy = new byte[value.remaining()];
        try {
          value.get(copy);
        } finally {
          // make sure the buffer position is unchanged
          value.position(position);
        }
        return MURMUR3.hashBytes(copy).asInt();
      }
    }

    @Override
    public boolean canTransform(Type type) {
      return SUPPORTED_TYPES.contains(type.typeId());
    }
  }

  private static class BucketUUID extends Bucket<UUID> {
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(() -> {
      ByteBuffer buffer = ByteBuffer.allocate(16);
      buffer.order(ByteOrder.BIG_ENDIAN);
      return buffer;
    });

    private BucketUUID(int N) {
      super(N);
    }

    public int hash(UUID value) {
      ByteBuffer buffer = BUFFER.get();
      buffer.rewind();
      buffer.putLong(value.getMostSignificantBits());
      buffer.putLong(value.getLeastSignificantBits());
      return MURMUR3.hashBytes(buffer.array()).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.UUID;
    }
  }

  private static class BucketDecimal extends Bucket<BigDecimal> {
    private BucketDecimal(int N) {
      super(N);
    }

    public int hash(BigDecimal value) {
      return MURMUR3.hashBytes(value.unscaledValue().toByteArray()).asInt();
    }

    @Override
    public boolean canTransform(Type type) {
      return type.typeId() == TypeID.DECIMAL;
    }
  }
}
