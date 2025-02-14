/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.rocksdb;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.Preconditions;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * It's a wrapper class around RocksDB's {@link WriteBatch} for writing in bulk.
 *
 * <p>IMPORTANT: This class is not thread safe.
 */
public class RocksDBWriteBatchWrapper implements AutoCloseable {

    private static final int MIN_CAPACITY = 100;
    private static final int MAX_CAPACITY = 1000;
    private static final int PER_RECORD_BYTES = 100;
    // default 0 for disable memory size based flush
    private static final long DEFAULT_BATCH_SIZE = 0;
    private static final int DEFAULT_CANCELLATION_CHECK_INTERVAL = MIN_CAPACITY;
    private static final int DEFAULT_CANCELLATION_CHECK_INTERVAL_BYTES =
            DEFAULT_CANCELLATION_CHECK_INTERVAL * PER_RECORD_BYTES;

    private final RocksDB db;

    private final WriteBatch batch;

    private final WriteOptions options;

    private final int capacity;

    @Nonnegative private final long batchSize;

    /** List of all objects that we need to close in close(). */
    private final List<AutoCloseable> toClose;

    private volatile boolean cancelled;

    private final int cancellationCheckInterval;

    private final long cancellationCheckIntervalBytes;

    private long lastCancellationCheckBatchSize = 0L;

    public RocksDBWriteBatchWrapper(@Nonnull RocksDB rocksDB, long writeBatchSize) {
        this(rocksDB, null, 500, writeBatchSize);
    }

    public RocksDBWriteBatchWrapper(@Nonnull RocksDB rocksDB, @Nullable WriteOptions options) {
        this(rocksDB, options, 500, DEFAULT_BATCH_SIZE);
    }

    public RocksDBWriteBatchWrapper(
            @Nonnull RocksDB rocksDB, @Nullable WriteOptions options, long batchSize) {
        this(rocksDB, options, 500, batchSize);
    }

    public RocksDBWriteBatchWrapper(
            @Nonnull RocksDB rocksDB,
            @Nullable WriteOptions options,
            int capacity,
            long batchSize) {
        this(
                rocksDB,
                options,
                capacity,
                batchSize,
                DEFAULT_CANCELLATION_CHECK_INTERVAL,
                DEFAULT_CANCELLATION_CHECK_INTERVAL_BYTES);
    }

    public RocksDBWriteBatchWrapper(
            @Nonnull RocksDB rocksDB,
            @Nullable WriteOptions options,
            int capacity,
            long batchSize,
            int cancellationCheckInterval,
            long cancellationCheckIntervalBytes) {
        Preconditions.checkArgument(
                capacity >= MIN_CAPACITY && capacity <= MAX_CAPACITY,
                "capacity should be between " + MIN_CAPACITY + " and " + MAX_CAPACITY);
        Preconditions.checkArgument(batchSize >= 0, "Max batch size have to be no negative.");

        this.db = rocksDB;
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.toClose = new ArrayList<>(2);
        if (this.batchSize > 0) {
            this.batch =
                    new WriteBatch(
                            (int) Math.min(this.batchSize, this.capacity * PER_RECORD_BYTES));
        } else {
            this.batch = new WriteBatch(this.capacity * PER_RECORD_BYTES);
        }
        this.toClose.add(this.batch);
        if (options != null) {
            this.options = options;
        } else {
            // Use default write options with disabled WAL
            this.options = new WriteOptions().setDisableWAL(true);
            // We own this object, so we must ensure that we close it.
            this.toClose.add(this.options);
        }
        this.cancellationCheckInterval = cancellationCheckInterval;
        this.cancellationCheckIntervalBytes = cancellationCheckIntervalBytes;
    }

    public void put(@Nonnull ColumnFamilyHandle handle, @Nonnull byte[] key, @Nonnull byte[] value)
            throws RocksDBException {
        maybeEnsureNotCancelled();

        batch.put(handle, key, value);

        flushIfNeeded();
    }

    private void maybeEnsureNotCancelled() {
        if (batch.count() % cancellationCheckInterval == 0
                || batch.getDataSize() - lastCancellationCheckBatchSize
                        >= cancellationCheckIntervalBytes) {
            ensureNotCancelled();
        }
    }

    public void remove(@Nonnull ColumnFamilyHandle handle, @Nonnull byte[] key)
            throws RocksDBException {

        batch.delete(handle, key);

        flushIfNeeded();
    }

    public void flush() throws RocksDBException {
        ensureNotCancelled();
        db.write(options, batch);
        batch.clear();
        lastCancellationCheckBatchSize = 0;
    }

    @VisibleForTesting
    WriteOptions getOptions() {
        return options;
    }

    public void markCancelled() {
        this.cancelled = true;
    }

    public Closeable getCancelCloseable() {
        return this::markCancelled;
    }

    @Override
    public void close() throws RocksDBException {
        try {
            ensureNotCancelled();
            if (batch.count() != 0) {
                flush();
            }
        } finally {
            IOUtils.closeAllQuietly(toClose);
        }
    }

    private void ensureNotCancelled() {
        if (cancelled) {
            throw new CancelTaskException();
        }
        lastCancellationCheckBatchSize = batch.getDataSize();
    }

    private void flushIfNeeded() throws RocksDBException {
        boolean needFlush =
                batch.count() == capacity || (batchSize > 0 && getDataSize() >= batchSize);
        if (needFlush) {
            flush();
        }
    }

    @VisibleForTesting
    long getDataSize() {
        return batch.getDataSize();
    }
}
