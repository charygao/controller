/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.raft.behaviors;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opendaylight.controller.cluster.raft.FollowerLogInformation;
import org.opendaylight.controller.cluster.raft.RaftActorContext;
import org.opendaylight.controller.cluster.raft.RaftActorLeadershipTransferCohort;
import org.opendaylight.controller.cluster.raft.RaftState;
import org.opendaylight.controller.cluster.raft.base.messages.ElectionTimeout;
import org.opendaylight.controller.cluster.raft.base.messages.IsolatedLeaderCheck;
import org.opendaylight.controller.cluster.raft.messages.AppendEntriesReply;

/**
 * The behavior of a RaftActor when it is in the Leader state
 * <p/>
 * Leaders:
 * <ul>
 * <li> Upon election: send initial empty AppendEntries RPCs
 * (heartbeat) to each server; repeat during idle periods to
 * prevent election timeouts (§5.2)
 * <li> If command received from client: append entry to local log,
 * respond after entry applied to state machine (§5.3)
 * <li> If last log index ≥ nextIndex for a follower: send
 * AppendEntries RPC with log entries starting at nextIndex
 * <ul>
 * <li> If successful: update nextIndex and matchIndex for
 * follower (§5.3)
 * <li> If AppendEntries fails because of log inconsistency:
 * decrement nextIndex and retry (§5.3)
 * </ul>
 * <li> If there exists an N such that N > commitIndex, a majority
 * of matchIndex[i] ≥ N, and log[N].term == currentTerm:
 * set commitIndex = N (§5.3, §5.4).
 */
public class Leader extends AbstractLeader {
    private static final IsolatedLeaderCheck ISOLATED_LEADER_CHECK = new IsolatedLeaderCheck();
    private final Stopwatch isolatedLeaderCheck;
    private @Nullable LeadershipTransferContext leadershipTransferContext;

    public Leader(RaftActorContext context) {
        super(context);
        isolatedLeaderCheck = Stopwatch.createStarted();
    }

    @Override public RaftActorBehavior handleMessage(ActorRef sender, Object originalMessage) {
        Preconditions.checkNotNull(sender, "sender should not be null");

        if (originalMessage instanceof IsolatedLeaderCheck) {
            if (isLeaderIsolated()) {
                LOG.warn("{}: At least {} followers need to be active, Switching {} from Leader to IsolatedLeader",
                        context.getId(), getMinIsolatedLeaderPeerCount(), leaderId);

                return internalSwitchBehavior(RaftState.IsolatedLeader);
            }
        }

        return super.handleMessage(sender, originalMessage);
    }

    @Override
    protected void beforeSendHeartbeat(){
        if(isolatedLeaderCheck.elapsed(TimeUnit.MILLISECONDS) > context.getConfigParams().getIsolatedCheckIntervalInMillis()){
            context.getActor().tell(ISOLATED_LEADER_CHECK, context.getActor());
            isolatedLeaderCheck.reset().start();
        }

        if(leadershipTransferContext != null && leadershipTransferContext.isExpired(
                context.getConfigParams().getElectionTimeOutInterval().toMillis())) {
            LOG.debug("{}: Leadership transfer expired", logName());
            leadershipTransferContext = null;
        }
    }

    @Override
    protected RaftActorBehavior handleAppendEntriesReply(ActorRef sender, AppendEntriesReply appendEntriesReply) {
        RaftActorBehavior returnBehavior = super.handleAppendEntriesReply(sender, appendEntriesReply);
        tryToCompleteLeadershipTransfer(appendEntriesReply.getFollowerId());
        return returnBehavior;
    }

    public void transferLeadership(@Nonnull RaftActorLeadershipTransferCohort leadershipTransferCohort) {
        if(!context.hasFollowers()) {
            leadershipTransferCohort.transferComplete();
            return;
        }

        LOG.debug("{}: Attempting to transfer leadership", logName());

        leadershipTransferContext = new LeadershipTransferContext(leadershipTransferCohort);

        // Send an immediate heart beat to the followers.
        sendAppendEntries(0, false);
    }

    private void tryToCompleteLeadershipTransfer(String followerId) {
        if(leadershipTransferContext == null) {
            return;
        }

        FollowerLogInformation followerInfo = getFollower(followerId);
        if(followerInfo == null) {
            return;
        }

        long lastIndex = context.getReplicatedLog().lastIndex();

        LOG.debug("{}: tryToCompleteLeadershipTransfer: followerId: {}, matchIndex: {}, lastIndex: {}",
                logName(), followerId, followerInfo.getMatchIndex(), lastIndex);

        if(followerInfo.getMatchIndex() == lastIndex) {
            LOG.debug("{}: Follower's log matches - sending ElectionTimeout", logName());

            // We can't be sure if the follower has applied all its log entries to its state so send an
            // additional AppendEntries.
            sendAppendEntries(0, false);

            // Now send an ElectionTimeout to the matching follower to immediately start an election.
            ActorSelection followerActor = context.getPeerActorSelection(followerId);
            followerActor.tell(new ElectionTimeout(), context.getActor());

            LOG.debug("{}: Leader transfer complete", logName());

            leadershipTransferContext.transferCohort.transferComplete();
            leadershipTransferContext = null;
        }
    }

    @Override
    public void close() throws Exception {
        if(leadershipTransferContext != null) {
            leadershipTransferContext.transferCohort.abortTransfer();
        }

        super.close();
    }

    @VisibleForTesting
    void markFollowerActive(String followerId) {
        getFollower(followerId).markFollowerActive();
    }

    @VisibleForTesting
    void markFollowerInActive(String followerId) {
        getFollower(followerId).markFollowerInActive();
    }

    private static class LeadershipTransferContext {
        RaftActorLeadershipTransferCohort transferCohort;
        Stopwatch timer = Stopwatch.createStarted();

        LeadershipTransferContext(RaftActorLeadershipTransferCohort transferCohort) {
            this.transferCohort = transferCohort;
        }

        boolean isExpired(long timeout) {
            if(timer.elapsed(TimeUnit.MILLISECONDS) >= timeout) {
                transferCohort.abortTransfer();
                return true;
            }

            return false;
        }
    }
}
