<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# RFC-2: Support for conversion of Deletion Vectors from Delta Lake to Iceberg format

## Proposers**

- @ashvin

## Status

GH Feature Request: https://github.com/apache/incubator-xtable/issues/339

## Abstract
Deletion vectors are one of the most popular features in table formats, allowing logical deletion of records, by marking
them as deleted, without requiring rewriting of the data files to remove them physically. This document provides a 
high-level proposal of deletion vector conversion in XTable, focusing on the current implementation of deletion vectors 
in Delta Lake and Iceberg.

## Background
Deletion vectors feature in table formats like **_Delta Lake_** and **_Apache Iceberg_** are designed to enhance data 
management efficiency. These vectors allow for the marking of deleted rows without physically removing them from the 
data files, thereby optimizing operations such as `DELETE`, `UPDATE`, and `MERGE`. By maintaining a separate files of 
deletions, deletion vectors enable faster query performance and reduce the need for costly data file rewrites. This 
approach aligns with the merge-on-read paradigm, where changes are merged during read operations rather than write 
operations, ensuring minimal disruption to ongoing data ingestion processes.

The main types of delete vectors are _**equality deletes**_ and _**position deletes**_. 

#### Equality deletes 
Equality deletes allow for row-level deletions based on specific column values rather than their positions within a 
file. This means that records in data files matching certain criteria are considered deleted without altering the 
original data files. This deletion vector type is useful when identifying and storing the exact position of the row(s) 
is expensive, but the content that identifies the row is clear. This feature is particularly useful in streaming use 
cases where updates are frequent. Currently, Iceberg supports equality deletes, however, there is a proposal to replace 
it in future versions.

#### Position deletes
Position deletes, on the other hand, specify the ordinals of records within a data file that should be considered 
deleted. This method relies on identifying the exact position within the data file, thereby providing a more precise and
direct approach to managing row-level deletions. Position deletes can be further categorized into two representations: 
simple table representations and compressed bitmap representations.

**_Simple table representations_** involve a straightforward structure typically stored in Parquet format, containing 
columns like `file_path` and `pos`. The `file_path` indicates the path of the data file, while `pos` specifies the 
ordinal of the record to be deleted. These files can optionally include the row data for equality deletes, aiding in 
identifying the exact change which is particularly useful in CDC applications.

A **_compressed bitmap representations_**, leverage a compressed bitmap to represent deletions. This method allows for a
more compact and optimized storage of delete vectors, which in turn saves memory and network bandwidth. The 
_RoaringBitmap_ is a popular choice for this representation. While the bitmaps are typically stored in separate files, 
in cases where the bitmaps are small because the number of deleted records is low, they can be embedded within the 
commit logs to optimize the number of network round trips required to load all the files.

#### Delta Lake and Iceberg
Currently, Delta Lake supports RoaringBitmaps for deletion vectors, while Iceberg supports simple table representations.
The java libraries of Delta Lake and Iceberg provide APIs to read and write between these representations. Delta Lake 
provides support for deletion vectors for `DELETE` operations since versions 2.4, while `UPDATE` and `MERGE` commands 
default to the copy-on-write mode.

In Delta Lake, the implementation of deletion vectors involves `actions` in the commit logs, namely `AddFile` and 
`RemoveFile`. When a DML command is executed, it identifies the rows to be modified and generates a deletion vector, 
typically in a compressed bitmap format like RoaringBitmap. The generated deletion vector is stored directly 
in the commit logs `json` files (when the number of deletions is minimal). When stored in separate files, the files 
paths are encoded and referenced in the json files. With each DML operation, the commit log records the removal of the 
original data file and its addition with updated details including the newly added deletion vector. 

Example:
```
Type       Path           DV Path
RemoveFile file_a.parquet NULL
AddFile    file_a.parquet file_a_dv_1.bin
```

If a file with an existing deletion vector is updated again, a new deletion vector is created, merging the previous and 
new deletions. The commit log records the removal of the old deletion vector information and the addition of the new 
one.

Example:
```
Type       Path           DV Path
RemoveFile file_a.parquet file_a_dv_1.bin
AddFile    file_a.parquet file_a_dv_2.bin
```

When a compaction operation rewrites records into new files, and deletion vectors associated with data files that are
removed are also marked as deleted. The commit log records the removal of the old file and deletion vector and 
the addition of the new compacted file.

Example:
```
Type       Path           DV Path
RemoveFile file_a.parquet file_a_dv_2.bin
AddFile    file_d.parquet NULL
```

## Implementation of the conversion of Deletion Vectors in XTable
On a high level, the implementation of deletion vector conversion in XTable involves the following steps. XTable must 
first locate the deletion vector, which can be stored either inline within the commit logs or can be stored in a 
separate deletion vector file whose path is recorded in the commit logs. Once located, XTable can utilize the utility 
methods provided by table format libraries to stream the ordinals of the records affected by the deletion operations.

Unlike currently supported conversion of metadata in XTable, deletion vectors require special handling. While all
table metadata is present in commit logs and manifest files, deletion vectors can be stored in separate files. The size
of the deletion vectors is proportional to the number of records deleted, which can be significant in large tables.
The proposed implementation aims to handle this efficiently by streaming the records from the sources to the targets.

**New Class: `InternalDeletionVector`**
To improve the functionality, a new class to manage the internal in-memory representation of deletion vectors is added.
This is similar to creating internal representation for data files, schema, and partitioning spec.

The following metadata is relevant for deletion vectors:
- `dataFilePath`: Path of the data file associated with the deletion vector.
- `size`: Size of the deletion vector.
- `countRecordsDeleted`: Number of records deleted.
- `sourceDeletionVectorFilePath`: Path of the deletion vector file (optional as deletion vectors can be stored inline).
- `offset`: Offset of the deletion vector within the file (optional as inline deletion vectors do not have an offset).
- `binaryRepresentation`: Binary representation of the inline deletion vector (optional).
- `ordinalStream`: Stream of ordinals of the records deleted.

**Modifications in `TableChange`**
Currently XTable only captures the addition and removal of data files. To support deletion vectors, the `TableChange`
class is updated to include the following fields a new field `deletionVectorsAdded` to capture deletion vectors added 
in a commit. The sources will need to parse the commit logs to extract the deletion vectors and populate this field.

**Updates in `DeltaActionsConverter`**
The converter class is responsible for converting Delta Lake `actions` into internal representations, resulting in
`TableChange` objects. The converter is updated to extract deletion vectors from the `AddFile` actions. Currently,
the converters read content from source metadata files and then close the file handles. However, this will be different
for deletion vectors. To avoid the potential memory explosion, the converters will create a provider of deletion vectors
ordinals, which will be used to stream the ordinals of the records deleted to the targets.

**Changes in `DeltaConversionSource`**
As described above, the delta commit logs adds data file entries in commit log each time delete vectors are updated. 
This needs special handling in the delta source, to avoid duplication of data file actions.

## Rollout/Adoption Plan

This change does not cause any breaking changes. Before this change, delete vectors were ignored and would cause
incorrect results in the target table. With this change, XTable will be able to convert the deletion vectors from Delta
Lake to Iceberg format, ensuring that the target table is consistent with the source table.

## Test Plan

New tests will be added to verify the conversion of deletion vectors from Delta Lake to Iceberg format. The integration
tests will cover generation of position deletes using spark sql, and the conversion of these deletes to Iceberg format. 