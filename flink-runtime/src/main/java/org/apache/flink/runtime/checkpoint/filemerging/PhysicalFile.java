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

package org.apache.flink.runtime.checkpoint.filemerging;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.core.fs.FSDataOutputStream;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.CheckpointedStateScope;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** An abstraction of physical files in file-merging checkpoints. */
public class PhysicalFile {

    private static final Logger LOG = LoggerFactory.getLogger(PhysicalFile.class);

    /** Functional interface to delete the physical file. */
    @FunctionalInterface
    public interface PhysicalFileDeleter {
        /** Delete the file. */
        void perform(Path filePath, long size) throws IOException;
    }

    /** Functional interface to create the physical file. */
    @FunctionalInterface
    public interface PhysicalFileCreator {
        /** Create the file. */
        PhysicalFile perform(
                FileMergingSnapshotManager.SubtaskKey subtaskKey, CheckpointedStateScope scope)
                throws IOException;
    }

    /**
     * Output stream to the file, which keeps open for writing. It can be null if the file is
     * closed.
     */
    @Nullable private FSDataOutputStream outputStream;

    /** Reference count from the logical files. */
    private final AtomicInteger logicalFileRefCount;

    /** The size of this physical file. */
    private final AtomicLong size;

    /** The valid data size in this physical file. */
    private final AtomicLong dataSize;

    /**
     * Deleter that will be called when delete this physical file. If null, do not delete this
     * physical file.
     */
    @Nullable private final PhysicalFileDeleter deleter;

    private final Path filePath;

    private final CheckpointedStateScope scope;

    /**
     * If a physical file is closed, it means no more file segments will be written to the physical
     * file, and it can be deleted once its logicalFileRefCount decreases to 0.
     */
    private boolean closed;

    /**
     * A file can be deleted if: 1. It is closed, and 2. No more {@link LogicalFile}s have reference
     * on it.
     */
    private boolean deleted = false;

    /**
     * If a physical file is owned by current {@link FileMergingSnapshotManager}, the current {@link
     * FileMergingSnapshotManager} should not delete or count it if not owned.
     */
    private boolean isOwned;

    /** If this physical file could be further reused, considering the space amplification. */
    private boolean couldReuse;

    public PhysicalFile(
            @Nullable FSDataOutputStream outputStream,
            Path filePath,
            @Nullable PhysicalFileDeleter deleter,
            CheckpointedStateScope scope) {
        this(outputStream, filePath, deleter, scope, true);
    }

    public PhysicalFile(
            @Nullable FSDataOutputStream outputStream,
            Path filePath,
            @Nullable PhysicalFileDeleter deleter,
            CheckpointedStateScope scope,
            boolean owned) {
        this.filePath = filePath;
        this.outputStream = outputStream;
        this.closed = outputStream == null;
        this.deleter = deleter;
        this.scope = scope;
        this.size = new AtomicLong(0);
        this.dataSize = new AtomicLong(0);
        this.couldReuse = owned;
        this.logicalFileRefCount = new AtomicInteger(0);
        this.isOwned = owned;
    }

    @Nullable
    public FSDataOutputStream getOutputStream() {
        return outputStream;
    }

    void incRefCount() {
        int newValue = this.logicalFileRefCount.incrementAndGet();
        LOG.trace(
                "Increase the reference count of physical file: {} by 1. New value is: {}.",
                this.filePath,
                newValue);
    }

    void decRefCount() throws IOException {
        Preconditions.checkArgument(logicalFileRefCount.get() > 0);
        int newValue = this.logicalFileRefCount.decrementAndGet();
        LOG.trace(
                "Decrease the reference count of physical file: {} by 1. New value is: {}. ",
                this.filePath,
                newValue);
        deleteIfNecessary();
    }

    /**
     * Delete this physical file if there is no reference count from logical files (all discarded),
     * and this physical file is closed (no further writing on it).
     *
     * @throws IOException if anything goes wrong with file system.
     */
    public void deleteIfNecessary() throws IOException {
        synchronized (this) {
            if (!isOpen() && !deleted && this.logicalFileRefCount.get() <= 0) {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        LOG.warn("Fail to close output stream when deleting file: {}", filePath);
                    }
                }
                if (deleter != null && isOwned) {
                    deleter.perform(filePath, size.get());
                } else {
                    LOG.debug(
                            "Skip deleting this file {} because it is not owned by FileMergingManager.",
                            filePath);
                }
                this.deleted = true;
            }
        }
    }

    void incSize(long delta) {
        dataSize.addAndGet(delta);
        if (!closed) {
            size.addAndGet(delta);
        }
    }

    void decSize(long delta) {
        dataSize.addAndGet(-delta);
    }

    long getSize() {
        return size.get();
    }

    long wastedSize() {
        return size.get() - dataSize.get();
    }

    void updateSize(long updated) {
        size.set(updated);
    }

    boolean isCouldReuse() {
        return !closed || couldReuse;
    }

    /**
     * Check whether this physical file can be reused.
     *
     * @param maxAmp the max space amplification.
     * @return true if it can be further reused.
     */
    boolean checkReuseOnSpaceAmplification(float maxAmp) {
        if (!closed) {
            return true;
        }
        if (couldReuse) {
            if (dataSize.get() == 0L || dataSize.get() * maxAmp < size.get()) {
                couldReuse = false;
            }
        }
        return couldReuse;
    }

    @VisibleForTesting
    int getRefCount() {
        return logicalFileRefCount.get();
    }

    public boolean closed() {
        return closed;
    }

    public void close() throws IOException {
        innerClose();
        deleteIfNecessary();
    }

    /**
     * Close the physical file, stop reusing.
     *
     * @throws IOException if anything goes wrong with file system.
     */
    private void innerClose() throws IOException {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        }
    }

    /**
     * @return whether this physical file is still open for writing.
     */
    public boolean isOpen() {
        return !closed && outputStream != null;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Path getFilePath() {
        return filePath;
    }

    public CheckpointedStateScope getScope() {
        return scope;
    }

    public boolean isOwned() {
        return isOwned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PhysicalFile that = (PhysicalFile) o;
        return isOwned == that.isOwned && filePath.equals(that.filePath);
    }

    @Override
    public String toString() {
        return String.format(
                "Physical File: [%s], owned: %s, closed: %s, logicalFileRefCount: %d",
                filePath, isOwned, closed, logicalFileRefCount.get());
    }
}
