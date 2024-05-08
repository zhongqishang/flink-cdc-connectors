/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.qichacha.cdc.connectors.iceberg.sink;

import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.AlterColumnTypeEvent;
import org.apache.flink.cdc.common.event.CreateTableEvent;
import org.apache.flink.cdc.common.event.DropColumnEvent;
import org.apache.flink.cdc.common.event.RenameColumnEvent;
import org.apache.flink.cdc.common.event.SchemaChangeEvent;
import org.apache.flink.cdc.common.event.TableId;
import org.apache.flink.cdc.common.event.TruncateTableEvent;
import org.apache.flink.cdc.common.schema.Column;
import org.apache.flink.cdc.common.schema.Schema;
import org.apache.flink.cdc.common.sink.MetadataApplier;
import org.apache.flink.cdc.common.types.DataType;

import com.qichacha.cdc.connectors.iceberg.types.utils.DataTypeUtils;
import com.qichacha.cdc.connectors.iceberg.types.utils.FlinkCdcSchemaUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.flink.FlinkSchemaUtil;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A {@code MetadataApplier} that applies metadata changes to Iceberg. */
public class IcebergMetadataApplier implements MetadataApplier {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(IcebergMetadataApplier.class);

    private final CatalogLoader catalogLoader;
    private transient Catalog catalog;
    private boolean isOpened = false;

    public IcebergMetadataApplier(CatalogLoader catalogLoader) {
        this.catalogLoader = catalogLoader;
    }

    @Override
    public void applySchemaChange(SchemaChangeEvent schemaChangeEvent) {
        if (!isOpened) {
            catalog = catalogLoader.loadCatalog();
            isOpened = true;
        }

        try {
            if (schemaChangeEvent instanceof CreateTableEvent) {
                applyCreateTable((CreateTableEvent) schemaChangeEvent);
            } else if (schemaChangeEvent instanceof AddColumnEvent) {
                applyAddColumn((AddColumnEvent) schemaChangeEvent);
            } else if (schemaChangeEvent instanceof DropColumnEvent) {
                applyDropColumn((DropColumnEvent) schemaChangeEvent);
            } else if (schemaChangeEvent instanceof RenameColumnEvent) {
                applyRenameColumn((RenameColumnEvent) schemaChangeEvent);
            } else if (schemaChangeEvent instanceof AlterColumnTypeEvent) {
                applyAlterColumn((AlterColumnTypeEvent) schemaChangeEvent);
            } else if (schemaChangeEvent instanceof TruncateTableEvent) {
                applyTruncateTable((TruncateTableEvent) schemaChangeEvent);
            } else {
                throw new UnsupportedOperationException(
                        "IcebergDataSink doesn't support schema change event " + schemaChangeEvent);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void applyCreateTable(CreateTableEvent createTableEvent) {
        TableId tableId = createTableEvent.tableId();
        Schema schema = createTableEvent.getSchema();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());

        // validate schema
        if (catalog.tableExists(tableIdentifier)) {
            Table table = catalog.loadTable(tableIdentifier);
            Types.StructType struct = table.schema().asStruct();
            for (String columnName : createTableEvent.getSchema().getColumnNames()) {
                if (struct.fieldType(columnName) == null) {
                    LOG.warn("Column {} will not be found in iceberg schema.", columnName);
                }
            }
            return;
        }

        org.apache.iceberg.Schema icebergSchema = FlinkCdcSchemaUtil.convert(schema);
        try {
            catalog.createTable(
                    tableIdentifier,
                    icebergSchema,
                    PartitionSpec.unpartitioned(),
                    CatalogPropertiesUtils.getProperties(tableId.getSchemaName()));
        } catch (AlreadyExistsException e) {
            LOG.warn("Failed to apply create table, event: {}", createTableEvent, e);
        }
        LOG.info("Successful to apply create table, event: {}", createTableEvent);
    }

    private void applyAddColumn(AddColumnEvent addColumnEvent) {
        TableId tableId = addColumnEvent.tableId();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());
        Table loadTable = catalog.loadTable(tableIdentifier);

        // Filter not exists column
        List<AddColumnEvent.ColumnWithPosition> columnWithPositions =
                addColumnEvent.getAddedColumns().stream()
                        .filter(
                                addCol ->
                                        loadTable
                                                        .schema()
                                                        .asStruct()
                                                        .field(addCol.getAddColumn().getName())
                                                == null)
                        .collect(Collectors.toList());

        Transaction transaction = loadTable.newTransaction();
        UpdateSchema pendingUpdate = transaction.updateSchema();

        for (AddColumnEvent.ColumnWithPosition columnWithPosition : columnWithPositions) {
            // we will ignore position information, and always add the column to the last.
            // The reason is that ...
            Column column = columnWithPosition.getAddColumn();
            Type icebergType =
                    FlinkSchemaUtil.convert(
                            DataTypeUtils.toFlinkQccDataType(column.getType()).getLogicalType());
            if (column.getType().isNullable()) {
                pendingUpdate.addColumn(column.getName(), icebergType);
            } else {
                pendingUpdate.addRequiredColumn(column.getName(), icebergType);
            }
            AddColumnEvent.ColumnPosition position = columnWithPosition.getPosition();
            switch (position) {
                case BEFORE:
                    pendingUpdate.moveBefore(
                            column.getName(), columnWithPosition.getExistedColumnName());
                    break;
                case AFTER:
                    pendingUpdate.moveAfter(
                            column.getName(), columnWithPosition.getExistedColumnName());
                    break;
                case FIRST:
                    pendingUpdate.moveFirst(column.getName());
                    break;
                default:
                    break;
            }
        }
        pendingUpdate.commit();
        transaction.commitTransaction();
        LOG.info("Successful to apply add column, event: {}", addColumnEvent);
    }

    private void applyDropColumn(DropColumnEvent dropColumnEvent) {
        TableId tableId = dropColumnEvent.tableId();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());
        Table loadTable = catalog.loadTable(tableIdentifier);
        Transaction transaction = loadTable.newTransaction();
        UpdateSchema pendingUpdate = transaction.updateSchema();

        // Filter exists column
        List<String> columns =
                dropColumnEvent.getDroppedColumnNames().stream()
                        .filter(dropCol -> loadTable.schema().asStruct().fieldType(dropCol) != null)
                        .collect(Collectors.toList());

        for (String dropColumn : columns) {
            pendingUpdate.deleteColumn(dropColumn);
        }
        pendingUpdate.commit();
        transaction.commitTransaction();
        LOG.info("Successful to apply drop column, event: {}", dropColumnEvent);
    }

