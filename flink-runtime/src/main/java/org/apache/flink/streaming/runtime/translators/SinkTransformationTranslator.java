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

package org.apache.flink.streaming.runtime.translators;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.SupportsConcurrentExecutionAttempts;
import org.apache.flink.api.common.operators.SlotSharingGroup;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SupportsCommitter;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.streaming.api.connector.sink2.CommittableMessage;
import org.apache.flink.streaming.api.connector.sink2.CommittableMessageTypeInfo;
import org.apache.flink.streaming.api.connector.sink2.StandardSinkTopologies;
import org.apache.flink.streaming.api.connector.sink2.SupportsPostCommitTopology;
import org.apache.flink.streaming.api.connector.sink2.SupportsPreCommitTopology;
import org.apache.flink.streaming.api.connector.sink2.SupportsPreWriteTopology;
import org.apache.flink.streaming.api.datastream.CustomSinkOperatorUidHashes;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.TransformationTranslator;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.streaming.api.transformations.PhysicalTransformation;
import org.apache.flink.streaming.api.transformations.SinkTransformation;
import org.apache.flink.streaming.api.transformations.StreamExchangeMode;
import org.apache.flink.streaming.runtime.operators.sink.CommitterOperatorFactory;
import org.apache.flink.streaming.runtime.operators.sink.SinkWriterOperatorFactory;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * A {@link org.apache.flink.streaming.api.graph.TransformationTranslator} for the {@link
 * SinkTransformation}.
 */
