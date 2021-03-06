package org.motovs.elasticsearch.snapshots;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.SnapshotId;
import org.elasticsearch.cluster.metadata.SnapshotMetaData;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.log4j.LogConfigurator;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.internal.InternalNode;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.transport.*;

import java.io.IOException;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

/**
 * Created by igor on 5/27/14.
 */
public class AbortedSnapshotCleaner {

    public static void main(String[] args) {
        AbortedSnapshotCleaner cleaner = new AbortedSnapshotCleaner(ImmutableSettings.EMPTY);
        try {
            cleaner.cleanSnapshots();
        } finally {
            cleaner.close();
        }

    }


    private Node node;

    private ESLogger logger;

    public AbortedSnapshotCleaner(Settings settings) {
        this(nodeBuilder().client(true).node(), settings);
    }

    // For testing
    public AbortedSnapshotCleaner(Node node, Settings settings) {
        LogConfigurator.configure(settings);
        logger = Loggers.getLogger(getClass(), settings);
        this.node = node;
    }

    public void cleanSnapshots() {
        InternalNode internalNode = (InternalNode)node;
        TransportService transportService = internalNode.injector().getInstance(TransportService.class);
        ClusterService clusterService = internalNode.injector().getInstance(ClusterService.class);


        ClusterState clusterState = clusterService.state();
        SnapshotMetaData snapshots = clusterState.getMetaData().custom(SnapshotMetaData.TYPE);
        if (snapshots == null || snapshots.entries().isEmpty()) {
            logger.info("No snapshots found, snapshots metadata is {}", snapshots == null ? "null" : "empty");
            return;
        }

        for (SnapshotMetaData.Entry entry : snapshots.entries()) {
            SnapshotId snapshotId = entry.snapshotId();
            logger.info("Processing snapshot [{}]", snapshotId);
            boolean updated = false;
            for (ImmutableMap.Entry<ShardId, SnapshotMetaData.ShardSnapshotStatus> shard : entry.shards().entrySet()) {
                String nodeId = shard.getValue().nodeId();
                if (shard.getValue().state() == SnapshotMetaData.State.ABORTED && clusterState.nodes().get(nodeId) == null) {
                    logger.info("Found aborted snapshot [{}] on node [{}] - cleaning", shard.getKey(), nodeId);
                    SnapshotMetaData.ShardSnapshotStatus status = new SnapshotMetaData.ShardSnapshotStatus(nodeId, SnapshotMetaData.State.FAILED, "Aborted");
                    UpdateIndexShardSnapshotStatusRequest request = new UpdateIndexShardSnapshotStatusRequest(snapshotId, shard.getKey(), status);
                    transportService.sendRequest(clusterService.state().nodes().masterNode(),
                            SnapshotsService.UPDATE_SNAPSHOT_ACTION_NAME, request, EmptyTransportResponseHandler.INSTANCE_SAME);
                    updated = true;
                } else {
                    logger.info("Ignoring shard [{}] with state [{}] on node [{}] - node exists : [{}]", shard.getKey(), shard.getValue().state(), nodeId, clusterState.nodes().get(nodeId) != null);
                }
            }
            if (updated == false) {
                // Hmm, nothing was found - try to push it by adding fake aborted shard
                SnapshotMetaData.ShardSnapshotStatus status = new SnapshotMetaData.ShardSnapshotStatus("fake-node", SnapshotMetaData.State.FAILED, "Aborted");
                UpdateIndexShardSnapshotStatusRequest request = new UpdateIndexShardSnapshotStatusRequest(snapshotId, new ShardId("fake-index", 0), status);
                transportService.sendRequest(clusterService.state().nodes().masterNode(),
                        SnapshotsService.UPDATE_SNAPSHOT_ACTION_NAME, request, EmptyTransportResponseHandler.INSTANCE_SAME);
            }
        }
    }

    public void close() {
        node.close();
    }


    /**
     * Internal request that is used to send changes in snapshot status to master
     */
    private static class UpdateIndexShardSnapshotStatusRequest extends TransportRequest {
        private SnapshotId snapshotId;
        private ShardId shardId;
        private SnapshotMetaData.ShardSnapshotStatus status;

        private UpdateIndexShardSnapshotStatusRequest() {

        }

        private UpdateIndexShardSnapshotStatusRequest(SnapshotId snapshotId, ShardId shardId, SnapshotMetaData.ShardSnapshotStatus status) {
            this.snapshotId = snapshotId;
            this.shardId = shardId;
            this.status = status;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            snapshotId = SnapshotId.readSnapshotId(in);
            shardId = ShardId.readShardId(in);
            status = SnapshotMetaData.ShardSnapshotStatus.readShardSnapshotStatus(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            snapshotId.writeTo(out);
            shardId.writeTo(out);
            status.writeTo(out);
        }

        public SnapshotId snapshotId() {
            return snapshotId;
        }

        public ShardId shardId() {
            return shardId;
        }

        public SnapshotMetaData.ShardSnapshotStatus status() {
            return status;
        }
    }

}
