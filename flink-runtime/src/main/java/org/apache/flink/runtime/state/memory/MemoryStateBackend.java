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

package org.apache.flink.runtime.state.memory;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.runtime.state.BackendBuildingException;
import org.apache.flink.runtime.state.CheckpointStorageAccess;
import org.apache.flink.runtime.state.ConfigurableStateBackend;
import org.apache.flink.runtime.state.DefaultOperatorStateBackendBuilder;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.TaskStateManager;
import org.apache.flink.runtime.state.filesystem.AbstractFileStateBackend;
import org.apache.flink.runtime.state.heap.HeapKeyedStateBackendBuilder;
import org.apache.flink.runtime.state.heap.HeapPriorityQueueSetFactory;
import org.apache.flink.runtime.state.metrics.LatencyTrackingStateConfig;
import org.apache.flink.util.TernaryBoolean;

import javax.annotation.Nullable;

import java.io.IOException;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * <b>IMPORTANT</b> {@link MemoryStateBackend} is deprecated in favor of {@link
 * org.apache.flink.runtime.state.hashmap.HashMapStateBackend} and {@link
 * org.apache.flink.runtime.state.storage.JobManagerCheckpointStorage}. This change does not affect
 * the runtime characteristics of your Jobs and is simply an API change to help better communicate
 * the ways Flink separates local state storage from fault tolerance. Jobs can be upgraded without
 * loss of state. If configuring your state backend via the {@code StreamExecutionEnvironment}
 * please make the following changes.
 *
 * <pre>{@code
 * 		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
 * 		env.setStateBackend(new HashMapStateBackend());
 * 		env.getCheckpointConfig().setCheckpointStorage(new JobManagerCheckpointStorage());
 * }</pre>
 *
 * <p>If you are configuring your state backend via the {@code flink-conf.yaml} please make the
 * following changes:
 *
 * <pre>{@code
 * state.backend.type: hashmap
 * state.checkpoint-storage: jobmanager
 * }</pre>
 *
 * <p>This state backend holds the working state in the memory (JVM heap) of the TaskManagers. The
 * state backend checkpoints state directly to the JobManager's memory (hence the backend's name),
 * but the checkpoints will be persisted to a file system for high-availability setups and
 * savepoints. The MemoryStateBackend is consequently a FileSystem-based backend that can work
 * without a file system dependency in simple setups.
 *
 * <p>This state backend should be used only for experimentation, quick local setups, or for
 * streaming applications that have very small state: Because it requires checkpoints to go through
 * the JobManager's memory, larger state will occupy larger portions of the JobManager's main
 * memory, reducing operational stability. For any other setup, the {@link
 * org.apache.flink.runtime.state.filesystem.FsStateBackend FsStateBackend} should be used. The
 * {@code FsStateBackend} holds the working state on the TaskManagers in the same way, but
 * checkpoints state directly to files rather than to the JobManager's memory, thus supporting large
 * state sizes.
 *
 * <h1>State Size Considerations</h1>
 *
 * <p>State checkpointing with this state backend is subject to the following conditions:
 *
 * <ul>
 *   <li>Each individual state must not exceed the configured maximum state size (see {@link
 *       #getMaxStateSize()}.
 *   <li>All state from one task (i.e., the sum of all operator states and keyed states from all
 *       chained operators of the task) must not exceed what the RPC system supports, which is be
 *       default < 10 MB. That limit can be configured up, but that is typically not advised.
 *   <li>The sum of all states in the application times all retained checkpoints must comfortably
 *       fit into the JobManager's JVM heap space.
 * </ul>
 *
 * <h1>Persistence Guarantees</h1>
 *
 * <p>For the use cases where the state sizes can be handled by this backend, the backend does
 * guarantee persistence for savepoints, externalized checkpoints (of configured), and checkpoints
 * (when high-availability is configured).
 *
 * <h1>Configuration</h1>
 *
 * <p>As for all state backends, this backend can either be configured within the application (by
 * creating the backend with the respective constructor parameters and setting it on the execution
 * environment) or by specifying it in the Flink configuration.
 *
 * <p>If the state backend was specified in the application, it may pick up additional configuration
 * parameters from the Flink configuration. For example, if the backend if configured in the
 * application without a default savepoint directory, it will pick up a default savepoint directory
 * specified in the Flink configuration of the running job/cluster. That behavior is implemented via
 * the {@link #configure(ReadableConfig, ClassLoader)} method.
 */
@Deprecated
@PublicEvolving
public class MemoryStateBackend extends AbstractFileStateBackend
        implements ConfigurableStateBackend {

    private static final long serialVersionUID = 4109305377809414635L;

    /** The default maximal size that the snapshotted memory state may have (5 MiBytes). */
    public static final int DEFAULT_MAX_STATE_SIZE = 5 * 1024 * 1024;

    /** The maximal size that the snapshotted memory state may have. */
    private final int maxStateSize;

    /**
     * Switch to create checkpoint sub-directory with name of jobId. A value of 'undefined' means
     * not yet configured, in which case the default will be used.
     */
    private TernaryBoolean createCheckpointSubDirs = TernaryBoolean.UNDEFINED;

    // ------------------------------------------------------------------------

    /**
     * Creates a new memory state backend that accepts states whose serialized forms are up to the
     * default state size (5 MB).
     *
     * <p>Checkpoint and default savepoint locations are used as specified in the runtime
     * configuration.
     */
    public MemoryStateBackend() {
        this(null, null, DEFAULT_MAX_STATE_SIZE, TernaryBoolean.UNDEFINED);
    }

    /**
     * Creates a new memory state backend that accepts states whose serialized forms are up to the
     * default state size (5 MB). The state backend uses asynchronous snapshots or synchronous
     * snapshots as configured.
     *
     * <p>Checkpoint and default savepoint locations are used as specified in the runtime
     * configuration.
     *
     * @param asynchronousSnapshots This parameter is only there for API compatibility. Checkpoints
     *     are always asynchronous now.
     */
    public MemoryStateBackend(boolean asynchronousSnapshots) {
        this(null, null, DEFAULT_MAX_STATE_SIZE, TernaryBoolean.fromBoolean(asynchronousSnapshots));
    }

    /**
     * Creates a new memory state backend that accepts states whose serialized forms are up to the
     * given number of bytes.
     *
     * <p>Checkpoint and default savepoint locations are used as specified in the runtime
     * configuration.
     *
     * <p><b>WARNING:</b> Increasing the size of this value beyond the default value ({@value
     * #DEFAULT_MAX_STATE_SIZE}) should be done with care. The checkpointed state needs to be send
     * to the JobManager via limited size RPC messages, and there and the JobManager needs to be
     * able to hold all aggregated state in its memory.
     *
     * @param maxStateSize The maximal size of the serialized state
     */
    public MemoryStateBackend(int maxStateSize) {
        this(null, null, maxStateSize, TernaryBoolean.UNDEFINED);
    }

    /**
     * Creates a new memory state backend that accepts states whose serialized forms are up to the
     * given number of bytes and that uses asynchronous snashots as configured.
     *
     * <p>Checkpoint and default savepoint locations are used as specified in the runtime
     * configuration.
     *
     * <p><b>WARNING:</b> Increasing the size of this value beyond the default value ({@value
     * #DEFAULT_MAX_STATE_SIZE}) should be done with care. The checkpointed state needs to be send
     * to the JobManager via limited size RPC messages, and there and the JobManager needs to be
     * able to hold all aggregated state in its memory.
     *
     * @param maxStateSize The maximal size of the serialized state
     * @param asynchronousSnapshots This parameter is only there for API compatibility. Checkpoints
     *     are always asynchronous now.
     */
    public MemoryStateBackend(int maxStateSize, boolean asynchronousSnapshots) {
        this(null, null, maxStateSize, TernaryBoolean.fromBoolean(asynchronousSnapshots));
    }

    /**
     * Creates a new MemoryStateBackend, setting optionally the path to persist checkpoint metadata
     * to, and to persist savepoints to.
     *
     * @param checkpointPath The path to write checkpoint metadata to. If null, the value from the
     *     runtime configuration will be used.
     * @param savepointPath The path to write savepoints to. If null, the value from the runtime
     *     configuration will be used.
     */
    public MemoryStateBackend(@Nullable String checkpointPath, @Nullable String savepointPath) {
        this(checkpointPath, savepointPath, DEFAULT_MAX_STATE_SIZE, TernaryBoolean.UNDEFINED);
    }

    /**
     * Creates a new MemoryStateBackend, setting optionally the paths to persist checkpoint metadata
     * and savepoints to, as well as configuring state thresholds and asynchronous operations.
     *
     * <p><b>WARNING:</b> Increasing the size of this value beyond the default value ({@value
     * #DEFAULT_MAX_STATE_SIZE}) should be done with care. The checkpointed state needs to be send
     * to the JobManager via limited size RPC messages, and there and the JobManager needs to be
     * able to hold all aggregated state in its memory.
     *
     * @param checkpointPath The path to write checkpoint metadata to. If null, the value from the
     *     runtime configuration will be used.
     * @param savepointPath The path to write savepoints to. If null, the value from the runtime
     *     configuration will be used.
     * @param maxStateSize The maximal size of the serialized state.
     * @param asynchronousSnapshots This parameter is only there for API compatibility. Checkpoints
     *     are always asynchronous now.
     */
    public MemoryStateBackend(
            @Nullable String checkpointPath,
            @Nullable String savepointPath,
            int maxStateSize,
            TernaryBoolean asynchronousSnapshots) {

        super(
                checkpointPath == null ? null : new Path(checkpointPath),
                savepointPath == null ? null : new Path(savepointPath));

        checkArgument(maxStateSize > 0, "maxStateSize must be > 0");
        this.maxStateSize = maxStateSize;
    }

    /**
     * Private constructor that creates a re-configured copy of the state backend.
     *
     * @param original The state backend to re-configure
     * @param configuration The configuration
     * @param classLoader The class loader
     */
    private MemoryStateBackend(
            MemoryStateBackend original, ReadableConfig configuration, ClassLoader classLoader) {
        super(original.getCheckpointPath(), original.getSavepointPath(), configuration);

        this.maxStateSize = original.maxStateSize;

        // configure latency tracking
        latencyTrackingConfigBuilder =
                original.latencyTrackingConfigBuilder.configure(configuration);
        this.createCheckpointSubDirs =
                original.createCheckpointSubDirs.resolveUndefined(
                        configuration.get(CheckpointingOptions.CREATE_CHECKPOINT_SUB_DIR));
    }

    // ------------------------------------------------------------------------
    //  Properties
    // ------------------------------------------------------------------------

    /**
     * Gets the maximum size that an individual state can have, as configured in the constructor (by
     * default {@value #DEFAULT_MAX_STATE_SIZE}).
     *
     * @return The maximum size that an individual state can have
     */
    public int getMaxStateSize() {
        return maxStateSize;
    }

    /**
     * Gets whether the key/value data structures are asynchronously snapshotted, which is always
     * true for this state backend.
     */
    public boolean isUsingAsynchronousSnapshots() {
        return true;
    }

    @Override
    public boolean supportsNoClaimRestoreMode() {
        // we never share any files, all snapshots are full
        return true;
    }

    @Override
    public boolean supportsSavepointFormat(SavepointFormatType formatType) {
        return true;
    }

    // ------------------------------------------------------------------------
    //  Reconfiguration
    // ------------------------------------------------------------------------

    /**
     * Creates a copy of this state backend that uses the values defined in the configuration for
     * fields where that were not specified in this state backend.
     *
     * @param config The configuration
     * @param classLoader The class loader
     * @return The re-configured variant of the state backend
     */
    @Override
    public MemoryStateBackend configure(ReadableConfig config, ClassLoader classLoader) {
        return new MemoryStateBackend(this, config, classLoader);
    }

    // ------------------------------------------------------------------------
    //  checkpoint state persistence
    // ------------------------------------------------------------------------

    @Override
    public CheckpointStorageAccess createCheckpointStorage(JobID jobId) throws IOException {
        return new MemoryBackendCheckpointStorageAccess(
                jobId,
                getCheckpointPath(),
                getSavepointPath(),
                createCheckpointSubDirs.getOrDefault(
                        CheckpointingOptions.CREATE_CHECKPOINT_SUB_DIR.defaultValue()),
                maxStateSize);
    }

    // ------------------------------------------------------------------------
    //  state holding structures
    // ------------------------------------------------------------------------

    @Override
    public OperatorStateBackend createOperatorStateBackend(
            OperatorStateBackendParameters parameters) throws Exception {
        return new DefaultOperatorStateBackendBuilder(
                        parameters.getEnv().getUserCodeClassLoader().asClassLoader(),
                        parameters.getEnv().getExecutionConfig(),
                        isUsingAsynchronousSnapshots(),
                        parameters.getStateHandles(),
                        parameters.getCancelStreamRegistry())
                .build();
    }

    @Override
    public <K> AbstractKeyedStateBackend<K> createKeyedStateBackend(
            KeyedStateBackendParameters<K> parameters) throws BackendBuildingException {

        TaskStateManager taskStateManager = parameters.getEnv().getTaskStateManager();
        HeapPriorityQueueSetFactory priorityQueueSetFactory =
                new HeapPriorityQueueSetFactory(
                        parameters.getKeyGroupRange(), parameters.getNumberOfKeyGroups(), 128);
        LatencyTrackingStateConfig latencyTrackingStateConfig =
                latencyTrackingConfigBuilder.setMetricGroup(parameters.getMetricGroup()).build();
        return new HeapKeyedStateBackendBuilder<>(
                        parameters.getKvStateRegistry(),
                        parameters.getKeySerializer(),
                        parameters.getEnv().getUserCodeClassLoader().asClassLoader(),
                        parameters.getNumberOfKeyGroups(),
                        parameters.getKeyGroupRange(),
                        parameters.getEnv().getExecutionConfig(),
                        parameters.getTtlTimeProvider(),
                        latencyTrackingStateConfig,
                        parameters.getStateHandles(),
                        AbstractStateBackend.getCompressionDecorator(
                                parameters.getEnv().getExecutionConfig()),
                        taskStateManager.createLocalRecoveryConfig(),
                        priorityQueueSetFactory,
                        isUsingAsynchronousSnapshots(),
                        parameters.getCancelStreamRegistry())
                .build();
    }

    // ------------------------------------------------------------------------
    //  utilities
    // ------------------------------------------------------------------------

    @Override
    public String toString() {
        return "MemoryStateBackend (data in heap memory / checkpoints to JobManager) "
                + "(checkpoints: '"
                + getCheckpointPath()
                + "', savepoints: '"
                + getSavepointPath()
                + ", maxStateSize: "
                + maxStateSize
                + ")";
    }
}
