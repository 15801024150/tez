/**
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

package org.apache.tez.dag.app.dag.impl;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.Event;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.RackResolver;
import org.apache.hadoop.yarn.util.Records;
import org.apache.tez.common.TezEngineTaskContext;
import org.apache.tez.common.TezTaskContext;
import org.apache.tez.common.counters.DAGCounter;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.VertexLocationHint.TaskLocationHint;
import org.apache.tez.dag.api.oldrecords.TaskAttemptReport;
import org.apache.tez.dag.api.oldrecords.TaskAttemptState;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.TaskAttemptListener;
import org.apache.tez.dag.app.TaskHeartbeatHandler;
import org.apache.tez.dag.app.dag.DAG;
import org.apache.tez.dag.app.dag.Task;
import org.apache.tez.dag.app.dag.TaskAttempt;
import org.apache.tez.dag.app.dag.TaskAttemptStateInternal;
import org.apache.tez.dag.app.dag.Vertex;
import org.apache.tez.dag.app.dag.event.DAGEvent;
import org.apache.tez.dag.app.dag.event.DAGEventCounterUpdate;
import org.apache.tez.dag.app.dag.event.DAGEventDiagnosticsUpdate;
import org.apache.tez.dag.app.dag.event.DAGEventType;
import org.apache.tez.dag.app.dag.event.DiagnosableEvent;
import org.apache.tez.dag.app.dag.event.TaskAttemptEvent;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventContainerTerminated;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventContainerTerminating;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventDiagnosticsUpdate;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventNodeFailed;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventOutputConsumable;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventSchedule;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventStartedRemotely;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventStatusUpdate;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventStatusUpdate.TaskAttemptStatus;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventType;
import org.apache.tez.dag.app.dag.event.TaskEventTAUpdate;
import org.apache.tez.dag.app.dag.event.TaskEventType;
import org.apache.tez.dag.app.dag.event.VertexEventTaskAttemptFetchFailure;
import org.apache.tez.dag.app.rm.AMSchedulerEventTAEnded;
import org.apache.tez.dag.app.rm.AMSchedulerEventTALaunchRequest;
import org.apache.tez.dag.history.DAGHistoryEvent;
import org.apache.tez.dag.history.events.TaskAttemptFinishedEvent;
import org.apache.tez.dag.history.events.TaskAttemptStartedEvent;
import org.apache.tez.dag.records.TezDAGID;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;
import org.apache.tez.dag.records.TezVertexID;
import org.apache.tez.dag.utils.TezBuilderUtils;
import org.apache.tez.engine.common.security.JobTokenIdentifier;

import com.google.common.annotations.VisibleForTesting;

public class TaskAttemptImpl implements TaskAttempt,
    EventHandler<TaskAttemptEvent> {

  // TODO Ensure MAPREDUCE-4457 is factored in. Also MAPREDUCE-4068.
  // TODO Consider TAL registration in the TaskAttempt instead of the container.

  private static final Log LOG = LogFactory.getLog(TaskAttemptImpl.class);
  private static final String LINE_SEPARATOR = System
      .getProperty("line.separator");

  static final TezCounters EMPTY_COUNTERS = new TezCounters();

  protected final Configuration conf;
  protected final int partition;
  @SuppressWarnings("rawtypes")
  protected EventHandler eventHandler;
  private final TezTaskAttemptID attemptId;
  private final Clock clock;
  private final List<String> diagnostics = new ArrayList<String>();
  private final Lock readLock;
  private final Lock writeLock;
  protected final AppContext appContext;
  private final TaskHeartbeatHandler taskHeartbeatHandler;
  private Credentials credentials;
  protected Token<JobTokenIdentifier> jobToken;
  private long launchTime = 0;
  private long finishTime = 0;
  private int shufflePort = -1;
  private String trackerName;
  private int httpPort;

  // TODO Can these be replaced by the container object ?
  private ContainerId containerId;
  private NodeId containerNodeId;
  private String nodeHttpAddress;
  private String nodeRackName;

  private TaskAttemptStatus reportedStatus;
  private DAGCounter localityCounter;

  // Used to store locality information when
  Set<String> taskHosts = new HashSet<String>();
  Set<String> taskRacks = new HashSet<String>();

  protected final TaskLocationHint locationHint;
  protected final Resource taskResource;
  protected final Map<String, LocalResource> localResources;
  protected final Map<String, String> environment;
  protected final String javaOpts;
  protected final boolean isRescheduled;

  protected ProcessorDescriptor processorDescriptor;

  protected static final FailedTransitionHelper FAILED_HELPER =
      new FailedTransitionHelper();

  protected static final KilledTransitionHelper KILLED_HELPER =
      new KilledTransitionHelper();

  private static SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent>
      DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION =
          new DiagnosticInformationUpdater();

  private static SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent>
      STATUS_UPDATER = new StatusUpdaterTransition();

  private final StateMachine<TaskAttemptStateInternal, TaskAttemptEventType, TaskAttemptEvent> stateMachine;

  private static StateMachineFactory
  <TaskAttemptImpl, TaskAttemptStateInternal, TaskAttemptEventType, TaskAttemptEvent>
  stateMachineFactory
            = new StateMachineFactory
            <TaskAttemptImpl, TaskAttemptStateInternal, TaskAttemptEventType, TaskAttemptEvent>
            (TaskAttemptStateInternal.NEW)

        .addTransition(TaskAttemptStateInternal.NEW, TaskAttemptStateInternal.START_WAIT, TaskAttemptEventType.TA_SCHEDULE, new ScheduleTaskattemptTransition())
        .addTransition(TaskAttemptStateInternal.NEW, TaskAttemptStateInternal.NEW, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.NEW, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_FAIL_REQUEST, new TerminateTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.NEW, TaskAttemptStateInternal.KILLED, TaskAttemptEventType.TA_KILL_REQUEST, new TerminateTransition(KILLED_HELPER))

        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.RUNNING, TaskAttemptEventType.TA_STARTED_REMOTELY, new StartedTransition())
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.START_WAIT, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAIL_REQUEST, new TerminatedBeforeRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_KILL_REQUEST, new TerminatedBeforeRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_NODE_FAILED, new NodeFailedBeforeRunningTransition())
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_CONTAINER_TERMINATING, new ContainerTerminatingBeforeRunningTransition())
        .addTransition(TaskAttemptStateInternal.START_WAIT, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedBeforeRunningTransition())

        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.RUNNING, TaskAttemptEventType.TA_STATUS_UPDATE, STATUS_UPDATER)
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.RUNNING, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE, new OutputConsumableTransition()) //Optional, may not come in for all tasks.
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptEventType.TA_COMMIT_PENDING, new CommitPendingTransition())
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.SUCCEEDED, TaskAttemptEventType.TA_DONE, new SucceededTransition())
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAILED, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_TIMED_OUT, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAIL_REQUEST, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_KILL_REQUEST, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_NODE_FAILED, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_CONTAINER_TERMINATING, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.RUNNING, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedWhileRunningTransition())

        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_STATUS_UPDATE, STATUS_UPDATER)
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE) // Stuck RPC. The client retries in a loop.
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptEventType.TA_COMMIT_PENDING, new CommitPendingAtOutputConsumableTransition())
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.SUCCEEDED, TaskAttemptEventType.TA_DONE, new SucceededTransition())
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAILED,  new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_TIMED_OUT,  new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAIL_REQUEST,  new TerminatedWhileRunningTransition(FAILED_HELPER))
        // TODO CREUSE Ensure TaskCompletionEvents are updated to reflect this. Something needs to go out to the job.
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_KILL_REQUEST, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_NODE_FAILED, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_CONTAINER_TERMINATING, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedBeforeRunningTransition())
        .addTransition(TaskAttemptStateInternal.OUTPUT_CONSUMABLE, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES, new TerminatedWhileRunningTransition(FAILED_HELPER))

        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptEventType.TA_STATUS_UPDATE, STATUS_UPDATER)
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptEventType.TA_COMMIT_PENDING)
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.SUCCEEDED, TaskAttemptEventType.TA_DONE, new SucceededTransition())
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAILED, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_TIMED_OUT, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_FAIL_REQUEST, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_KILL_REQUEST, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_NODE_FAILED, new TerminatedWhileRunningTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_CONTAINER_TERMINATING, new TerminatedWhileRunningTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedBeforeRunningTransition())
        .addTransition(TaskAttemptStateInternal.COMMIT_PENDING, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES, new TerminatedWhileRunningTransition(FAILED_HELPER))

        .addTransition(TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptStateInternal.KILLED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedWhileTerminating())
        .addTransition(TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.KILL_IN_PROGRESS, TaskAttemptStateInternal.KILL_IN_PROGRESS, EnumSet.of(TaskAttemptEventType.TA_STARTED_REMOTELY, TaskAttemptEventType.TA_STATUS_UPDATE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_COMMIT_PENDING, TaskAttemptEventType.TA_DONE, TaskAttemptEventType.TA_FAILED, TaskAttemptEventType.TA_TIMED_OUT, TaskAttemptEventType.TA_FAIL_REQUEST, TaskAttemptEventType.TA_KILL_REQUEST, TaskAttemptEventType.TA_NODE_FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATING, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES))

        .addTransition(TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATED, new ContainerCompletedWhileTerminating())
        .addTransition(TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.FAIL_IN_PROGRESS, TaskAttemptStateInternal.FAIL_IN_PROGRESS, EnumSet.of(TaskAttemptEventType.TA_STARTED_REMOTELY, TaskAttemptEventType.TA_STATUS_UPDATE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_COMMIT_PENDING, TaskAttemptEventType.TA_DONE, TaskAttemptEventType.TA_FAILED, TaskAttemptEventType.TA_TIMED_OUT, TaskAttemptEventType.TA_FAIL_REQUEST, TaskAttemptEventType.TA_KILL_REQUEST, TaskAttemptEventType.TA_NODE_FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATING, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES))

        .addTransition(TaskAttemptStateInternal.KILLED, TaskAttemptStateInternal.KILLED, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.KILLED, TaskAttemptStateInternal.KILLED, EnumSet.of(TaskAttemptEventType.TA_STARTED_REMOTELY, TaskAttemptEventType.TA_STATUS_UPDATE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_COMMIT_PENDING, TaskAttemptEventType.TA_DONE, TaskAttemptEventType.TA_FAILED, TaskAttemptEventType.TA_TIMED_OUT, TaskAttemptEventType.TA_FAIL_REQUEST, TaskAttemptEventType.TA_KILL_REQUEST, TaskAttemptEventType.TA_NODE_FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATING, TaskAttemptEventType.TA_CONTAINER_TERMINATED, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES))

        .addTransition(TaskAttemptStateInternal.FAILED, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.FAILED, TaskAttemptStateInternal.FAILED, EnumSet.of(TaskAttemptEventType.TA_STARTED_REMOTELY, TaskAttemptEventType.TA_STATUS_UPDATE, TaskAttemptEventType.TA_OUTPUT_CONSUMABLE, TaskAttemptEventType.TA_COMMIT_PENDING, TaskAttemptEventType.TA_DONE, TaskAttemptEventType.TA_FAILED, TaskAttemptEventType.TA_TIMED_OUT, TaskAttemptEventType.TA_FAIL_REQUEST, TaskAttemptEventType.TA_KILL_REQUEST, TaskAttemptEventType.TA_NODE_FAILED, TaskAttemptEventType.TA_CONTAINER_TERMINATING, TaskAttemptEventType.TA_CONTAINER_TERMINATED, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES))

        // How will duplicate history events be handled ?
        // TODO Maybe consider not failing REDUCE tasks in this case. Also, MAP_TASKS in case there's only one phase in the job.
        .addTransition(TaskAttemptStateInternal.SUCCEEDED, TaskAttemptStateInternal.SUCCEEDED, TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE, DIAGNOSTIC_INFORMATION_UPDATE_TRANSITION)
        .addTransition(TaskAttemptStateInternal.SUCCEEDED, TaskAttemptStateInternal.KILLED, TaskAttemptEventType.TA_KILL_REQUEST, new TerminatedAfterSuccessTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.SUCCEEDED, TaskAttemptStateInternal.KILLED, TaskAttemptEventType.TA_NODE_FAILED, new TerminatedAfterSuccessTransition(KILLED_HELPER))
        .addTransition(TaskAttemptStateInternal.SUCCEEDED, TaskAttemptStateInternal.FAILED, TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES, new TerminatedAfterSuccessTransition(FAILED_HELPER))
        .addTransition(TaskAttemptStateInternal.SUCCEEDED, TaskAttemptStateInternal.SUCCEEDED, EnumSet.of(TaskAttemptEventType.TA_TIMED_OUT, TaskAttemptEventType.TA_FAIL_REQUEST, TaskAttemptEventType.TA_CONTAINER_TERMINATING, TaskAttemptEventType.TA_CONTAINER_TERMINATED))


        .installTopology();


  // TODO Remove TaskAttemptListener from the constructor.
  @SuppressWarnings("rawtypes")
  public TaskAttemptImpl(TezTaskID taskId, int attemptNumber, EventHandler eventHandler,
      TaskAttemptListener tal, int partition,
      Configuration conf,
      Token<JobTokenIdentifier> jobToken, Credentials credentials, Clock clock,
      TaskHeartbeatHandler taskHeartbeatHandler, AppContext appContext,
      ProcessorDescriptor processorDescriptor, TaskLocationHint locationHint,
      Resource resource, Map<String, LocalResource> localResources,
      Map<String, String> environment,
      String javaOpts, boolean isRescheduled) {
    ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    this.readLock = rwLock.readLock();
    this.writeLock = rwLock.writeLock();
    this.attemptId = TezBuilderUtils.newTaskAttemptId(taskId, attemptNumber);
    this.eventHandler = eventHandler;
    //Reported status
    this.partition = partition;
    this.conf = conf;
    this.jobToken = jobToken;
    this.credentials = credentials;
    this.clock = clock;
    this.taskHeartbeatHandler = taskHeartbeatHandler;
    this.appContext = appContext;
    this.taskResource = resource;
    this.reportedStatus = new TaskAttemptStatus();
    this.processorDescriptor = processorDescriptor;
    initTaskAttemptStatus(reportedStatus);
    RackResolver.init(conf);
    this.stateMachine = stateMachineFactory.make(this);
    this.locationHint = locationHint;
    this.localResources = localResources;
    this.environment = environment;
    this.javaOpts = javaOpts;
    this.isRescheduled = isRescheduled;
  }


  @Override
  public TezTaskAttemptID getID() {
    return attemptId;
  }

  @Override
  public TezTaskID getTaskID() {
    return attemptId.getTaskID();
  }

  @Override
  public TezVertexID getVertexID() {
    return attemptId.getTaskID().getVertexID();
  }

  @Override
  public TezDAGID getDAGID() {
    return getVertexID().getDAGId();
  }

  TezTaskContext createRemoteTask() {
    Vertex vertex = getTask().getVertex();
    DAG dag = vertex.getDAG();

    // TODO  TEZ-50 user and jobname
    return new TezEngineTaskContext(getID(), dag.getUserName(),
        dag.getName(), getTask()
        .getVertex().getName(), processorDescriptor,
        vertex.getInputSpecList(), vertex.getOutputSpecList());
  }

  @Override
  public TaskAttemptReport getReport() {
    TaskAttemptReport result = Records.newRecord(TaskAttemptReport.class);
    readLock.lock();
    try {
      result.setTaskAttemptId(attemptId);
      //take the LOCAL state of attempt
      //DO NOT take from reportedStatus

      result.setTaskAttemptState(getState());
      result.setProgress(reportedStatus.progress);
      result.setStartTime(launchTime);
      result.setFinishTime(finishTime);
      result.setShuffleFinishTime(this.reportedStatus.shuffleFinishTime);
      result.setDiagnosticInfo(StringUtils.join(LINE_SEPARATOR, getDiagnostics()));
      //result.setPhase(reportedStatus.phase);
      result.setStateString(reportedStatus.stateString);
      result.setCounters(getCounters());
      result.setContainerId(this.getAssignedContainerID());
      result.setNodeManagerHost(trackerName);
      result.setNodeManagerHttpPort(httpPort);
      if (this.containerNodeId != null) {
        result.setNodeManagerPort(this.containerNodeId.getPort());
      }
      return result;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public List<String> getDiagnostics() {
    List<String> result = new ArrayList<String>();
    readLock.lock();
    try {
      result.addAll(diagnostics);
      return result;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public TezCounters getCounters() {
    readLock.lock();
    try {
      reportedStatus.setLocalityCounter(localityCounter);
      TezCounters counters = reportedStatus.counters;
      if (counters == null) {
        counters = EMPTY_COUNTERS;
      }
      return counters;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public float getProgress() {
    readLock.lock();
    try {
      return reportedStatus.progress;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public TaskAttemptState getState() {
    readLock.lock();
    try {
      return getExternalState(stateMachine.getCurrentState());
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public boolean isFinished() {
    readLock.lock();
    try {
      return (EnumSet.of(TaskAttemptStateInternal.SUCCEEDED,
          TaskAttemptStateInternal.FAILED,
          TaskAttemptStateInternal.FAIL_IN_PROGRESS,
          TaskAttemptStateInternal.KILLED,
          TaskAttemptStateInternal.KILL_IN_PROGRESS)
          .contains(getInternalState()));
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public ContainerId getAssignedContainerID() {
    readLock.lock();
    try {
      return containerId;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public String getAssignedContainerMgrAddress() {
    readLock.lock();
    try {
      return containerNodeId.toString();
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public NodeId getNodeId() {
    readLock.lock();
    try {
      return containerNodeId;
    } finally {
      readLock.unlock();
    }
  }

  /**If container Assigned then return the node's address, otherwise null.
   */
  @Override
  public String getNodeHttpAddress() {
    readLock.lock();
    try {
      return nodeHttpAddress;
    } finally {
      readLock.unlock();
    }
  }

  /**
   * If container Assigned then return the node's rackname, otherwise null.
   */
  @Override
  public String getNodeRackName() {
    this.readLock.lock();
    try {
      return this.nodeRackName;
    } finally {
      this.readLock.unlock();
    }
  }

  @Override
  public long getLaunchTime() {
    readLock.lock();
    try {
      return launchTime;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public long getFinishTime() {
    readLock.lock();
    try {
      return finishTime;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public long getInputReadyTime() {
    readLock.lock();
    try {
      return this.reportedStatus.shuffleFinishTime;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public long getOutputReadyTime() {
    readLock.lock();
    try {
      return this.reportedStatus.sortFinishTime;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public int getShufflePort() {
    readLock.lock();
    try {
      return shufflePort;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Task getTask() {
    return appContext.getDAG()
        .getVertex(attemptId.getTaskID().getVertexID())
        .getTask(attemptId.getTaskID());
  }

  @SuppressWarnings("unchecked")
  @Override
  public void handle(TaskAttemptEvent event) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing TaskAttemptEvent " + event.getTaskAttemptID()
          + " of type " + event.getType() + " while in state "
          + getInternalState() + ". Event: " + event);
    }
    writeLock.lock();
    try {
      final TaskAttemptStateInternal oldState = getInternalState();
      try {
        stateMachine.doTransition(event.getType(), event);
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle this event at current state for "
            + this.attemptId, e);
        eventHandler.handle(new DAGEventDiagnosticsUpdate(
            this.attemptId.getTaskID().getVertexID().getDAGId(),
            "Invalid event " + event.getType() +
            " on TaskAttempt " + this.attemptId));
        eventHandler.handle(
            new DAGEvent(
                this.attemptId.getTaskID().getVertexID().getDAGId(),
                DAGEventType.INTERNAL_ERROR)
            );
      }
      if (oldState != getInternalState()) {
          LOG.info(attemptId + " TaskAttempt Transitioned from "
           + oldState + " to "
           + getInternalState());
      }
    } finally {
      writeLock.unlock();
    }
  }

  @VisibleForTesting
  public TaskAttemptStateInternal getInternalState() {
    readLock.lock();
    try {
      return stateMachine.getCurrentState();
    } finally {
      readLock.unlock();
    }
  }

  private static TaskAttemptState getExternalState(
      TaskAttemptStateInternal smState) {
    switch (smState) {
    case NEW:
    case START_WAIT:
      return TaskAttemptState.STARTING;
    case RUNNING:
      return TaskAttemptState.RUNNING;
    case COMMIT_PENDING:
    case OUTPUT_CONSUMABLE:
      return TaskAttemptState.COMMIT_PENDING;
    case FAILED:
    case FAIL_IN_PROGRESS:
      return TaskAttemptState.FAILED;
    case KILLED:
    case KILL_IN_PROGRESS:
      return TaskAttemptState.KILLED;
    case SUCCEEDED:
      return TaskAttemptState.SUCCEEDED;
    default:
      throw new TezUncheckedException("Attempt to convert invalid "
          + "stateMachineTaskAttemptState to externalTaskAttemptState: "
          + smState);
    }
  }

  @Override
  public boolean getIsRescheduled() {
    return isRescheduled;
  }

  @SuppressWarnings("unchecked")
  private void sendEvent(Event<?> event) {
    this.eventHandler.handle(event);
  }

  // always called in write lock
  private void setFinishTime() {
    // set the finish time only if launch time is set
    if (launchTime != 0) {
      finishTime = clock.getTime();
    }
  }

  // TOOD Merge some of these JobCounter events.
  private static DAGEventCounterUpdate createJobCounterUpdateEventTALaunched(
      TaskAttemptImpl ta) {
    DAGEventCounterUpdate jce =
        new DAGEventCounterUpdate(
            ta.getDAGID()
            );
    jce.addCounterUpdate(DAGCounter.TOTAL_LAUNCHED_TASKS, 1);
    return jce;
  }

  private static DAGEventCounterUpdate createJobCounterUpdateEventSlotMillis(
      TaskAttemptImpl ta) {
    DAGEventCounterUpdate jce =
        new DAGEventCounterUpdate(
            ta.getDAGID()
            );

//    long slotMillis = computeSlotMillis(ta);
//    jce.addCounterUpdate(DAGCounter.SLOTS_MILLIS_TASKS, slotMillis);
    return jce;
  }

  private static DAGEventCounterUpdate createJobCounterUpdateEventTATerminated(
      TaskAttemptImpl taskAttempt, boolean taskAlreadyCompleted,
      TaskAttemptStateInternal taState) {
    DAGEventCounterUpdate jce =
        new DAGEventCounterUpdate(
            taskAttempt.getDAGID());

    if (taState == TaskAttemptStateInternal.FAILED) {
      jce.addCounterUpdate(DAGCounter.NUM_FAILED_TASKS, 1);
    } else if (taState == TaskAttemptStateInternal.KILLED) {
      jce.addCounterUpdate(DAGCounter.NUM_KILLED_TASKS, 1);
    }

//    long slotMillisIncrement = computeSlotMillis(taskAttempt);
//    if (!taskAlreadyCompleted) {
//      // dont double count the elapsed time
//      jce.addCounterUpdate(DAGCounter.SLOTS_MILLIS_TASKS, slotMillisIncrement);
//    }

    return jce;
  }

//  private static long computeSlotMillis(TaskAttemptImpl taskAttempt) {
//    int slotMemoryReq =
//        taskAttempt.taskResource.getMemory();
//
//    int minSlotMemSize =
//        taskAttempt.appContext.getClusterInfo().getMinContainerCapability()
//            .getMemory();
//
//    int simSlotsRequired =
//        minSlotMemSize == 0 ? 0 : (int) Math.ceil((float) slotMemoryReq
//            / minSlotMemSize);
//
//    long slotMillisIncrement =
//        simSlotsRequired
//            * (taskAttempt.getFinishTime() - taskAttempt.getLaunchTime());
//    return slotMillisIncrement;
//  }

  // TODO: JobHistory
  // TODO Change to return a JobHistoryEvent.
  /*
  private static
      TaskAttemptUnsuccessfulCompletionEvent
  createTaskAttemptUnsuccessfulCompletionEvent(TaskAttemptImpl taskAttempt,
      TaskAttemptStateInternal attemptState) {
    TaskAttemptUnsuccessfulCompletionEvent tauce =
    new TaskAttemptUnsuccessfulCompletionEvent(
        TypeConverter.fromYarn(taskAttempt.attemptId),
        TypeConverter.fromYarn(taskAttempt.attemptId.getTaskId()
            .getTaskType()), attemptState.toString(),
        taskAttempt.finishTime,
        taskAttempt.containerNodeId == null ? "UNKNOWN"
            : taskAttempt.containerNodeId.getHost(),
        taskAttempt.containerNodeId == null ? -1
            : taskAttempt.containerNodeId.getPort(),
        taskAttempt.nodeRackName == null ? "UNKNOWN"
            : taskAttempt.nodeRackName,
        StringUtils.join(
            LINE_SEPARATOR, taskAttempt.getDiagnostics()), taskAttempt
            .getProgressSplitBlock().burst());
    return tauce;
  }

  // TODO Incorporate MAPREDUCE-4838
  private JobHistoryEvent createTaskAttemptStartedEvent() {
    TaskAttemptStartedEvent tase = new TaskAttemptStartedEvent(
        TypeConverter.fromYarn(attemptId), TypeConverter.fromYarn(taskId
            .getTaskType()), launchTime, trackerName, httpPort, shufflePort,
        containerId, "", "");
    return new JobHistoryEvent(jobId, tase);

  }
  */

//  private WrappedProgressSplitsBlock getProgressSplitBlock() {
//    return null;
//    // TODO
//    /*
//    readLock.lock();
//    try {
//      if (progressSplitBlock == null) {
//        progressSplitBlock = new WrappedProgressSplitsBlock(conf.getInt(
//            MRJobConfig.MR_AM_NUM_PROGRESS_SPLITS,
//            MRJobConfig.DEFAULT_MR_AM_NUM_PROGRESS_SPLITS));
//      }
//      return progressSplitBlock;
//    } finally {
//      readLock.unlock();
//    }
//    */
//  }

  private void updateProgressSplits() {
//    double newProgress = reportedStatus.progress;
//    newProgress = Math.max(Math.min(newProgress, 1.0D), 0.0D);
//    TezCounters counters = reportedStatus.counters;
//    if (counters == null)
//      return;
//
//    WrappedProgressSplitsBlock splitsBlock = getProgressSplitBlock();
//    if (splitsBlock != null) {
//      long now = clock.getTime();
//      long start = getLaunchTime();
//
//      if (start == 0)
//        return;
//
//      if (start != 0 && now - start <= Integer.MAX_VALUE) {
//        splitsBlock.getProgressWallclockTime().extend(newProgress,
//            (int) (now - start));
//      }
//
//      TezCounter cpuCounter = counters.findCounter(TaskCounter.CPU_MILLISECONDS);
//      if (cpuCounter != null && cpuCounter.getValue() <= Integer.MAX_VALUE) {
//        splitsBlock.getProgressCPUTime().extend(newProgress,
//            (int) cpuCounter.getValue()); // long to int? TODO: FIX. Same below
//      }
//
//      TezCounter virtualBytes = counters
//        .findCounter(TaskCounter.VIRTUAL_MEMORY_BYTES);
//      if (virtualBytes != null) {
//        splitsBlock.getProgressVirtualMemoryKbytes().extend(newProgress,
//            (int) (virtualBytes.getValue() / (MEMORY_SPLITS_RESOLUTION)));
//      }
//
//      TezCounter physicalBytes = counters
//        .findCounter(TaskCounter.PHYSICAL_MEMORY_BYTES);
//      if (physicalBytes != null) {
//        splitsBlock.getProgressPhysicalMemoryKbytes().extend(newProgress,
//            (int) (physicalBytes.getValue() / (MEMORY_SPLITS_RESOLUTION)));
//      }
//    }
  }

//  private void maybeSendSpeculatorContainerRequired() {
//    if (!speculatorContainerRequestSent) {
//      sendEvent(new SpeculatorEvent(getID().getTaskID(), +1));
//      speculatorContainerRequestSent = true;
//    }
//  }
//
//  private void maybeSendSpeculatorContainerNoLongerRequired() {
//    if (speculatorContainerRequestSent) {
//      sendEvent(new SpeculatorEvent(getID().getTaskID(), -1));
//      speculatorContainerRequestSent = false;
//    }
//  }

  private void sendTaskAttemptCleanupEvent() {
//    TaskAttemptContext taContext =
//        new TaskAttemptContextImpl(this.conf,
//            TezMRTypeConverter.fromTez(this.attemptId));
//    sendEvent(new TaskCleanupEvent(this.attemptId, this.committer, taContext));
  }

  protected String[] resolveHosts(String[] src) {
    return TaskAttemptImplHelpers.resolveHosts(src);
  }

  @SuppressWarnings("unchecked")
  protected void logJobHistoryAttemptStarted() {
    final String containerIdStr = containerId.toString();
    String inProgressLogsUrl = nodeHttpAddress
       + "/" + "node/containerlogs"
       + "/" + containerIdStr
       + "/" + this.appContext.getUser();
    String completedLogsUrl = "";
    if (conf.getBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED,
        YarnConfiguration.DEFAULT_LOG_AGGREGATION_ENABLED)
        && conf.get(YarnConfiguration.YARN_LOG_SERVER_URL) != null) {
      String contextStr = "v_" + getTask().getVertex().getName()
          + "_" + this.attemptId.toString();
      completedLogsUrl = conf.get(YarnConfiguration.YARN_LOG_SERVER_URL)
          + "/" + containerNodeId.toString()
          + "/" + containerIdStr
          + "/" + contextStr
          + "/" + this.appContext.getUser();
    }
    TaskAttemptStartedEvent startEvt = new TaskAttemptStartedEvent(
        attemptId, getTask().getVertex().getName(),
        launchTime, containerId, containerNodeId,
        inProgressLogsUrl, completedLogsUrl);
    eventHandler.handle(new DAGHistoryEvent(startEvt));
  }

  @SuppressWarnings("unchecked")
  protected void logJobHistoryAttemptFinishedEvent(TaskAttemptStateInternal state) {
    //Log finished events only if an attempt started.
    if (getLaunchTime() == 0) return;

    TaskAttemptFinishedEvent finishEvt = new TaskAttemptFinishedEvent(
        attemptId, getTask().getVertex().getName(), getLaunchTime(),
        getFinishTime(), TaskAttemptState.SUCCEEDED, "",
        getCounters());
    // FIXME how do we store information regd completion events
    eventHandler.handle(new DAGHistoryEvent(finishEvt));
  }

  @SuppressWarnings("unchecked")
  protected void logJobHistoryAttemptUnsuccesfulCompletion(
      TaskAttemptState state) {
    TaskAttemptFinishedEvent finishEvt = new TaskAttemptFinishedEvent(
        attemptId, getTask().getVertex().getName(), getLaunchTime(),
        clock.getTime(), state,
        StringUtils.join(
            LINE_SEPARATOR, getDiagnostics()),
        getCounters());
    // FIXME how do we store information regd completion events
    eventHandler.handle(new DAGHistoryEvent(finishEvt));
  }

  //////////////////////////////////////////////////////////////////////////////
  //                   Start of Transition Classes                            //
  //////////////////////////////////////////////////////////////////////////////

  protected static class ScheduleTaskattemptTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      TaskAttemptEventSchedule scheduleEvent = (TaskAttemptEventSchedule) event;
      // Event to speculator - containerNeeded++
      //ta.maybeSendSpeculatorContainerRequired();

      // TODO Creating the remote task here may not be required in case of
      // recovery.

      // Create the remote task.
      TezTaskContext remoteTaskContext = ta.createRemoteTask();
      // Create startTaskRequest

      String[] requestHosts = new String[0];
      String[] requestRacks = new String[0];

      // Compute node/rack location request even if re-scheduled.
      Set<String> racks = new HashSet<String>();
      if (ta.locationHint != null) {
        if (ta.locationHint.getRacks() != null) {
          racks.addAll(ta.locationHint.getRacks());
        }
        if (ta.locationHint.getDataLocalHosts() != null) {
          for (String host : ta.locationHint.getDataLocalHosts()) {
            racks.add(RackResolver.resolve(host).getNetworkLocation());
          }
          requestHosts = ta.resolveHosts(ta.locationHint.getDataLocalHosts()
              .toArray(new String[ta.locationHint.getDataLocalHosts().size()]));
        }
      }
      requestRacks = racks.toArray(new String[racks.size()]);

      ta.taskHosts.addAll(Arrays.asList(requestHosts));
      ta.taskRacks = racks;

      // Ask for hosts / racks only if not a re-scheduled task.
      if (ta.isRescheduled) {
        requestHosts = new String[0];
        requestRacks = new String[0];
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("Asking for container launch with taskAttemptContext: "
            + remoteTaskContext);
      }
      // Send out a launch request to the scheduler.
      AMSchedulerEventTALaunchRequest launchRequestEvent =
          new AMSchedulerEventTALaunchRequest(ta.attemptId,
              ta.taskResource,
              ta.localResources, remoteTaskContext, ta,
              ta.credentials, ta.jobToken, requestHosts,
              requestRacks,
              scheduleEvent.getPriority(), ta.environment, //ta.javaOpts,
              ta.conf);
      ta.sendEvent(launchRequestEvent);
    }
  }

  protected static class DiagnosticInformationUpdater implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      TaskAttemptEventDiagnosticsUpdate diagEvent = (TaskAttemptEventDiagnosticsUpdate) event;
      if (LOG.isDebugEnabled()) {
        LOG.debug("Diagnostics update for " + ta.attemptId + ": "
            + diagEvent.getDiagnosticInfo());
      }
      ta.addDiagnosticInfo(diagEvent.getDiagnosticInfo());
    }
  }

  protected static class TerminateTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {

    TerminatedTransitionHelper helper;

    public TerminateTransition(TerminatedTransitionHelper helper) {
      this.helper = helper;
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      ta.setFinishTime();

      if (event instanceof DiagnosableEvent) {
        ta.addDiagnosticInfo(((DiagnosableEvent) event).getDiagnosticInfo());
      }

      ta.sendEvent(createJobCounterUpdateEventTATerminated(ta, false,
          helper.getTaskAttemptStateInternal()));
      if (ta.getLaunchTime() != 0) {
        // TODO For cases like this, recovery goes for a toss, since the the
        // attempt will not exist in the history file.
        ta.logJobHistoryAttemptUnsuccesfulCompletion(helper
            .getTaskAttemptState());
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Not generating HistoryFinish event since start event not "
              + "generated for taskAttempt: " + ta.getID());
        }
      }
      // Send out events to the Task - indicating TaskAttemptTermination(F/K)
      ta.sendEvent(new TaskEventTAUpdate(ta.attemptId, helper
          .getTaskEventType()));
    }
  }

  protected static class StartedTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent origEvent) {
      TaskAttemptEventStartedRemotely event = (TaskAttemptEventStartedRemotely) origEvent;

      Container container = ta.appContext.getAllContainers()
          .get(event.getContainerId()).getContainer();

      ta.containerId = event.getContainerId();
      ta.containerNodeId = container.getNodeId();
      ta.nodeHttpAddress = container.getNodeHttpAddress();
      ta.nodeRackName = RackResolver.resolve(ta.containerNodeId.getHost())
          .getNetworkLocation();

      ta.launchTime = ta.clock.getTime();
      ta.shufflePort = event.getShufflePort();

      // TODO Resolve to host / IP in case of a local address.
      InetSocketAddress nodeHttpInetAddr = NetUtils
          .createSocketAddr(ta.nodeHttpAddress); // TODO: Costly?
      ta.trackerName = nodeHttpInetAddr.getHostName();
      ta.httpPort = nodeHttpInetAddr.getPort();
      ta.sendEvent(createJobCounterUpdateEventTALaunched(ta));

      LOG.info("TaskAttempt: [" + ta.attemptId + "] started."
          + " Is using containerId: [" + ta.containerId + "]" + " on NM: ["
          + ta.containerNodeId + "]");

      // JobHistoryEvent
      ta.logJobHistoryAttemptStarted();

      // Compute LOLCAITY counter for this task.
      if (ta.taskHosts.contains(ta.containerNodeId.getHost())) {
        ta.localityCounter = DAGCounter.DATA_LOCAL_TASKS;
      } else if (ta.taskRacks.contains(ta.nodeRackName)) {
        ta.localityCounter = DAGCounter.RACK_LOCAL_TASKS;
      } else {
        // Not computing this if the task does not have locality information.
        if (ta.locationHint != null) {
          ta.localityCounter = DAGCounter.OTHER_LOCAL_TASKS;
        }
      }


      // Inform the speculator about the container assignment.
      //ta.maybeSendSpeculatorContainerNoLongerRequired();
      // Inform speculator about startTime
      //ta.sendEvent(new SpeculatorEvent(ta.attemptId, true, ta.launchTime));

      // Inform the Task
      ta.sendEvent(new TaskEventTAUpdate(ta.attemptId,
          TaskEventType.T_ATTEMPT_LAUNCHED));

      ta.taskHeartbeatHandler.register(ta.attemptId);
    }
  }

  protected static class TerminatedBeforeRunningTransition extends
      TerminateTransition {

    public TerminatedBeforeRunningTransition(
        TerminatedTransitionHelper helper) {
      super(helper);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      // Inform the scheduler
      ta.sendEvent(new AMSchedulerEventTAEnded(ta, ta.containerId, helper
          .getTaskAttemptState()));
      // Decrement speculator container request.
      //ta.maybeSendSpeculatorContainerNoLongerRequired();
    }
  }

  protected static class NodeFailedBeforeRunningTransition extends
      TerminatedBeforeRunningTransition {

    public NodeFailedBeforeRunningTransition() {
      super(KILLED_HELPER);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      TaskAttemptEventNodeFailed nfEvent = (TaskAttemptEventNodeFailed) event;
      ta.addDiagnosticInfo(nfEvent.getDiagnosticInfo());
    }
  }

  protected static class ContainerTerminatingBeforeRunningTransition extends
      TerminatedBeforeRunningTransition {

    public ContainerTerminatingBeforeRunningTransition() {
      super(FAILED_HELPER);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      TaskAttemptEventContainerTerminating tEvent = (TaskAttemptEventContainerTerminating) event;
      ta.addDiagnosticInfo(tEvent.getDiagnosticInfo());
    }
  }

  protected static class ContainerCompletedBeforeRunningTransition extends
      TerminatedBeforeRunningTransition {
    public ContainerCompletedBeforeRunningTransition() {
      super(FAILED_HELPER);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      ta.sendTaskAttemptCleanupEvent();

      TaskAttemptEventContainerTerminated tEvent = (TaskAttemptEventContainerTerminated) event;
      ta.addDiagnosticInfo(tEvent.getDiagnosticInfo());
    }

  }

  protected static class StatusUpdaterTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      TaskAttemptStatus newReportedStatus = ((TaskAttemptEventStatusUpdate) event)
          .getReportedTaskAttemptStatus();
      ta.reportedStatus = newReportedStatus;
      ta.reportedStatus.taskState = ta.getState();

      // Inform speculator of status.
      //ta.sendEvent(new SpeculatorEvent(ta.reportedStatus, ta.clock.getTime()));

      ta.updateProgressSplits();

      // Inform the job about fetch failures if they exist.
      if (ta.reportedStatus.fetchFailedMaps != null
          && ta.reportedStatus.fetchFailedMaps.size() > 0) {
        ta.sendEvent(new VertexEventTaskAttemptFetchFailure(ta.attemptId,
            ta.reportedStatus.fetchFailedMaps));
      }
      // TODO at some point. Nodes may be interested in FetchFailure info.
      // Can be used to blacklist nodes.
    }
  }

  protected static class OutputConsumableTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      TaskAttemptEventOutputConsumable orEvent = (TaskAttemptEventOutputConsumable) event;
      ta.shufflePort = orEvent.getOutputContext().getShufflePort();
      ta.sendEvent(new TaskEventTAUpdate(ta.attemptId,
          TaskEventType.T_ATTEMPT_OUTPUT_CONSUMABLE));
    }
  }

  protected static class CommitPendingTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      ta.sendEvent(new TaskEventTAUpdate(ta.attemptId,
          TaskEventType.T_ATTEMPT_COMMIT_PENDING));
    }
  }

  protected static class SucceededTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {

      ta.setFinishTime();
      // Inform the speculator.
      //ta.sendEvent(new SpeculatorEvent(ta.reportedStatus, ta.finishTime));
      // Send out history event.
      ta.logJobHistoryAttemptFinishedEvent(TaskAttemptStateInternal.SUCCEEDED);
      ta.sendEvent(createJobCounterUpdateEventSlotMillis(ta));

      // Inform the Scheduler.
      ta.sendEvent(new AMSchedulerEventTAEnded(ta, ta.containerId,
          TaskAttemptState.SUCCEEDED));

      // Inform the task.
      ta.sendEvent(new TaskEventTAUpdate(ta.attemptId,
          TaskEventType.T_ATTEMPT_SUCCEEDED));

      // Unregister from the TaskHeartbeatHandler.
      ta.taskHeartbeatHandler.unregister(ta.attemptId);

      // TODO maybe. For reuse ... Stacking pulls for a reduce task, even if the
      // TA finishes independently. // Will likely be the Job's responsibility.

    }
  }

  protected static class TerminatedWhileRunningTransition extends
      TerminatedBeforeRunningTransition {

    public TerminatedWhileRunningTransition(
        TerminatedTransitionHelper helper) {
      super(helper);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      ta.taskHeartbeatHandler.unregister(ta.attemptId);
    }
  }

  protected static class ContainerCompletedWhileRunningTransition extends
      TerminatedBeforeRunningTransition {
    public ContainerCompletedWhileRunningTransition() {
      super(FAILED_HELPER);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      ta.sendTaskAttemptCleanupEvent();
    }
  }

  protected static class CommitPendingAtOutputConsumableTransition extends
      CommitPendingTransition {

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      // TODO Figure out the interaction between OUTPUT_CONSUMABLE AND
      // COMMIT_PENDING, Ideally both should not exist for the same task.
      super.transition(ta, event);
      LOG.info("Received a commit pending while in the OutputConsumable state");
    }
  }

  protected static class ContainerCompletedWhileTerminating implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      ta.sendTaskAttemptCleanupEvent();
      TaskAttemptEventContainerTerminated tEvent = (TaskAttemptEventContainerTerminated) event;
      ta.addDiagnosticInfo(tEvent.getDiagnosticInfo());
    }

  }

  protected static class TerminatedAfterSuccessTransition extends
      TerminatedBeforeRunningTransition {

    public TerminatedAfterSuccessTransition(TerminatedTransitionHelper helper) {
      super(helper);
    }

    @Override
    public void transition(TaskAttemptImpl ta, TaskAttemptEvent event) {
      super.transition(ta, event);
      ta.sendTaskAttemptCleanupEvent();
    }

  }

  private void initTaskAttemptStatus(TaskAttemptStatus result) {
    result.progress = 0.0f;
    // result.phase = Phase.STARTING;
    result.stateString = "NEW";
    result.taskState = TaskAttemptState.NEW;
    //TezCounters counters = EMPTY_COUNTERS;
    //result.counters = counters;
  }

  private void addDiagnosticInfo(String diag) {
    if (diag != null && !diag.equals("")) {
      diagnostics.add(diag);
    }
  }

  protected interface TerminatedTransitionHelper {

    public TaskAttemptStateInternal getTaskAttemptStateInternal();

    public TaskAttemptState getTaskAttemptState();

    public TaskEventType getTaskEventType();
  }

  protected static class FailedTransitionHelper implements
      TerminatedTransitionHelper {
    public TaskAttemptStateInternal getTaskAttemptStateInternal() {
      return TaskAttemptStateInternal.FAILED;
    }

    @Override
    public TaskAttemptState getTaskAttemptState() {
      return TaskAttemptState.FAILED;
    }

    @Override
    public TaskEventType getTaskEventType() {
      return TaskEventType.T_ATTEMPT_FAILED;
    }
  }

  protected static class KilledTransitionHelper implements
      TerminatedTransitionHelper {

    @Override
    public TaskAttemptStateInternal getTaskAttemptStateInternal() {
      return TaskAttemptStateInternal.KILLED;
    }

    @Override
    public TaskAttemptState getTaskAttemptState() {
      return TaskAttemptState.KILLED;
    }

    @Override
    public TaskEventType getTaskEventType() {
      return TaskEventType.T_ATTEMPT_KILLED;
    }
  }

  @Override
  public String toString() {
    return getID().toString();
  }

  @Override
  public Map<String, LocalResource> getLocalResources() {
    return this.localResources;
  }

  @Override
  public Map<String, String> getEnvironment() {
    return this.environment;
  }

  @Override
  public String getJavaOpts() {
	return this.javaOpts;
  }

}
