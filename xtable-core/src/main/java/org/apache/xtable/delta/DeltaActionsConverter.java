/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.delta;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import org.apache.hadoop.fs.Path;

import org.apache.spark.sql.delta.Snapshot;
import org.apache.spark.sql.delta.actions.AddFile;
import org.apache.spark.sql.delta.actions.DeletionVectorDescriptor;
import org.apache.spark.sql.delta.actions.RemoveFile;
import org.apache.spark.sql.delta.deletionvectors.RoaringBitmapArray;
import org.apache.spark.sql.delta.storage.dv.DeletionVectorStore;
import org.apache.spark.sql.delta.storage.dv.HadoopFileSystemDVStore;

import org.apache.xtable.exception.NotSupportedException;
import org.apache.xtable.model.schema.InternalField;
import org.apache.xtable.model.schema.InternalPartitionField;
import org.apache.xtable.model.stat.ColumnStat;
import org.apache.xtable.model.storage.FileFormat;
import org.apache.xtable.model.storage.InternalDataFile;
import org.apache.xtable.model.storage.InternalDeletionVector;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DeltaActionsConverter {

  private static final DeltaActionsConverter INSTANCE = new DeltaActionsConverter();

  public static DeltaActionsConverter getInstance() {
    return INSTANCE;
  }

  public InternalDataFile convertAddActionToInternalDataFile(
      AddFile addFile,
      Snapshot deltaSnapshot,
      FileFormat fileFormat,
      List<InternalPartitionField> partitionFields,
      List<InternalField> fields,
      boolean includeColumnStats,
      DeltaPartitionExtractor partitionExtractor,
      DeltaStatsExtractor fileStatsExtractor) {
    List<ColumnStat> columnStats =
        includeColumnStats
            ? fileStatsExtractor.getColumnStatsForFile(addFile, fields)
            : Collections.emptyList();
    long recordCount =
        columnStats.stream().map(ColumnStat::getNumValues).max(Long::compareTo).orElse(0L);
    // TODO(https://github.com/apache/incubator-xtable/issues/102): removed record count.
    return InternalDataFile.builder()
        .physicalPath(getFullPathToFile(deltaSnapshot, addFile.path()))
        .fileFormat(fileFormat)
        .fileSizeBytes(addFile.size())
        .lastModified(addFile.modificationTime())
        .partitionValues(
            partitionExtractor.partitionValueExtraction(addFile.partitionValues(), partitionFields))
        .columnStats(columnStats)
        .recordCount(recordCount)
        .build();
  }

  public InternalDataFile convertRemoveActionToInternalDataFile(
      RemoveFile removeFile,
      Snapshot deltaSnapshot,
      FileFormat fileFormat,
      List<InternalPartitionField> partitionFields,
      DeltaPartitionExtractor partitionExtractor) {
    return InternalDataFile.builder()
        .physicalPath(getFullPathToFile(deltaSnapshot, removeFile.path()))
        .fileFormat(fileFormat)
        .partitionValues(
            partitionExtractor.partitionValueExtraction(
                removeFile.partitionValues(), partitionFields))
        .build();
  }

  public FileFormat convertToFileFormat(String provider) {
    if (provider.equals("parquet")) {
      return FileFormat.APACHE_PARQUET;
    } else if (provider.equals("orc")) {
      return FileFormat.APACHE_ORC;
    }
    throw new NotSupportedException(
        String.format("delta file format %s is not recognized", provider));
  }

  static String getFullPathToFile(Snapshot snapshot, String dataFilePath) {
    String tableBasePath = snapshot.deltaLog().dataPath().toUri().toString();
    if (dataFilePath.startsWith(tableBasePath)) {
      return dataFilePath;
    }
    return tableBasePath + Path.SEPARATOR + dataFilePath;
  }

  /**
   * Extracts the representation of the deletion vector information corresponding to an AddFile
   * action. Currently, this method extracts and returns the path to the data file for which a
   * deletion vector data is present.
   *
   * @param snapshot the commit snapshot
   * @param addFile the add file action
   * @return the deletion vector representation, or null if no deletion vector is present
   */
  public InternalDeletionVector extractDeletionVector(Snapshot snapshot, AddFile addFile) {
    DeletionVectorDescriptor deletionVector = addFile.deletionVector();
    if (deletionVector == null) {
      return null;
    }

    String dataFilePath = addFile.path();
    dataFilePath = getFullPathToFile(snapshot, dataFilePath);
    Path deletionVectorFilePath = deletionVector.absolutePath(snapshot.deltaLog().dataPath());

    // TODO assumes deletion vector file. Need to handle inlined deletion vectors
    InternalDeletionVector deleteVector =
        InternalDeletionVector.builder()
            .dataFilePath(dataFilePath)
            .deletionVectorFilePath(deletionVectorFilePath.toString())
            .countRecordsDeleted(deletionVector.cardinality())
            .offset(getOffset(deletionVector))
            .length(deletionVector.sizeInBytes())
            .deleteRecordSupplier(() -> deletedRecordsIterator(snapshot, deletionVector))
            .build();

    return deleteVector;
  }

  private Iterator<Long> deletedRecordsIterator(
      Snapshot snapshot, DeletionVectorDescriptor deleteVector) {
    DeletionVectorStore dvStore =
        new HadoopFileSystemDVStore(snapshot.deltaLog().newDeltaHadoopConf());

    Path deletionVectorFilePath = deleteVector.absolutePath(snapshot.deltaLog().dataPath());
    int size = deleteVector.sizeInBytes();
    int offset = getOffset(deleteVector);
    RoaringBitmapArray rbm = dvStore.read(deletionVectorFilePath, offset, size);
    return Arrays.stream(rbm.values()).iterator();
  }

  private static int getOffset(DeletionVectorDescriptor deleteVector) {
    return deleteVector.offset().isDefined() ? (int) deleteVector.offset().get() : 1;
  }
}
