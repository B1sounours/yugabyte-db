// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.subtasks;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.WireProtocol;
import org.yb.client.AbstractModifyMasterClusterConfig;
import org.yb.client.YBClient;
import org.yb.master.Master;

import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.params.ITaskParams;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Universe.NodeDetails;

import play.api.Play;

public class UpdatePlacementInfo extends AbstractTaskBase {
  public static final Logger LOG = LoggerFactory.getLogger(UpdatePlacementInfo.class);

  // The YB client.
  public YBClientService ybService = null;

  // Parameters for placement info update task.
  public static class Params implements ITaskParams {
    // The cloud provider to get node details.
    public CloudType cloud;

    // The universe against which this node's details should be saved.
    public UUID universeUUID;

    // If present, then we intend to decommission these nodes.
    public Set<String> blacklistNodes = null;
  }

  protected Params taskParams() {
    return (Params)taskParams;
  }

  @Override
  public void initialize(ITaskParams params) {
    super.initialize(params);
    ybService = Play.current().injector().instanceOf(YBClientService.class);
  }

  @Override
  public String getName() {
    return super.getName() + "(" + taskParams().universeUUID + ")";
  }

  @Override
  public void run() {
    String hostPorts = Universe.get(taskParams().universeUUID).getMasterAddresses();
    try {
      LOG.info("Running {}: hostPorts={}.", getName(), hostPorts);

      ModifyUniverseConfig modifyConfig = new ModifyUniverseConfig(ybService.getClient(hostPorts),
                                                                   taskParams().universeUUID,
                                                                   taskParams().blacklistNodes);
      modifyConfig.doCall();
    } catch (Exception e) {
      LOG.error("{} hit error : {}", getName(), e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static class ModifyUniverseConfig extends AbstractModifyMasterClusterConfig {
    UUID universeUUID;
    Set<String> blacklistNodes;

    public ModifyUniverseConfig(YBClient client,
                                UUID universeUUID,
                                Set<String> blacklistNodes) {
      super(client);
      this.universeUUID = universeUUID;
      this.blacklistNodes = blacklistNodes;
    }

    @Override
    protected Master.SysClusterConfigEntryPB modifyConfig(Master.SysClusterConfigEntryPB config) {
      // Get the masters in the universe.
      Collection<Universe.NodeDetails> masters = Universe.get(universeUUID).getMasters();

      Master.SysClusterConfigEntryPB.Builder configBuilder =
          Master.SysClusterConfigEntryPB.newBuilder();
      Master.PlacementInfoPB.Builder placementInfo = configBuilder.getPlacementInfoBuilder();

      // Set the replication factor to the number of masters.
      placementInfo.setNumReplicas(masters.size());

      // This is a set to track unique placement ids to prevent repetition.
      Set<String> placementIds = new HashSet<String>();
      // Generate the current config from the masters in the universe.
      for (NodeDetails node : masters) {
        // Add one entry per (cloud, region, az) tuple. For now, assume the concatenation of the
        // names is unique with no subname overlapping.
        String placementId = node.cloud + node.region + node.az;
        if (!placementIds.contains(placementId)) {
          // Create the cloud info object.
          WireProtocol.CloudInfoPB.Builder ccb = WireProtocol.CloudInfoPB.newBuilder();
          ccb.setPlacementCloud(node.cloud)
             .setPlacementRegion(node.region)
             .setPlacementZone(node.az);

          Master.PlacementBlockPB.Builder pbb = Master.PlacementBlockPB.newBuilder();
          // Set the cloud info.
          pbb.setCloudInfo(ccb);
          // Set the minumum number of replicas in this PlacementAZ to 1 (at least one copy of the data).
          pbb.setMinNumReplicas(1);
          placementInfo.addPlacementBlocks(pbb);
          // Add this (cloud, region, az) tuple to the set to make sure we do not process it again.
          placementIds.add(placementId);
        }
      }
      placementInfo.build();
      return configBuilder.build();
    }
  }
}
