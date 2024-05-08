/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.common.event;

import java.util.Objects;

/** A {@link SchemaChangeEvent} that represents an {@code Truncate TABLE} DDL. */
public class TruncateTableEvent implements SchemaChangeEvent {

    private final TableId tableId;

    public TruncateTableEvent(TableId tableId) {
        this.tableId = tableId;
    }

    @Override
    public TableId tableId() {
        return tableId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TruncateTableEvent)) {
            return false;
        }
        TruncateTableEvent that = (TruncateTableEvent) o;
        return Objects.equals(tableId, that.tableId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableId);
    }

    @Override
    public String toString() {
        return "TruncateTableEvent{" + "tableId=" + tableId + "'";
    }
}