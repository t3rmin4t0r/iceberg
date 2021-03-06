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
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.netflix.iceberg.avro.Avro;
import com.netflix.iceberg.avro.AvroIterable;
import com.netflix.iceberg.expressions.Expression;
import com.netflix.iceberg.expressions.Expressions;
import com.netflix.iceberg.expressions.Projections;
import com.netflix.iceberg.io.InputFile;
import com.netflix.iceberg.types.Types;
import com.netflix.iceberg.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.netflix.iceberg.ManifestEntry.Status.DELETED;

/**
 * Reader for manifest files.
 * <p>
 * Readers are created using the builder from {@link #read(InputFile)}.
 */
public class ManifestReader implements Filterable<FilteredManifest> {
  private static final Logger LOG = LoggerFactory.getLogger(ManifestReader.class);

  private static final List<String> ALL_COLUMNS = Lists.newArrayList("*");
  private static final List<String> CHANGE_COLUNNS = Lists.newArrayList(
      "file_path", "file_format", "partition", "record_count", "file_size_in_bytes");

  /**
   * Returns a new builder to create a {@link ManifestReader} for an {@link InputFile}.
   *
   * @param file an InputFile
   * @return a manifest reader
   */
  public static ManifestReader read(InputFile file) {
    return new ManifestReader(file);
  }

  private final InputFile file;
  private final Map<String, String> metadata;
  private final PartitionSpec spec;
  private final Schema schema;

  // lazily initialized
  private List<ManifestEntry> adds = null;
  private List<ManifestEntry> deletes = null;

  private ManifestReader(InputFile file) {
    this.file = file;

    AvroIterable<ManifestEntry> headerReader = Avro.read(file)
        .project(ManifestEntry.getSchema(Types.StructType.of()).select("status"))
        .build();
    this.metadata = headerReader.getMetadata();
    this.schema = SchemaParser.fromJson(metadata.get("schema"));
    this.spec = PartitionSpecParser.fromJson(schema, metadata.get("partition-spec"));
  }

  public InputFile file() {
    return file;
  }

  public Schema schema() {
    return schema;
  }

  public PartitionSpec spec() {
    return spec;
  }

  @Override
  public Iterator<DataFile> iterator() {
    return iterator(Expressions.alwaysTrue(), ALL_COLUMNS);
  }

  @Override
  public FilteredManifest select(Collection<String> columns) {
    return new FilteredManifest(this, Expressions.alwaysTrue(), Lists.newArrayList(columns));
  }

  @Override
  public FilteredManifest filterPartitions(Expression expr) {
    return new FilteredManifest(this, expr, ALL_COLUMNS);
  }

  @Override
  public FilteredManifest filterRows(Expression expr) {
    return filterPartitions(Projections.inclusive(spec).project(expr));
  }

  public List<ManifestEntry> addedFiles() {
    if (adds == null) {
      cacheChanges();
    }
    return adds;
  }

  public List<ManifestEntry> deletedFiles() {
    if (deletes == null) {
      cacheChanges();
    }
    return deletes;
  }

  private void cacheChanges() {
    List<ManifestEntry> adds = Lists.newArrayList();
    List<ManifestEntry> deletes = Lists.newArrayList();

    for (ManifestEntry entry : entries(CHANGE_COLUNNS)) {
      switch (entry.status()) {
        case ADDED:
          adds.add(entry);
          break;
        case DELETED:
          deletes.add(entry);
          break;
        default:
      }
    }

    this.adds = adds;
    this.deletes = deletes;
  }

  Iterable<ManifestEntry> entries() {
    return entries(ALL_COLUMNS);
  }

  Iterable<ManifestEntry> entries(Collection<String> columns) {
    FileFormat format = FileFormat.fromFileName(file.toString());
    Preconditions.checkArgument(format != null, "Unable to determine format of manifest: " + file);

    Schema schema = ManifestEntry.projectSchema(spec.partitionType(), columns);
    switch (format) {
      case AVRO:
        return Avro.read(file)
            .project(schema)
            .rename("manifest_entry", ManifestEntry.class.getName())
            .rename("partition", PartitionData.class.getName())
            .rename("data_file", GenericDataFile.class.getName())
            .rename("column_sizes", Pair.class.getName())
            .rename("value_counts", Pair.class.getName())
            .rename("null_value_counts", Pair.class.getName())
            .rename("distinct_counts", Pair.class.getName())
            .reuseContainers()
            .build();

      default:
        throw new UnsupportedOperationException("Invalid format for manifest file: " + format);
    }
  }

  // visible for use by PartialManifest
  Iterator<DataFile> iterator(Expression filter, Collection<String> columns) {
    return Iterables.transform(Iterables.filter(
        entries(columns),
        entry -> entry.status() != DELETED),
        ManifestEntry::file).iterator();
  }

}