@Internal
public class SinkTransformationTranslator<Input, Output>
        implements TransformationTranslator<Output, SinkTransformation<Input, Output>> {

    @Override
    public Collection<Integer> translateForBatch(
            SinkTransformation<Input, Output> transformation, Context context) {
        return translateInternal(transformation, context, true);
    }

    @Override
    public Collection<Integer> translateForStreaming(
            SinkTransformation<Input, Output> transformation, Context context) {
        return translateInternal(transformation, context, false);
    }

    private Collection<Integer> translateInternal(
            SinkTransformation<Input, Output> transformation, Context context, boolean batch) {
        SinkExpander<Input> expander =
                new SinkExpander<>(
                        transformation.getInputStream(),
                        transformation.getSink(),
                        transformation,
                        context,
                        batch);
        expander.expand();
        return Collections.emptyList();
    }

    /**
     * Expands the FLIP-143 Sink to a subtopology. Each part of the topology is created after the
     * previous part of the topology has been completely configured by the user. For example, if a
     * user explicitly sets the parallelism of the sink, each part of the subtopology can rely on
     * the input having that parallelism.
     */
    private static class SinkExpander<T> {
        private final SinkTransformation<T, ?> transformation;
        private final Sink<T> sink;
        private final Context context;
        private final DataStream<T> inputStream;
        private final StreamExecutionEnvironment executionEnvironment;
        private final Optional<Integer> environmentParallelism;
        private final boolean isBatchMode;
        private final boolean isCheckpointingEnabled;

        public SinkExpander(
                DataStream<T> inputStream,
                Sink<T> sink,
                SinkTransformation<T, ?> transformation,
                Context context,
                boolean isBatchMode) {
            this.inputStream = inputStream;
            this.executionEnvironment = inputStream.getExecutionEnvironment();
            this.environmentParallelism =
                    executionEnvironment
                            .getConfig()
                            .toConfiguration()
                            .getOptional(CoreOptions.DEFAULT_PARALLELISM);
            this.isCheckpointingEnabled =
                    executionEnvironment.getCheckpointConfig().isCheckpointingEnabled();
            this.transformation = transformation;
            this.sink = sink;
            this.context = context;
            this.isBatchMode = isBatchMode;
        }

        private void expand() {

            final int sizeBefore = executionEnvironment.getTransformations().size();

            DataStream<T> prewritten = inputStream;

            if (sink instanceof SupportsPreWriteTopology) {
                prewritten =
                        adjustTransformations(
                                prewritten,
                                ((SupportsPreWriteTopology<T>) sink)::addPreWriteTopology,
                                true,
                                sink instanceof SupportsConcurrentExecutionAttempts);
            }

            if (sink instanceof SupportsPreCommitTopology) {
                Preconditions.checkArgument(
                        sink instanceof SupportsCommitter,
                        "Sink with SupportsPreCommitTopology should implement SupportsCommitter");
            }
            if (sink instanceof SupportsPostCommitTopology) {
                Preconditions.checkArgument(
                        sink instanceof SupportsCommitter,
                        "Sink with SupportsPostCommitTopology should implement SupportsCommitter");
            }

            if (sink instanceof SupportsCommitter) {
                addCommittingTopology(sink, prewritten);
            } else {
                adjustTransformations(
                        prewritten,
                        input ->
                                input.transform(
                                        ConfigConstants.WRITER_NAME,
                                        CommittableMessageTypeInfo.noOutput(),
                                        new SinkWriterOperatorFactory<>(sink)),
                        false,
                        sink instanceof SupportsConcurrentExecutionAttempts);
            }

            getSinkTransformations(sizeBefore).forEach(context::transform);

            repeatUntilConverged(
                    () ->
                            getSinkTransformations(sizeBefore).stream()
                                    .flatMap(t -> context.transform(t).stream())
                                    .collect(Collectors.toList()));

            disallowUnalignedCheckpoint(getSinkTransformations(sizeBefore));

            // Remove all added sink subtransformations to avoid duplications and allow additional
            // expansions
            while (executionEnvironment.getTransformations().size() > sizeBefore) {
                executionEnvironment
                        .getTransformations()
                        .remove(executionEnvironment.getTransformations().size() - 1);
            }
        }

        private <R> void repeatUntilConverged(Supplier<R> producer) {
            R lastResult = producer.get();
            R nextResult;
            while (!lastResult.equals(nextResult = producer.get())) {
                lastResult = nextResult;
            }
        }

        private List<Transformation<?>> getSinkTransformations(int sizeBefore) {
            return executionEnvironment
                    .getTransformations()
                    .subList(sizeBefore, executionEnvironment.getTransformations().size());
        }

        /**
         * Disables UC for all connections of operators within the sink expansion. This is necessary
         * because committables need to be at the respective operators on notifyCheckpointComplete
         * or else we can't commit all side-effects, which violates the contract of
         * notifyCheckpointComplete.
         */
        private void disallowUnalignedCheckpoint(List<Transformation<?>> sinkTransformations) {
            Optional<Transformation<?>> writerOpt =
                    sinkTransformations.stream().filter(SinkExpander::isWriter).findFirst();
            Preconditions.checkState(writerOpt.isPresent(), "Writer transformation not found.");
            Transformation<?> writer = writerOpt.get();
            int indexOfWriter = sinkTransformations.indexOf(writer);

            // check all transformation after the writer and recursively disable UC for all inputs
            // up to the writer
            Set<Integer> seen = new HashSet<>(sinkTransformations.size() * 2);
            seen.add(writer.getId());

            Queue<Transformation<?>> pending =
                    new ArrayDeque<>(
                            sinkTransformations.subList(
                                    indexOfWriter + 1, sinkTransformations.size()));

            while (!pending.isEmpty()) {
                Transformation<?> current = pending.poll();
                seen.add(current.getId());

                for (Transformation<?> input : current.getInputs()) {
                    if (input instanceof PartitionTransformation) {
                        ((PartitionTransformation<?>) input)
                                .getPartitioner()
                                .disableUnalignedCheckpoints();
                    }
                    if (seen.add(input.getId())) {
                        pending.add(input);
                    }
                }
            }
        }

        private static boolean isWriter(Transformation<?> t) {
            if (!(t instanceof OneInputTransformation)) {
                return false;
            }
            return ((OneInputTransformation<?, ?>) t).getOperatorFactory()
                    instanceof SinkWriterOperatorFactory;
        }

        private <CommT, WriteResultT> void addCommittingTopology(
                Sink<T> sink, DataStream<T> inputStream) {
            SupportsCommitter<CommT> committingSink = (SupportsCommitter<CommT>) sink;
            TypeInformation<CommittableMessage<CommT>> committableTypeInformation =
                    CommittableMessageTypeInfo.of(committingSink::getCommittableSerializer);

            DataStream<CommittableMessage<CommT>> precommitted;
            if (sink instanceof SupportsPreCommitTopology) {
                SupportsPreCommitTopology<WriteResultT, CommT> preCommittingSink =
                        (SupportsPreCommitTopology<WriteResultT, CommT>) sink;
                TypeInformation<CommittableMessage<WriteResultT>> writeResultTypeInformation =
                        CommittableMessageTypeInfo.of(preCommittingSink::getWriteResultSerializer);

                DataStream<CommittableMessage<WriteResultT>> writerResult =
                        addWriter(sink, inputStream, writeResultTypeInformation);

                precommitted =
                        adjustTransformations(
                                writerResult, preCommittingSink::addPreCommitTopology, true, false);
            } else {
                precommitted = addWriter(sink, inputStream, committableTypeInformation);
            }

            DataStream<CommittableMessage<CommT>> committed =
                    adjustTransformations(
                            precommitted,
                            pc ->
                                    pc.transform(
                                            ConfigConstants.COMMITTER_NAME,
                                            committableTypeInformation,
                                            new CommitterOperatorFactory<>(
                                                    committingSink,
                                                    isBatchMode,
                                                    isCheckpointingEnabled)),
                            false,
                            false);

            if (sink instanceof SupportsPostCommitTopology) {
                DataStream<CommittableMessage<CommT>> postcommitted = addFailOverRegion(committed);
                adjustTransformations(
                        postcommitted,
                        pc -> {
                            ((SupportsPostCommitTopology<CommT>) sink).addPostCommitTopology(pc);
                            return null;
                        },
                        true,
                        false);
            }
        }

        private <WriteResultT> DataStream<CommittableMessage<WriteResultT>> addWriter(
                Sink<T> sink,
                DataStream<T> inputStream,
                TypeInformation<CommittableMessage<WriteResultT>> typeInformation) {
            DataStream<CommittableMessage<WriteResultT>> written =
                    adjustTransformations(
                            inputStream,
                            input ->
                                    input.transform(
                                            ConfigConstants.WRITER_NAME,
                                            typeInformation,
                                            new SinkWriterOperatorFactory<>(sink)),
                            false,
                            sink instanceof SupportsConcurrentExecutionAttempts);

            return addFailOverRegion(written);
        }

        /**
         * Adds a batch exchange that materializes the output first. This is a no-op in STREAMING.
         */
        private <I> DataStream<I> addFailOverRegion(DataStream<I> input) {
            return new DataStream<>(
                    executionEnvironment,
                    new PartitionTransformation<>(
                            input.getTransformation(),
                            new ForwardPartitioner<>(),
                            StreamExchangeMode.BATCH));
        }

        /**
         * Since user may set specific parallelism on sub topologies, we have to pay attention to
         * the priority of parallelism at different levels, i.e. sub topologies customized
         * parallelism > sinkTransformation customized parallelism > environment customized
         * parallelism. In order to satisfy this rule and keep these customized parallelism values,
         * the environment parallelism will be set to be {@link ExecutionConfig#PARALLELISM_DEFAULT}
         * before adjusting transformations. SubTransformations, constructed after that, will have
         * either the default value or customized value. In this way, any customized value will be
         * discriminated from the default value and, for any subTransformation with the default
         * parallelism value, we will then be able to let it inherit the parallelism value from the
         * previous sinkTransformation. After the adjustment of transformations is closed, the
         * environment parallelism will be restored back to its original value to keep the
         * customized parallelism value at environment level.
         */
        private <I, R> R adjustTransformations(
                DataStream<I> inputStream,
                Function<DataStream<I>, R> action,
                boolean isExpandedTopology,
                boolean supportsConcurrentExecutionAttempts) {

            // Reset the environment parallelism temporarily before adjusting transformations,
            // we can therefore be aware of any customized parallelism of the sub topology
            // set by users during the adjustment.
            executionEnvironment.setParallelism(ExecutionConfig.PARALLELISM_DEFAULT);

            int numTransformsBefore = executionEnvironment.getTransformations().size();
            R result = action.apply(inputStream);
            List<Transformation<?>> transformations = executionEnvironment.getTransformations();
            List<Transformation<?>> expandedTransformations =
                    transformations.subList(numTransformsBefore, transformations.size());

            final CustomSinkOperatorUidHashes operatorsUidHashes =
                    transformation.getSinkOperatorsUidHashes();
            for (Transformation<?> subTransformation : expandedTransformations) {

                String subUid = subTransformation.getUid();
                if (isExpandedTopology && subUid != null && !subUid.isEmpty()) {
                    checkState(
                            transformation.getUid() != null && !transformation.getUid().isEmpty(),
                            "Sink "
                                    + transformation.getName()
                                    + " requires to set a uid since its customized topology"
                                    + " has set uid for some operators.");
                }

                // Set the operator uid hashes to support stateful upgrades without prior uids
                setOperatorUidHashIfPossible(
                        subTransformation,
                        ConfigConstants.WRITER_NAME,
                        operatorsUidHashes.getWriterUidHash());
                setOperatorUidHashIfPossible(
                        subTransformation,
                        ConfigConstants.COMMITTER_NAME,
                        operatorsUidHashes.getCommitterUidHash());
                setOperatorUidHashIfPossible(
                        subTransformation,
                        StandardSinkTopologies.GLOBAL_COMMITTER_TRANSFORMATION_NAME,
                        operatorsUidHashes.getGlobalCommitterUidHash());
                setAdditionalMetricVariablesForWriter(subTransformation);

                concatUid(subTransformation, subTransformation.getName());

                concatProperty(
                        subTransformation,
                        Transformation::getCoLocationGroupKey,
                        Transformation::setCoLocationGroupKey);

                concatProperty(subTransformation, Transformation::getName, Transformation::setName);

                concatProperty(
                        subTransformation,
                        Transformation::getDescription,
                        Transformation::setDescription);

                // handle coLocationGroupKey.
                String coLocationGroupKey = transformation.getCoLocationGroupKey();
                if (coLocationGroupKey != null
                        && subTransformation.getCoLocationGroupKey() == null) {
                    subTransformation.setCoLocationGroupKey(coLocationGroupKey);
                }

                Optional<SlotSharingGroup> ssg = transformation.getSlotSharingGroup();

                if (ssg.isPresent() && !subTransformation.getSlotSharingGroup().isPresent()) {
                    subTransformation.setSlotSharingGroup(ssg.get());
                }

                // remember that the environment parallelism has been set to be default
                // at the beginning. SubTransformations, whose parallelism has been
                // customized, will skip this part. The customized parallelism value set by user
                // will therefore be kept.
                if (subTransformation.getParallelism() == ExecutionConfig.PARALLELISM_DEFAULT) {
                    // In this case, the subTransformation does not contain any customized
                    // parallelism value and will therefore inherit the parallelism value
                    // from the sinkTransformation.
                    subTransformation.setParallelism(
                            transformation.getParallelism(),
                            transformation.isParallelismConfigured());
                }

                if (subTransformation.getMaxParallelism() < 0
                        && transformation.getMaxParallelism() > 0) {
                    subTransformation.setMaxParallelism(transformation.getMaxParallelism());
                }

                if (subTransformation instanceof PhysicalTransformation) {
                    PhysicalTransformation<?> physicalSubTransformation =
                            (PhysicalTransformation<?>) subTransformation;

                    if (transformation.getChainingStrategy() != null) {
                        physicalSubTransformation.setChainingStrategy(
                                transformation.getChainingStrategy());
                    }

                    // overrides the supportsConcurrentExecutionAttempts of transformation because
                    // it's not allowed to specify fine-grained concurrent execution attempts yet
                    physicalSubTransformation.setSupportsConcurrentExecutionAttempts(
                            supportsConcurrentExecutionAttempts);
                }
            }

            // Restore the previous parallelism of the environment before adjusting transformations
            if (environmentParallelism.isPresent()) {
                executionEnvironment.getConfig().setParallelism(environmentParallelism.get());
            } else {
                executionEnvironment.getConfig().resetParallelism();
            }

            return result;
        }

        /**
         * Only writer inherits additional metric variables, as writer's metrics like number of
         * records in, busyness, etc. are the most representative of "sink's" metrics.
         */
        private void setAdditionalMetricVariablesForWriter(Transformation<?> subTransformation) {
            Map<String, String> additionalMetricVariables =
                    transformation.getAdditionalMetricVariables();
            if (additionalMetricVariables != null
                    && subTransformation.getName().equals(ConfigConstants.WRITER_NAME)) {
                additionalMetricVariables.forEach(subTransformation::addMetricVariable);
            }
        }

        private void setOperatorUidHashIfPossible(
                Transformation<?> transformation,
                String writerName,
                @Nullable String operatorUidHash) {
            if (operatorUidHash == null || !transformation.getName().equals(writerName)) {
                return;
            }
            transformation.setUidHash(operatorUidHash);
        }

        private void concatUid(
                Transformation<?> subTransformation, @Nullable String transformationName) {
            if (transformationName != null && transformation.getUid() != null) {
                // Use the same uid pattern than for Sink V1. We deliberately decided to use the uid
                // pattern of Flink 1.13 because 1.14 did not have a dedicated committer operator.
                if (transformationName.equals(ConfigConstants.COMMITTER_NAME)) {
                    final String committerFormat = "Sink Committer: %s";
                    subTransformation.setUid(
                            String.format(committerFormat, transformation.getUid()));
                    return;
                }
                // Set the writer operator uid to the sinks uid to support state migrations
                if (transformationName.equals(ConfigConstants.WRITER_NAME)) {
                    subTransformation.setUid(transformation.getUid());
                    return;
                }

                // Use the same uid pattern than for Sink V1 in Flink 1.14.
                if (transformationName.equals(
                        StandardSinkTopologies.GLOBAL_COMMITTER_TRANSFORMATION_NAME)) {
                    final String committerFormat = "Sink %s Global Committer";
                    subTransformation.setUid(
                            String.format(committerFormat, transformation.getUid()));
                    return;
                }
            }
            concatProperty(subTransformation, Transformation::getUid, Transformation::setUid);
        }

        private void concatProperty(
                Transformation<?> subTransformation,
                Function<Transformation<?>, String> getter,
                BiConsumer<Transformation<?>, String> setter) {
            if (getter.apply(transformation) != null && getter.apply(subTransformation) != null) {
                setter.accept(
                        subTransformation,
                        getter.apply(transformation) + ": " + getter.apply(subTransformation));
            }
        }
    }
}
