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

package org.apache.hadoop.mapreduce.v2.app2.job.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import junit.framework.Assert;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapTaskAttemptImpl2;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.jobhistory.ContainerHeartbeatHandler;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.TaskAttemptUnsuccessfulCompletion;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitMetaInfo;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.JobState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptReport;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app2.AppContext;
import org.apache.hadoop.mapreduce.v2.app2.ControlledClock;
import org.apache.hadoop.mapreduce.v2.app2.MRApp;
import org.apache.hadoop.mapreduce.v2.app2.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app2.TaskHeartbeatHandler;
import org.apache.hadoop.mapreduce.v2.app2.job.Job;
import org.apache.hadoop.mapreduce.v2.app2.job.Task;
import org.apache.hadoop.mapreduce.v2.app2.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app2.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventDiagnosticsUpdate;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventContainerTerminated;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventContainerTerminating;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventFailRequest;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventKillRequest;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventStartedRemotely;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventSchedule;
import org.apache.hadoop.mapreduce.v2.app2.rm.AMSchedulerTALaunchRequestEvent;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerMap;
import org.apache.hadoop.mapreduce.v2.util.MRBuilderUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.Clock;
import org.apache.hadoop.yarn.ClusterInfo;
import org.apache.hadoop.yarn.SystemClock;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.event.Event;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.apache.tez.mapreduce.task.MapOnlyTask;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"unchecked", "rawtypes"})
public class TestTaskAttempt{

  // TODO TEZAM4 This may need to be test specific.
  private static final String MAP_ONLY_TASK_CLASS_NAME = MapOnlyTask.class.getName();
  
