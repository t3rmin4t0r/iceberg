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

package com.netflix.iceberg;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.TypeUtil;
import com.netflix.iceberg.types.Types;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Schema evolution API implementation.
 */
class SchemaUpdate implements UpdateSchema {
  private static final int TABLE_ROOT_ID = -1;

  private final TableOperations ops;
  private final TableMetadata base;
  private final Schema schema;
  private final List<Integer> deletes = Lists.newArrayList();
  private final Map<Integer, Types.NestedField> updates = Maps.newHashMap();
  private final Multimap<Integer, Types.NestedField> adds =
      Multimaps.newListMultimap(Maps.newHashMap(), Lists::newArrayList);
  private int lastColumnId;

  SchemaUpdate(TableOperations ops) {
    this.ops = ops;
    this.base = ops.current();
    this.schema = base.schema();
    this.lastColumnId = base.lastColumnId();
  }

  /**
   * For testing only.
   */
  SchemaUpdate(Schema schema, int lastColumnId) {
    this.ops = null;
    this.base = null;
    this.schema = schema;
    this.lastColumnId = lastColumnId;
  }

  @Override
  public UpdateSchema addColumn(String name, Type type) {
    Preconditions.checkArgument(!name.contains("."),
        "Cannot add column with ambiguous name: %s, use addColumn(parent, name, type)", name);
    return addColumn(null, name, type);
  }

  @Override
  public UpdateSchema addColumn(String parent, String name, Type type) {
    int parentId = TABLE_ROOT_ID;
    if (parent != null) {
      Types.NestedField parentField = schema.findField(parent);
      Preconditions.checkArgument(parentField != null, "Cannot find parent struct: %s", parent);
      Type parentType = parentField.type();
      if (parentType.isNestedType()) {
        Type.NestedType nested = parentType.asNestedType();
        if (nested.isMapType()) {
          // fields are added to the map value type
          parentField = nested.asMapType().fields().get(1);
        } else if (nested.isListType()) {
          // fields are added to the element type
          parentField = nested.asListType().fields().get(0);
        }
      }
      Preconditions.checkArgument(
          parentField.type().isNestedType() && parentField.type().asNestedType().isStructType(),
          "Cannot add to non-struct column: %s: %s", parent, parentField.type());
      parentId = parentField.fieldId();
      Preconditions.checkArgument(!deletes.contains(parentId),
          "Cannot add to a column that will be deleted: %s", parent);
      Preconditions.checkArgument(schema.findField(parent + "." + name) == null,
          "Cannot add column, name already exists: " + parent + "." + name);
    } else {
      Preconditions.checkArgument(schema.findField(name) == null,
          "Cannot add column, name already exists: " + name);
    }

    // assign new IDs in order
    int newId = assignNewColumnId();
    adds.put(parentId, Types.NestedField.optional(newId, name,
        TypeUtil.reassignIds(type, this::assignNewColumnId)));

    return this;
  }

  @Override
  public UpdateSchema deleteColumn(String name) {
    Types.NestedField field = schema.findField(name);
    Preconditions.checkArgument(field != null, "Cannot delete missing column: %s", name);
    Preconditions.checkArgument(!adds.containsKey(field.fieldId()),
        "Cannot delete a column that has additions: %s", name);
    Preconditions.checkArgument(!updates.containsKey(field.fieldId()),
        "Cannot delete a column that has updates: %s", name);

    deletes.add(field.fieldId());

    return this;
  }

  @Override
  public UpdateSchema renameColumn(String name, String newName) {
    Types.NestedField field = schema.findField(name);
    Preconditions.checkArgument(field != null, "Cannot rename missing column: %s", name);
    Preconditions.checkArgument(!deletes.contains(field.fieldId()),
        "Cannot rename a column that will be deleted: %s", field.name());

    // merge with an update, if present
    int fieldId = field.fieldId();
    Types.NestedField update = updates.get(fieldId);
    if (update != null) {
      updates.put(fieldId, Types.NestedField.required(fieldId, newName, update.type()));
    } else {
      updates.put(fieldId, Types.NestedField.required(fieldId, newName, field.type()));
    }

    return this;
  }

  @Override
  public UpdateSchema updateColumn(String name, Type.PrimitiveType newType) {
    Types.NestedField field = schema.findField(name);
    Preconditions.checkArgument(field != null, "Cannot update missing column: %s", name);
    Preconditions.checkArgument(!deletes.contains(field.fieldId()),
        "Cannot update a column that will be deleted: %s", field.name());
    Preconditions.checkArgument(isPromotionAllowed(field.type(), newType),
        "Cannot change column type: %s: %s -> %s", name, field.type(), newType);

    // merge with a rename, if present
    int fieldId = field.fieldId();
    Types.NestedField rename = updates.get(fieldId);
    if (rename != null) {
      updates.put(fieldId, Types.NestedField.required(fieldId, rename.name(), newType));
    } else {
      updates.put(fieldId, Types.NestedField.required(fieldId, field.name(), newType));
    }

    return this;
  }

  /**
   * Apply the pending changes to the original schema and returns the result.
   * <p>
   * This does not result in a permanent update.
   *
   * @return the result Schema when all pending updates are applied
   */
  @Override
  public Schema apply() {
    return applyChanges(schema, deletes, updates, adds);
  }

