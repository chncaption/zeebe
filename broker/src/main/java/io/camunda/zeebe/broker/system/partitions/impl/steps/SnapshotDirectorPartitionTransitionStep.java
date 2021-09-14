/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions.impl.steps;

import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.partition.impl.RaftPartitionServer;
import io.camunda.zeebe.broker.system.partitions.PartitionTransitionContext;
import io.camunda.zeebe.broker.system.partitions.PartitionTransitionStep;
import io.camunda.zeebe.broker.system.partitions.impl.AsyncSnapshotDirector;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;

public class SnapshotDirectorPartitionTransitionStep implements PartitionTransitionStep {

  @Override
  public ActorFuture<Void> prepareTransition(
      final PartitionTransitionContext context, final long term, final Role targetRole) {
    final AsyncSnapshotDirector snapshotDirector = context.getSnapshotDirector();
    if (snapshotDirector != null
        && (shouldInstallOnTransition(targetRole, context.getCurrentRole())
            || targetRole == Role.INACTIVE)) {
      final var director = context.getSnapshotDirector();
      context.getComponentHealthMonitor().removeComponent(director.getName());
      context.getRaftPartition().getServer().removeCommittedEntryListener(director);
      final ActorFuture<Void> future = director.closeAsync();
      future.onComplete(
          (ok, error) -> {
            if (error == null) {
              context.setSnapshotDirector(null);
            }
          });
      return future;
    } else {
      return CompletableActorFuture.completed(null);
    }
  }

  @Override
  public ActorFuture<Void> transitionTo(
      final PartitionTransitionContext context, final long term, final Role targetRole) {
    if ((context.getSnapshotDirector() == null && targetRole != Role.INACTIVE)
        || shouldInstallOnTransition(targetRole, context.getCurrentRole())) {
      final var server = context.getRaftPartition().getServer();

      final Duration snapshotPeriod = context.getBrokerCfg().getData().getSnapshotPeriod();
      final AsyncSnapshotDirector director;
      if (targetRole == Role.LEADER) {
        director = createSnapshotDirectorOfLeader(context, server, snapshotPeriod);
      } else {
        director = createSnapshotDirectorOfFollower(context, snapshotPeriod);
      }

      final var future = context.getActorSchedulingService().submitActor(director);
      future.onComplete(
          (ok, error) -> {
            if (error == null) {
              context.setSnapshotDirector(director);
              context.getComponentHealthMonitor().registerComponent(director.getName(), director);
            }
          });
      return future;

    } else {
      return CompletableActorFuture.completed(null);
    }
  }

  @Override
  public String getName() {
    return "AsyncSnapshotDirector";
  }

  private AsyncSnapshotDirector createSnapshotDirectorOfLeader(
      final PartitionTransitionContext context,
      final RaftPartitionServer server,
      final Duration snapshotPeriod) {
    final var director =
        AsyncSnapshotDirector.ofProcessingMode(
            context.getNodeId(),
            context.getPartitionId(),
            context.getStreamProcessor(),
            context.getStateController(),
            snapshotPeriod);

    server.addCommittedEntryListener(director);
    return director;
  }

  private AsyncSnapshotDirector createSnapshotDirectorOfFollower(
      final PartitionTransitionContext context, final Duration snapshotPeriod) {
    return AsyncSnapshotDirector.ofReplayMode(
        context.getNodeId(),
        context.getPartitionId(),
        context.getStreamProcessor(),
        context.getStateController(),
        snapshotPeriod);
  }

  private boolean shouldInstallOnTransition(final Role newRole, final Role currentRole) {
    return newRole == Role.LEADER
        || (newRole == Role.FOLLOWER && currentRole != Role.CANDIDATE)
        || (newRole == Role.CANDIDATE && currentRole != Role.FOLLOWER);
  }
}