    private void applyRenameColumn(RenameColumnEvent renameColumnEvent) {
        TableId tableId = renameColumnEvent.tableId();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());
        Table loadTable = catalog.loadTable(tableIdentifier);
        Transaction transaction = loadTable.newTransaction();
        UpdateSchema pendingUpdate = transaction.updateSchema();

        // Filter exists column
        Map<String, String> columns =
                renameColumnEvent.getNameMapping().entrySet().stream()
                        .filter(
                                renameCol ->
                                        loadTable.schema().asStruct().field(renameCol.getKey())
                                                != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        columns.forEach(pendingUpdate::renameColumn);

        pendingUpdate.commit();
        transaction.commitTransaction();
        LOG.info("Successful to apply rename column, event: {}", renameColumnEvent);
    }

    private void applyAlterColumn(AlterColumnTypeEvent alterColumnTypeEvent) {
        TableId tableId = alterColumnTypeEvent.tableId();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());
        Table loadTable = catalog.loadTable(tableIdentifier);
        Transaction transaction = loadTable.newTransaction();
        UpdateSchema pendingUpdate = transaction.updateSchema();

        // Filter exists column
        Map<String, DataType> columns =
                alterColumnTypeEvent.getTypeMapping().entrySet().stream()
                        .filter(
                                renameCol ->
                                        loadTable.schema().asStruct().field(renameCol.getKey())
                                                != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (Map.Entry<String, DataType> renameColumn : columns.entrySet()) {
            String columnName = renameColumn.getKey();
            Type icebergType =
                    FlinkSchemaUtil.convert(
                            DataTypeUtils.toFlinkQccDataType(renameColumn.getValue())
                                    .getLogicalType());
            pendingUpdate.updateColumn(columnName, icebergType.asPrimitiveType());
            pendingUpdate.makeColumnOptional(columnName);
        }
        pendingUpdate.commit();
        transaction.commitTransaction();
        LOG.info("Successful to apply alter column, event: {}", alterColumnTypeEvent);
    }

    private void applyTruncateTable(TruncateTableEvent truncateTableEvent) {
        TableId tableId = truncateTableEvent.tableId();
        TableIdentifier tableIdentifier =
                TableIdentifier.of(tableId.getSchemaName(), tableId.getTableName());
        Table loadTable = catalog.loadTable(tableIdentifier);
        Transaction transaction = loadTable.newTransaction();
        transaction
                .newDelete()
                .set("app.id", "cdc truncate trigger")
                .deleteFromRowFilter(Expressions.alwaysTrue())
                .commit();
        transaction.commitTransaction();
    }
}