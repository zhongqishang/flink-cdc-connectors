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

package org.apache.iceberg.flink.sink;

import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;

import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** IcebergEventStreamWriter. */
public class IcebergEventStreamWriter<T> extends AbstractStreamOperator<TableWriteResult>
        implements OneInputStreamOperator<T, TableWriteResult>, BoundedOneInput {

    private static final long serialVersionUID = 1L;

    private final TableIdentifier tableId;
    private TaskWriterFactory<T> taskWriterFactory;

    private transient TaskWriter<T> writer;
    private transient int subTaskId;
    private transient int attemptId;
    private transient IcebergStreamWriterMetrics writerMetrics;
    private transient boolean hasWriter = false;

    IcebergEventStreamWriter(TableIdentifier tableId) {
        this.tableId = tableId;
        setChainingStrategy(ChainingStrategy.ALWAYS);
    }

    public void setTaskWriterFactory(TaskWriterFactory<T> taskWriterFactory) {
        if (taskWriterFactory == null) {
            throw new RuntimeException("not get taskWriterFactory");
        }
        this.taskWriterFactory = taskWriterFactory;
        // Initialize the task writer factory.
        this.taskWriterFactory.initialize(subTaskId, attemptId);
        // Initialize the task writer.
        this.writer = taskWriterFactory.create();
        this.hasWriter = true;
    }

    public boolean hasWriter() {
        return hasWriter;
    }

    @Override
    public void open() {
        this.subTaskId = getRuntimeContext().getIndexOfThisSubtask();
        this.attemptId = getRuntimeContext().getAttemptNumber();
        this.writerMetrics =
                new IcebergStreamWriterMetrics(super.metrics, tableId.namespace() + tableId.name());

        // Initialize the task writer factory.
        this.taskWriterFactory.initialize(subTaskId, attemptId);

        // Initialize the task writer.
        this.writer = taskWriterFactory.create();
        this.hasWriter = true;
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        flush();
        this.writer = taskWriterFactory.create();
    }

    @Override
    public void processElement(StreamRecord<T> element) throws Exception {
        writer.write(element.getValue());
    }

    @Override
    public void close() throws Exception {
        super.close();
        if (writer != null) {
            writer.close();
            writer = null;
            hasWriter = false;
        }
    }

    @Override
    public void endInput() throws IOException {
        // For bounded stream, it may don't enable the checkpoint mechanism so we'd better to emit
        // the remaining completed files to downstream before closing the writer so that we won't
        // miss any of them.
        // Note that if the task is not closed after calling endInput, checkpoint may be triggered
        // again causing files to be sent repeatedly, the writer is marked as null after the last
        // file is sent to guard against duplicated writes.
        flush();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("tableId", tableId.toString())
                .add("subtask_id", subTaskId)
                .add("attempt_id", attemptId)
                .toString();
    }

    /** close all open files and emit files to downstream committer operator. */
    public void flush() throws IOException {
        if (writer == null) {
            return;
        }

        long startNano = System.nanoTime();
        WriteResult result = writer.complete();
        writerMetrics.updateFlushResult(result);
        output.collect(new StreamRecord<>(new TableWriteResult(tableId, result)));
        writerMetrics.flushDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano));

        // Set writer to null to prevent duplicate flushes in the corner case of
        // prepareSnapshotPreBarrier happening after endInput.
        writer = null;
        hasWriter = false;
    }
}