  static public class StubbedFS extends RawLocalFileSystem {
    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
      return new FileStatus(1, false, 1, 1, 1, f);
    }
  }

  @Test
  public void testMRAppHistoryForMap() throws Exception {
    MRApp app = new FailingAttemptsMRApp(1, 0);
    testMRAppHistory(app);
  }

  @Test
  public void testMRAppHistoryForReduce() throws Exception {
    MRApp app = new FailingAttemptsMRApp(0, 1);
    testMRAppHistory(app);
  }

  @Test
  public void testSingleRackRequest() throws Exception {
    TaskAttemptImpl.ScheduleTaskattemptTransition sta =
        new TaskAttemptImpl.ScheduleTaskattemptTransition();

    EventHandler eventHandler = mock(EventHandler.class);
    String[] hosts = new String[3];
    hosts[0] = "host1";
    hosts[1] = "host2";
    hosts[2] = "host3";
    TaskSplitMetaInfo splitInfo = new TaskSplitMetaInfo(hosts, 0,
        128 * 1024 * 1024l);

    TaskAttemptImpl mockTaskAttempt = createMapTaskAttemptImpl2ForTest(
        eventHandler, splitInfo);
    TaskAttemptEventSchedule mockTAEvent = mock(TaskAttemptEventSchedule.class);
    doReturn(false).when(mockTAEvent).isRescheduled();

    sta.transition(mockTaskAttempt, mockTAEvent);

    ArgumentCaptor<Event> arg = ArgumentCaptor.forClass(Event.class);
    verify(eventHandler, times(2)).handle(arg.capture());
    if (!(arg.getAllValues().get(1) instanceof AMSchedulerTALaunchRequestEvent)) {
      Assert.fail("Second Event not of type ContainerRequestEvent");
    }
    AMSchedulerTALaunchRequestEvent tlrE = (AMSchedulerTALaunchRequestEvent) arg
        .getAllValues().get(1);
    String[] requestedRacks = tlrE.getRacks();
    // Only a single occurrence of /DefaultRack
    assertEquals(1, requestedRacks.length);
  }

  @Test
  public void testHostResolveAttempt() throws Exception {
    TaskAttemptImpl.ScheduleTaskattemptTransition sta =
        new TaskAttemptImpl.ScheduleTaskattemptTransition();

    EventHandler eventHandler = mock(EventHandler.class);
    String hosts[] = new String[] {"192.168.1.1", "host2", "host3"};
    String resolved[] = new String[] {"host1", "host2", "host3"};
    TaskSplitMetaInfo splitInfo =
        new TaskSplitMetaInfo(hosts, 0, 128 * 1024 * 1024l);

    TaskAttemptImpl mockTaskAttempt =
        createMapTaskAttemptImpl2ForTest(eventHandler, splitInfo);
    TaskAttemptImpl spyTa = spy(mockTaskAttempt);
    when(spyTa.resolveHosts(hosts)).thenReturn(resolved);

    TaskAttemptEventSchedule mockTAEvent = mock(TaskAttemptEventSchedule.class);
    doReturn(false).when(mockTAEvent).isRescheduled();

    sta.transition(spyTa, mockTAEvent);
    verify(spyTa).resolveHosts(hosts);
    ArgumentCaptor<Event> arg = ArgumentCaptor.forClass(Event.class);
    verify(eventHandler, times(2)).handle(arg.capture());
    if (!(arg.getAllValues().get(1) instanceof AMSchedulerTALaunchRequestEvent)) {
      Assert.fail("Second Event not of type ContainerRequestEvent");
    }
    Map<String, Boolean> expected = new HashMap<String, Boolean>();
    expected.put("host1", true);
    expected.put("host2", true);
    expected.put("host3", true);
    AMSchedulerTALaunchRequestEvent cre =
        (AMSchedulerTALaunchRequestEvent) arg.getAllValues().get(1);
    String[] requestedHosts = cre.getHosts();
    for (String h : requestedHosts) {
      expected.remove(h);
    }
    assertEquals(0, expected.size());
  }
  
  @Test
  public void testSlotMillisCounterUpdate() throws Exception {
    verifySlotMillis(2048, 2048, 1024);
    verifySlotMillis(2048, 1024, 1024);
    verifySlotMillis(10240, 1024, 2048);
  }

  public void verifySlotMillis(int mapMemMb, int reduceMemMb,
      int minContainerSize) throws Exception {
    Clock actualClock = new SystemClock();
    ControlledClock clock = new ControlledClock(actualClock);
    clock.setTime(10);
    MRApp app =
        new MRApp(1, 1, false, "testSlotMillisCounterUpdate", true, clock);
    Configuration conf = new Configuration();
    conf.setInt(MRJobConfig.MAP_MEMORY_MB, mapMemMb);
    conf.setInt(MRJobConfig.REDUCE_MEMORY_MB, reduceMemMb);
    app.setClusterInfo(new ClusterInfo(BuilderUtils
        .newResource(minContainerSize, 1), BuilderUtils.newResource(10240,1)));

    Job job = app.submit(conf);
    app.waitForState(job, JobState.RUNNING);
    Map<TaskId, Task> tasks = job.getTasks();
    Assert.assertEquals("Num tasks is not correct", 2, tasks.size());
    Iterator<Task> taskIter = tasks.values().iterator();
    Task mTask = taskIter.next();
    app.waitForState(mTask, TaskState.RUNNING);
    Task rTask = taskIter.next();
    app.waitForState(rTask, TaskState.RUNNING);
    Map<TaskAttemptId, TaskAttempt> mAttempts = mTask.getAttempts();
    Assert.assertEquals("Num attempts is not correct", 1, mAttempts.size());
    Map<TaskAttemptId, TaskAttempt> rAttempts = rTask.getAttempts();
    Assert.assertEquals("Num attempts is not correct", 1, rAttempts.size());
    TaskAttempt mta = mAttempts.values().iterator().next();
    TaskAttempt rta = rAttempts.values().iterator().next();
    app.waitForState(mta, TaskAttemptState.RUNNING);
    app.waitForState(rta, TaskAttemptState.RUNNING);

    clock.setTime(11);
    app.getContext()
        .getEventHandler()
        .handle(new TaskAttemptEvent(mta.getID(), TaskAttemptEventType.TA_DONE));
    app.getContext()
        .getEventHandler()
        .handle(new TaskAttemptEvent(rta.getID(), TaskAttemptEventType.TA_DONE));
    app.waitForState(job, JobState.SUCCEEDED);
    Assert.assertEquals(mta.getFinishTime(), 11);
    Assert.assertEquals(mta.getLaunchTime(), 10);
    Assert.assertEquals(rta.getFinishTime(), 11);
    Assert.assertEquals(rta.getLaunchTime(), 10);
    Assert.assertEquals((int) Math.ceil((float) mapMemMb / minContainerSize),
        job.getAllCounters().findCounter(JobCounter.SLOTS_MILLIS_MAPS)
            .getValue());
    Assert.assertEquals(
        (int) Math.ceil((float) reduceMemMb / minContainerSize), job
            .getAllCounters().findCounter(JobCounter.SLOTS_MILLIS_REDUCES)
            .getValue());
  }
  
  private TaskAttemptImpl createMapTaskAttemptImpl2ForTest(
      EventHandler eventHandler, TaskSplitMetaInfo taskSplitMetaInfo) {
    Clock clock = new SystemClock();
    return createMapTaskAttemptImpl2ForTest(eventHandler, taskSplitMetaInfo, clock);
  }
  
  private TaskAttemptImpl createMapTaskAttemptImpl2ForTest(
      EventHandler eventHandler, TaskSplitMetaInfo taskSplitMetaInfo, Clock clock) {
    ApplicationId appId = BuilderUtils.newApplicationId(1, 1);
    JobId jobId = MRBuilderUtils.newJobId(appId, 1);
    TaskId taskId = MRBuilderUtils.newTaskId(jobId, 1, TaskType.MAP);
    TaskAttemptListener taListener = mock(TaskAttemptListener.class);
    Path jobFile = mock(Path.class);
    JobConf jobConf = new JobConf();
    OutputCommitter outputCommitter = mock(OutputCommitter.class);
    TaskAttemptImpl taImpl =
        new MapTaskAttemptImpl2(taskId, 1, eventHandler, jobFile, 1,
            taskSplitMetaInfo, jobConf, taListener, outputCommitter, null,
            null, clock, mock(TaskHeartbeatHandler.class), null,
            MAP_ONLY_TASK_CLASS_NAME);
    return taImpl;
  }

  private void testMRAppHistory(MRApp app) throws Exception {
    Configuration conf = new Configuration();
    Job job = app.submit(conf);
    app.waitForState(job, JobState.FAILED);
    Map<TaskId, Task> tasks = job.getTasks();

    Assert.assertEquals("Num tasks is not correct", 1, tasks.size());
    Task task = tasks.values().iterator().next();
    Assert.assertEquals("Task state not correct", TaskState.FAILED, task
        .getReport().getTaskState());
    Map<TaskAttemptId, TaskAttempt> attempts = tasks.values().iterator().next()
        .getAttempts();
    Assert.assertEquals("Num attempts is not correct", 4, attempts.size());

    Iterator<TaskAttempt> it = attempts.values().iterator();
    TaskAttemptReport report = it.next().getReport();
    Assert.assertEquals("Attempt state not correct", TaskAttemptState.FAILED,
        report.getTaskAttemptState());
    Assert.assertEquals("Diagnostic Information is not Correct",
        "Test Diagnostic Event", report.getDiagnosticInfo());
    report = it.next().getReport();
    Assert.assertEquals("Attempt state not correct", TaskAttemptState.FAILED,
        report.getTaskAttemptState());
  }

  static class FailingAttemptsMRApp extends MRApp {
    FailingAttemptsMRApp(int maps, int reduces) {
      super(maps, reduces, true, "FailingAttemptsMRApp", true);
    }

    @Override
    protected void attemptLaunched(TaskAttemptId attemptID) {
      getContext().getEventHandler().handle(
          new TaskAttemptEventFailRequest(attemptID, "Test Diagnostic Event"));
    }

    // TODO This will execute in a separate thread. The assert is not very useful.
    protected EventHandler<JobHistoryEvent> createJobHistoryHandler(
        AppContext context) {
      return new EventHandler<JobHistoryEvent>() {
        @Override
        public void handle(JobHistoryEvent event) {
          if (event.getType() == org.apache.hadoop.mapreduce.jobhistory.EventType.MAP_ATTEMPT_FAILED) {
            TaskAttemptUnsuccessfulCompletion datum = (TaskAttemptUnsuccessfulCompletion) event
                .getHistoryEvent().getDatum();
            // TODO Useless assert in a separate thread. Caues test to hang.
            Assert.assertEquals("Diagnostic Information is not Correct",
                "Test Diagnostic Event", datum.get(8).toString());
          }
        }
      };
    }
  }

  @Test
  public void testLaunchFailedWhileKilling() throws Exception {
    ApplicationId appId = BuilderUtils.newApplicationId(1, 2);
    ApplicationAttemptId appAttemptId = 
      BuilderUtils.newApplicationAttemptId(appId, 0);
    JobId jobId = MRBuilderUtils.newJobId(appId, 1);
    TaskId taskId = MRBuilderUtils.newTaskId(jobId, 1, TaskType.MAP);
    TaskAttemptId attemptId = MRBuilderUtils.newTaskAttemptId(taskId, 0);
    Path jobFile = mock(Path.class);
    
    MockEventHandler eventHandler = new MockEventHandler();
    TaskAttemptListener taListener = mock(TaskAttemptListener.class);
    when(taListener.getAddress()).thenReturn(new InetSocketAddress("localhost", 0));
    
    JobConf jobConf = new JobConf();
    jobConf.setClass("fs.file.impl", StubbedFS.class, FileSystem.class);
    jobConf.setBoolean("fs.file.impl.disable.cache", true);
    jobConf.set(JobConf.MAPRED_MAP_TASK_ENV, "");
    jobConf.set(MRJobConfig.APPLICATION_ATTEMPT_ID, "10");
    
    TaskSplitMetaInfo splits = mock(TaskSplitMetaInfo.class);
    when(splits.getLocations()).thenReturn(new String[] {"127.0.0.1"});
    
    AppContext mockAppContext = mock(AppContext.class);
    doReturn(new ClusterInfo()).when(mockAppContext).getClusterInfo();
    
    TaskAttemptImpl taImpl =
      new MapTaskAttemptImpl2(taskId, 1, eventHandler, jobFile, 1,
          splits, jobConf, taListener,
          mock(OutputCommitter.class), mock(Token.class), new Credentials(),
          new SystemClock(), mock(TaskHeartbeatHandler.class), mockAppContext,
          MAP_ONLY_TASK_CLASS_NAME);

    NodeId nid = BuilderUtils.newNodeId("127.0.0.1", 0);
    ContainerId contId = BuilderUtils.newContainerId(appAttemptId, 3);
    Container container = mock(Container.class);
    when(container.getId()).thenReturn(contId);
    when(container.getNodeId()).thenReturn(nid);
    
    taImpl.handle(new TaskAttemptEventSchedule(attemptId, false));
    // At state STARTING.
    taImpl.handle(new TaskAttemptEventKillRequest(attemptId, null));
    // At some KILLING state.
    taImpl.handle(new TaskAttemptEventContainerTerminating(attemptId, null));
    assertFalse(eventHandler.internalError);
  }

  // TODO Add a similar test for TERMINATING.
  @Test
  public void testContainerTerminatedWhileRunning() throws Exception {
    ApplicationId appId = BuilderUtils.newApplicationId(1, 2);
    ApplicationAttemptId appAttemptId = BuilderUtils.newApplicationAttemptId(
        appId, 0);
    JobId jobId = MRBuilderUtils.newJobId(appId, 1);
    TaskId taskId = MRBuilderUtils.newTaskId(jobId, 1, TaskType.MAP);
    TaskAttemptId attemptId = MRBuilderUtils.newTaskAttemptId(taskId, 0);
    Path jobFile = mock(Path.class);

    MockEventHandler eventHandler = new MockEventHandler();
    TaskAttemptListener taListener = mock(TaskAttemptListener.class);
    when(taListener.getAddress()).thenReturn(
        new InetSocketAddress("localhost", 0));

    JobConf jobConf = new JobConf();
    jobConf.setClass("fs.file.impl", StubbedFS.class, FileSystem.class);
    jobConf.setBoolean("fs.file.impl.disable.cache", true);
    jobConf.set(JobConf.MAPRED_MAP_TASK_ENV, "");
    jobConf.set(MRJobConfig.APPLICATION_ATTEMPT_ID, "10");

    TaskSplitMetaInfo splits = mock(TaskSplitMetaInfo.class);
    when(splits.getLocations()).thenReturn(new String[] { "127.0.0.1" });

    NodeId nid = BuilderUtils.newNodeId("127.0.0.1", 0);
    ContainerId contId = BuilderUtils.newContainerId(appAttemptId, 3);
    Container container = mock(Container.class);
    when(container.getId()).thenReturn(contId);
    when(container.getNodeId()).thenReturn(nid);
    when(container.getNodeHttpAddress()).thenReturn("localhost:0");

    AppContext appCtx = mock(AppContext.class);
    AMContainerMap containers = new AMContainerMap(
        mock(ContainerHeartbeatHandler.class), mock(TaskAttemptListener.class),
        appCtx);
    containers.addContainerIfNew(container);

    doReturn(new ClusterInfo()).when(appCtx).getClusterInfo();
    doReturn(containers).when(appCtx).getAllContainers();

    TaskAttemptImpl taImpl = new MapTaskAttemptImpl2(taskId, 1, eventHandler,
        jobFile, 1, splits, jobConf, taListener, mock(OutputCommitter.class),
        mock(Token.class), new Credentials(), new SystemClock(),
        mock(TaskHeartbeatHandler.class), appCtx, MAP_ONLY_TASK_CLASS_NAME);

    taImpl.handle(new TaskAttemptEventSchedule(attemptId, false));
    // At state STARTING.
    taImpl.handle(new TaskAttemptEventStartedRemotely(attemptId, contId, null, -1));
    assertEquals("Task attempt is not in running state", taImpl.getState(),
        TaskAttemptState.RUNNING);
    taImpl.handle(new TaskAttemptEventContainerTerminated(attemptId, null));
    assertFalse(
        "InternalError occurred trying to handle TA_CONTAINER_TERMINATED",
        eventHandler.internalError);
  }

  @Test
  public void testContainerTerminatedWhileCommitting() throws Exception {
    ApplicationId appId = BuilderUtils.newApplicationId(1, 2);
    ApplicationAttemptId appAttemptId = BuilderUtils.newApplicationAttemptId(
        appId, 0);
    JobId jobId = MRBuilderUtils.newJobId(appId, 1);
    TaskId taskId = MRBuilderUtils.newTaskId(jobId, 1, TaskType.MAP);
    TaskAttemptId attemptId = MRBuilderUtils.newTaskAttemptId(taskId, 0);
    Path jobFile = mock(Path.class);

    MockEventHandler eventHandler = new MockEventHandler();
    TaskAttemptListener taListener = mock(TaskAttemptListener.class);
    when(taListener.getAddress()).thenReturn(
        new InetSocketAddress("localhost", 0));

    JobConf jobConf = new JobConf();
    jobConf.setClass("fs.file.impl", StubbedFS.class, FileSystem.class);
    jobConf.setBoolean("fs.file.impl.disable.cache", true);
    jobConf.set(JobConf.MAPRED_MAP_TASK_ENV, "");
    jobConf.set(MRJobConfig.APPLICATION_ATTEMPT_ID, "10");

    TaskSplitMetaInfo splits = mock(TaskSplitMetaInfo.class);
    when(splits.getLocations()).thenReturn(new String[] { "127.0.0.1" });

    NodeId nid = BuilderUtils.newNodeId("127.0.0.1", 0);
    ContainerId contId = BuilderUtils.newContainerId(appAttemptId, 3);
    Container container = mock(Container.class);
    when(container.getId()).thenReturn(contId);
    when(container.getNodeId()).thenReturn(nid);
    when(container.getNodeHttpAddress()).thenReturn("localhost:0");

    AppContext appCtx = mock(AppContext.class);
    AMContainerMap containers = new AMContainerMap(
        mock(ContainerHeartbeatHandler.class), mock(TaskAttemptListener.class),
        appCtx);
    containers.addContainerIfNew(container);

    doReturn(new ClusterInfo()).when(appCtx).getClusterInfo();
    doReturn(containers).when(appCtx).getAllContainers();

    TaskAttemptImpl taImpl = new MapTaskAttemptImpl2(taskId, 1, eventHandler,
        jobFile, 1, splits, jobConf, taListener, mock(OutputCommitter.class),
        mock(Token.class), new Credentials(), new SystemClock(),
        mock(TaskHeartbeatHandler.class), appCtx, MAP_ONLY_TASK_CLASS_NAME);

    taImpl.handle(new TaskAttemptEventSchedule(attemptId, false));
    // At state STARTING.
    taImpl.handle(new TaskAttemptEventStartedRemotely(attemptId, contId, null, -1));
    assertEquals("Task attempt is not in running state", taImpl.getState(),
        TaskAttemptState.RUNNING);
    taImpl.handle(new TaskAttemptEvent(attemptId,
        TaskAttemptEventType.TA_COMMIT_PENDING));
    assertEquals("Task attempt is not in commit pending state",
        taImpl.getState(), TaskAttemptState.COMMIT_PENDING);
    taImpl.handle(new TaskAttemptEventContainerTerminated(attemptId, null));
    assertFalse(
        "InternalError occurred trying to handle TA_CONTAINER_TERMINATED",
        eventHandler.internalError);
  }

  @Test
  public void testMultipleTooManyFetchFailures() throws Exception {
    ApplicationId appId = BuilderUtils.newApplicationId(1, 2);
    ApplicationAttemptId appAttemptId = BuilderUtils.newApplicationAttemptId(
        appId, 0);
    JobId jobId = MRBuilderUtils.newJobId(appId, 1);
    TaskId taskId = MRBuilderUtils.newTaskId(jobId, 1, TaskType.MAP);
    TaskAttemptId attemptId = MRBuilderUtils.newTaskAttemptId(taskId, 0);
    Path jobFile = mock(Path.class);

    MockEventHandler eventHandler = new MockEventHandler();
    TaskAttemptListener taListener = mock(TaskAttemptListener.class);
    when(taListener.getAddress()).thenReturn(
        new InetSocketAddress("localhost", 0));

    JobConf jobConf = new JobConf();
    jobConf.setClass("fs.file.impl", StubbedFS.class, FileSystem.class);
    jobConf.setBoolean("fs.file.impl.disable.cache", true);
    jobConf.set(JobConf.MAPRED_MAP_TASK_ENV, "");
    jobConf.set(MRJobConfig.APPLICATION_ATTEMPT_ID, "10");

    TaskSplitMetaInfo splits = mock(TaskSplitMetaInfo.class);
    when(splits.getLocations()).thenReturn(new String[] { "127.0.0.1" });

    NodeId nid = BuilderUtils.newNodeId("127.0.0.1", 0);
    ContainerId contId = BuilderUtils.newContainerId(appAttemptId, 3);
    Container container = mock(Container.class);
    when(container.getId()).thenReturn(contId);
    when(container.getNodeId()).thenReturn(nid);
    when(container.getNodeHttpAddress()).thenReturn("localhost:0");

    AppContext appCtx = mock(AppContext.class);
    AMContainerMap containers = new AMContainerMap(
        mock(ContainerHeartbeatHandler.class), mock(TaskAttemptListener.class),
        appCtx);
    containers.addContainerIfNew(container);

    doReturn(new ClusterInfo()).when(appCtx).getClusterInfo();
    doReturn(containers).when(appCtx).getAllContainers();

    TaskAttemptImpl taImpl = new MapTaskAttemptImpl2(taskId, 1, eventHandler,
        jobFile, 1, splits, jobConf, taListener, mock(OutputCommitter.class),
        mock(Token.class), new Credentials(), new SystemClock(),
        mock(TaskHeartbeatHandler.class), appCtx, MAP_ONLY_TASK_CLASS_NAME);

    taImpl.handle(new TaskAttemptEventSchedule(attemptId, false));
    // At state STARTING.
    taImpl.handle(new TaskAttemptEventStartedRemotely(attemptId, contId, null, -1));
    taImpl
        .handle(new TaskAttemptEvent(attemptId, TaskAttemptEventType.TA_DONE));
    assertEquals("Task attempt is not in succeeded state", taImpl.getState(),
        TaskAttemptState.SUCCEEDED);
    taImpl.handle(new TaskAttemptEvent(attemptId,
        TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES));
    assertEquals("Task attempt is not in FAILED state", taImpl.getState(),
        TaskAttemptState.FAILED);
    taImpl.handle(new TaskAttemptEvent(attemptId,
        TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURES));
    assertEquals("Task attempt is not in FAILED state, still",
        taImpl.getState(), TaskAttemptState.FAILED);
    assertFalse("InternalError occurred trying to handle TA_TOO_MANY_FETCH_FAILURES",
        eventHandler.internalError);
  }

  public static class MockEventHandler implements EventHandler {
    public boolean internalError;
    
    @Override
    public void handle(Event event) {
      if (event instanceof JobEvent) {
        JobEvent je = ((JobEvent) event);
        if (JobEventType.INTERNAL_ERROR == je.getType()) {
          internalError = true;
        }
      }
    }
    
  };
}
