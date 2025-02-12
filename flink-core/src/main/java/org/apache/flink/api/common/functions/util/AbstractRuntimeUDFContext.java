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

package org.apache.flink.api.common.functions.util;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobInfo;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.api.common.accumulators.Accumulator;
import org.apache.flink.api.common.accumulators.AccumulatorHelper;
import org.apache.flink.api.common.accumulators.DoubleCounter;
import org.apache.flink.api.common.accumulators.Histogram;
import org.apache.flink.api.common.accumulators.IntCounter;
import org.apache.flink.api.common.accumulators.LongCounter;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.AggregatingState;
import org.apache.flink.api.common.state.AggregatingStateDescriptor;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReducingState;
import org.apache.flink.api.common.state.ReducingStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.fs.Path;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.util.UserCodeClassLoader;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Future;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** A standalone implementation of the {@link RuntimeContext}, created by runtime UDF operators. */
@Internal
public abstract class AbstractRuntimeUDFContext implements RuntimeContext {

    private final JobInfo jobInfo;

    private final TaskInfo taskInfo;

    private final UserCodeClassLoader userCodeClassLoader;

    private final ExecutionConfig executionConfig;

    private final Map<String, Accumulator<?, ?>> accumulators;

    private final DistributedCache distributedCache;

    private final OperatorMetricGroup metrics;

    public AbstractRuntimeUDFContext(
            JobInfo jobInfo,
            TaskInfo taskInfo,
            UserCodeClassLoader userCodeClassLoader,
            ExecutionConfig executionConfig,
            Map<String, Accumulator<?, ?>> accumulators,
            Map<String, Future<Path>> cpTasks,
            OperatorMetricGroup metrics) {
        this.jobInfo = checkNotNull(jobInfo);
        this.taskInfo = checkNotNull(taskInfo);
        this.userCodeClassLoader = userCodeClassLoader;
        this.executionConfig = executionConfig;
        this.distributedCache = new DistributedCache(checkNotNull(cpTasks));
        this.accumulators = checkNotNull(accumulators);
        this.metrics = metrics;
    }

    @Override
    public <T> TypeSerializer<T> createSerializer(TypeInformation<T> typeInformation) {
        return typeInformation.createSerializer(executionConfig.getSerializerConfig());
    }

    @Override
    public Map<String, String> getGlobalJobParameters() {
        return Collections.unmodifiableMap(executionConfig.getGlobalJobParameters().toMap());
    }

    @Override
    public boolean isObjectReuseEnabled() {
        return executionConfig.isObjectReuseEnabled();
    }

    @Override
    public OperatorMetricGroup getMetricGroup() {
        return metrics;
    }

    @Override
    public IntCounter getIntCounter(String name) {
        return (IntCounter) getAccumulator(name, IntCounter.class);
    }

    @Override
    public LongCounter getLongCounter(String name) {
        return (LongCounter) getAccumulator(name, LongCounter.class);
    }

    @Override
    public Histogram getHistogram(String name) {
        return (Histogram) getAccumulator(name, Histogram.class);
    }

    @Override
    public DoubleCounter getDoubleCounter(String name) {
        return (DoubleCounter) getAccumulator(name, DoubleCounter.class);
    }

    @Override
    public <V, A extends Serializable> void addAccumulator(
            String name, Accumulator<V, A> accumulator) {
        if (accumulators.containsKey(name)) {
            throw new UnsupportedOperationException(
                    "The accumulator '" + name + "' already exists and cannot be added.");
        }
        accumulators.put(name, accumulator);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V, A extends Serializable> Accumulator<V, A> getAccumulator(String name) {
        return (Accumulator<V, A>) accumulators.get(name);
    }

    @Override
    public ClassLoader getUserCodeClassLoader() {
        return this.userCodeClassLoader.asClassLoader();
    }

    @Override
    public void registerUserCodeClassLoaderReleaseHookIfAbsent(
            String releaseHookName, Runnable releaseHook) {
        userCodeClassLoader.registerReleaseHookIfAbsent(releaseHookName, releaseHook);
    }

    @Override
    public DistributedCache getDistributedCache() {
        return this.distributedCache;
    }

    @Override
    public JobInfo getJobInfo() {
        return jobInfo;
    }

    @Override
    public TaskInfo getTaskInfo() {
        return taskInfo;
    }

    // --------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <V, A extends Serializable> Accumulator<V, A> getAccumulator(
            String name, Class<? extends Accumulator<V, A>> accumulatorClass) {

        Accumulator<?, ?> accumulator = accumulators.get(name);

        if (accumulator != null) {
            AccumulatorHelper.compareAccumulatorTypes(
                    name, accumulator.getClass(), accumulatorClass);
        } else {
            // Create new accumulator
            try {
                accumulator = accumulatorClass.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Cannot create accumulator " + accumulatorClass.getName());
            }
            accumulators.put(name, accumulator);
        }
        return (Accumulator<V, A>) accumulator;
    }

    @Override
    @PublicEvolving
    public <T> ValueState<T> getState(ValueStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    @PublicEvolving
    public <T> ListState<T> getListState(ListStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    @PublicEvolving
    public <T> ReducingState<T> getReducingState(ReducingStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    @PublicEvolving
    public <IN, ACC, OUT> AggregatingState<IN, OUT> getAggregatingState(
            AggregatingStateDescriptor<IN, ACC, OUT> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    @PublicEvolving
    public <UK, UV> MapState<UK, UV> getMapState(MapStateDescriptor<UK, UV> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    public <T> org.apache.flink.api.common.state.v2.ValueState<T> getState(
            org.apache.flink.api.common.state.v2.ValueStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    public <T> org.apache.flink.api.common.state.v2.ListState<T> getListState(
            org.apache.flink.api.common.state.v2.ListStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    public <T> org.apache.flink.api.common.state.v2.ReducingState<T> getReducingState(
            org.apache.flink.api.common.state.v2.ReducingStateDescriptor<T> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    public <IN, ACC, OUT>
            org.apache.flink.api.common.state.v2.AggregatingState<IN, OUT> getAggregatingState(
                    org.apache.flink.api.common.state.v2.AggregatingStateDescriptor<IN, ACC, OUT>
                            stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Override
    public <UK, UV> org.apache.flink.api.common.state.v2.MapState<UK, UV> getMapState(
            org.apache.flink.api.common.state.v2.MapStateDescriptor<UK, UV> stateProperties) {
        throw new UnsupportedOperationException(
                "This state is only accessible by functions executed on a KeyedStream");
    }

    @Internal
    @VisibleForTesting
    public String getAllocationIDAsString() {
        return taskInfo.getAllocationIDAsString();
    }
}
