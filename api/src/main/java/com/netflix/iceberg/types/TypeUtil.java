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

package com.netflix.iceberg.types;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.iceberg.Schema;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class TypeUtil {
  public static Schema select(Schema schema, Set<Integer> fieldIds) {
    Preconditions.checkNotNull(schema, "Schema cannot be null");
    Preconditions.checkNotNull(fieldIds, "Field ids cannot be null");

    Type result = visit(schema, new PruneColumns(fieldIds));
    if (schema.asStruct() == result) {
      return schema;
    } else if (result != null) {
      if (schema.getAliases() != null) {
        return new Schema(result.asNestedType().fields(), schema.getAliases());
      } else {
        return new Schema(result.asNestedType().fields());
      }
    }

    return new Schema(ImmutableList.of(), schema.getAliases());
  }

  public static Set<Integer> getProjectedIds(Schema schema) {
    return visit(schema, new GetProjectedIds());
  }

  public static Set<Integer> getProjectedIds(Type schema) {
    if (schema.isPrimitiveType()) {
      return ImmutableSet.of();
    }
    return ImmutableSet.copyOf(visit(schema, new GetProjectedIds()));
  }

  public static Schema selectNot(Schema schema, Set<Integer> fieldIds) {
    Set<Integer> projectedIds = getProjectedIds(schema);
    projectedIds.removeAll(fieldIds);
    return select(schema, projectedIds);
  }

  public static Schema join(Schema left, Schema right) {
    List<Types.NestedField> joinedColumns = Lists.newArrayList();
    joinedColumns.addAll(left.columns());
    joinedColumns.addAll(right.columns());
    return new Schema(joinedColumns);
  }

  public static Map<String, Integer> indexByName(Types.StructType struct) {
    return visit(struct, new IndexByName());
  }

  public static Map<Integer, Types.NestedField> indexById(Types.StructType struct) {
    return visit(struct, new IndexById());
  }

  public static Type reassignIds(Type type, NextID nextId) {
    return TypeUtil.visit(type, new ReassignIds(nextId));
  }

  public static Schema reassignIds(Schema schema, NextID nextId) {
    return new Schema(TypeUtil
        .visit(schema.asStruct(), new ReassignIds(nextId))
        .asNestedType()
        .fields());
  }

  /**
   * Interface for passing a function that assigns column IDs.
   */
  public interface NextID {
    int get();
  }

  public static class SchemaVisitor<T> {
    protected LinkedList<String> fieldNames = Lists.newLinkedList();

    public T schema(Schema schema, T structResult) {
      return null;
    }

    public T struct(Types.StructType struct, List<T> fieldResults) {
      return null;
    }

    public T field(Types.NestedField field, T fieldResult) {
      return null;
    }

    public T list(Types.ListType list, T elementResult) {
      return null;
    }

    public T map(Types.MapType map, T valueResult) {
      return null;
    }

    public T primitive(Type.PrimitiveType primitive) {
      return null;
    }
  }

  public static <T> T visit(Schema schema, SchemaVisitor<T> visitor) {
    return visitor.schema(schema, visit(schema.asStruct(), visitor));
  }

  public static <T> T visit(Type type, SchemaVisitor<T> visitor) {
    switch (type.typeId()) {
      case STRUCT:
        Types.StructType struct = type.asNestedType().asStructType();
        List<T> results = Lists.newArrayListWithExpectedSize(struct.fields().size());
        for (Types.NestedField field : struct.fields()) {
          visitor.fieldNames.push(field.name());
          T result;
          try {
            result = visit(field.type(), visitor);
          } finally {
            visitor.fieldNames.pop();
          }
          results.add(visitor.field(field, result));
        }
        return visitor.struct(struct, results);

      case LIST:
        Types.ListType list = type.asNestedType().asListType();
        return visitor.list(list, visit(list.elementType(), visitor));

      case MAP:
        Types.MapType map = type.asNestedType().asMapType();
        return visitor.map(map, visit(map.valueType(), visitor));

      default:
        return visitor.primitive(type.asPrimitiveType());
    }
  }

  public static class CustomOrderSchemaVisitor<T> {
    public T schema(Schema schema, Supplier<T> structResult) {
      return null;
    }

    public T struct(Types.StructType struct, Iterable<T> fieldResults) {
      return null;
    }

    public T field(Types.NestedField field, Supplier<T> fieldResult) {
      return null;
    }

    public T list(Types.ListType list, Supplier<T> elementResult) {
      return null;
    }

    public T map(Types.MapType map, Supplier<T> valueResult) {
      return null;
    }

    public T primitive(Type.PrimitiveType primitive) {
      return null;
    }
  }

  private static class VisitFuture<T> implements Supplier<T> {
    private final Type type;
    private final CustomOrderSchemaVisitor<T> visitor;

    private VisitFuture(Type type, CustomOrderSchemaVisitor<T> visitor) {
      this.type = type;
      this.visitor = visitor;
    }

    @Override
    public T get() {
      return visit(type, visitor);
    }
  }

  private static class VisitFieldFuture<T> implements Supplier<T> {
    private final Types.NestedField field;
    private final CustomOrderSchemaVisitor<T> visitor;

    private VisitFieldFuture(Types.NestedField field, CustomOrderSchemaVisitor<T> visitor) {
      this.field = field;
      this.visitor = visitor;
    }

    @Override
    public T get() {
      return visitor.field(field, new VisitFuture<>(field.type(), visitor));
    }
  }

  public static <T> T visit(Schema schema, CustomOrderSchemaVisitor<T> visitor) {
    return visitor.schema(schema, new VisitFuture<>(schema.asStruct(), visitor));
  }

  /**
   * Used to traverse types with traversals other than pre-order.
   * <p>
   * This passes a {@link Supplier} to each {@link CustomOrderSchemaVisitor visitor} method that
   * returns the result of traversing child types. Structs are passed an {@link Iterable} that
   * traverses child fields during iteration.
   * <p>
   * An example use is assigning column IDs, which should be done with a post-order traversal.
   *
   * @param type a type to traverse with a visitor
   * @param visitor a custom order visitor
   * @param <T> the type returned by the visitor
   * @return the result of traversing the given type with the visitor
   */
  public static <T> T visit(Type type, CustomOrderSchemaVisitor<T> visitor) {
    switch (type.typeId()) {
      case STRUCT:
        Types.StructType struct = type.asNestedType().asStructType();
        List<VisitFieldFuture<T>> results = Lists
            .newArrayListWithExpectedSize(struct.fields().size());
        for (Types.NestedField field : struct.fields()) {
          results.add(
              new VisitFieldFuture<>(field, visitor));
        }

        return visitor.struct(struct, Iterables.transform(results, VisitFieldFuture::get));

      case LIST:
        Types.ListType list = type.asNestedType().asListType();
        return visitor.list(list, new VisitFuture<>(list.elementType(), visitor));

      case MAP:
        Types.MapType map = type.asNestedType().asMapType();
        return visitor.map(map, new VisitFuture<>(map.valueType(), visitor));

      default:
        return visitor.primitive(type.asPrimitiveType());
    }
  }

  static int decimalMaxPrecision(int numBytes) {
    Preconditions.checkArgument(numBytes >= 0 && numBytes < 24,
        "Unsupported decimal length: " + numBytes);
    return MAX_PRECISION[numBytes];
  }

  public static int decimalRequriedBytes(int precision) {
    Preconditions.checkArgument(precision >= 0 && precision < 40,
        "Unsupported decimal precision: " + precision);
    return REQUIRED_LENGTH[precision];
  }

  private static int[] MAX_PRECISION = new int[24];
  private static int[] REQUIRED_LENGTH = new int[40];

  static {
    // for each length, calculate the max precision
    for (int len = 0; len < MAX_PRECISION.length; len += 1) {
      MAX_PRECISION[len] = (int) Math.floor(Math.log10(Math.pow(2, 8*len - 1) - 1));
    }

    // for each precision, find the first length that can hold it
    for (int precision = 0; precision < REQUIRED_LENGTH.length; precision += 1) {
      REQUIRED_LENGTH[precision] = -1;
      for (int len = 0; len < MAX_PRECISION.length; len += 1) {
        // find the first length that can hold the precision
        if (precision <= MAX_PRECISION[len]) {
          REQUIRED_LENGTH[precision] = len;
          break;
        }
      }
      if (REQUIRED_LENGTH[precision] < 0) {
        throw new IllegalStateException(
            "Could not find required length for precision " + precision);
      }
    }
  }
}