  @Override
  public void commit() {
    TableMetadata update = base.updateSchema(apply(), lastColumnId);
    ops.commit(base, update);
  }

  private int assignNewColumnId() {
    int next = lastColumnId + 1;
    this.lastColumnId = next;
    return next;
  }

  private static boolean isPromotionAllowed(Type type, Type.PrimitiveType newType) {
    // Warning! Before changing this function, make sure that the type change doesn't introduce
    // compatibility problems in partitioning.
    if (type.equals(newType)) {
      return true;
    }

    switch (type.typeId()) {
      case INTEGER:
        return newType == Types.LongType.get();

      case FLOAT:
        return newType == Types.DoubleType.get();

      case DECIMAL:
        Types.DecimalType from = (Types.DecimalType) type;
        if (newType.typeId() != Type.TypeID.DECIMAL) {
          return false;
        }

        Types.DecimalType to = (Types.DecimalType) newType;
        return from.scale() == to.scale() && from.precision() <= to.precision();
    }
    return false;
  }

  private static Schema applyChanges(Schema schema, List<Integer> deletes,
                                     Map<Integer, Types.NestedField> updates,
                                     Multimap<Integer, Types.NestedField> adds) {
    Types.StructType struct = TypeUtil
        .visit(schema, new ApplyChanges(deletes, updates, adds))
        .asNestedType().asStructType();
    return new Schema(struct.fields());
  }

  private static class ApplyChanges extends TypeUtil.SchemaVisitor<Type> {
    private final List<Integer> deletes;
    private final Map<Integer, Types.NestedField> updates;
    private final Multimap<Integer, Types.NestedField> adds;

    private ApplyChanges(List<Integer> deletes,
                        Map<Integer, Types.NestedField> updates,
                        Multimap<Integer, Types.NestedField> adds) {
      this.deletes = deletes;
      this.updates = updates;
      this.adds = adds;
    }

    @Override
    public Type schema(Schema schema, Type structResult) {
      Collection<Types.NestedField> newColumns = adds.get(TABLE_ROOT_ID);
      if (newColumns != null) {
        return addFields(structResult.asNestedType().asStructType(), newColumns);
      }

      return structResult;
    }

    @Override
    public Type struct(Types.StructType struct, List<Type> fieldResults) {
      boolean hasChange = false;
      List<Types.NestedField> newFields = Lists.newArrayListWithExpectedSize(fieldResults.size());
      for (int i = 0; i < fieldResults.size(); i += 1) {
        Type resultType = fieldResults.get(i);
        if (resultType == null) {
          hasChange = true;
          continue;
        }

        Types.NestedField field = struct.fields().get(i);
        String name = field.name();
        Types.NestedField update = updates.get(field.fieldId());
        if (update != null && update.name() != null) {
          name = update.name();
        }

        if (!name.equals(field.name()) || field.type() != resultType) {
          hasChange = true;
          if (field.isOptional()) {
            newFields.add(Types.NestedField.optional(field.fieldId(), name, resultType));
          } else {
            newFields.add(Types.NestedField.required(field.fieldId(), name, resultType));
          }
        } else {
          newFields.add(field);
        }
      }

      if (hasChange) {
        // TODO: What happens if there are no fields left?
        return Types.StructType.of(newFields);
      }

      return struct;
    }

    @Override
    public Type field(Types.NestedField field, Type fieldResult) {
      // the API validates deletes, updates, and additions don't conflict
      int fieldId = field.fieldId();
      if (deletes.contains(fieldId)) {
        return null;
      }

      Types.NestedField update = updates.get(field.fieldId());
      if (update != null && update.type() != field.type()) {
        // rename is handled in struct
        return update.type();
      }

      Collection<Types.NestedField> newFields = adds.get(fieldId);
      if (newFields != null && !newFields.isEmpty()) {
        return addFields(fieldResult.asNestedType().asStructType(), newFields);
      }

      return fieldResult;
    }

    @Override
    public Type list(Types.ListType list, Type result) {
      // use field to apply updates
      Type elementResult = field(list.fields().get(0), result);
      if (elementResult == null) {
        throw new IllegalArgumentException("Cannot delete element type from list: " + list);
      }

      if (list.elementType() == elementResult) {
        return list;
      }

      if (list.isElementOptional()) {
        return Types.ListType.ofOptional(list.elementId(), elementResult);
      } else {
        return Types.ListType.ofRequired(list.elementId(), elementResult);
      }
    }

    @Override
    public Type map(Types.MapType map, Type result) {
      // use field to apply updates
      Type valueResult = field(map.fields().get(1), result);
      if (valueResult == null) {
        throw new IllegalArgumentException("Cannot delete value type from map: " + map);
      }

      if (map.valueType() == valueResult) {
        return map;
      }

      if (map.isValueOptional()) {
        return Types.MapType.ofOptional(map.keyId(), map.valueId(), valueResult);
      } else {
        return Types.MapType.ofRequired(map.keyId(), map.valueId(), valueResult);
      }
    }

    @Override
    public Type primitive(Type.PrimitiveType primitive) {
      return primitive;
    }
  }

  private static Types.StructType addFields(Types.StructType struct,
                                            Collection<Types.NestedField> adds) {
    List<Types.NestedField> newFields = Lists.newArrayList(struct.fields());
    newFields.addAll(adds);
    return Types.StructType.of(newFields);
  }
}
