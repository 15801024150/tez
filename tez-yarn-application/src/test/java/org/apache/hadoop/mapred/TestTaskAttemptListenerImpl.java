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
package org.apache.hadoop.mapred;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.jobhistory.ContainerHeartbeatHandler;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.mapreduce.v2.app2.AppContext;
import org.apache.hadoop.mapreduce.v2.app2.TaskHeartbeatHandler;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventStartedRemotely;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerImpl;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerMap;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.event.Event;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.apache.tez.mapreduce.hadoop.ContainerContext;
import org.apache.tez.mapreduce.hadoop.ContainerTask;
import org.apache.tez.mapreduce.hadoop.MRTaskType;
import org.apache.tez.mapreduce.hadoop.TaskAttemptListenerImplTez;
import org.apache.tez.mapreduce.task.impl.MRTaskContext;
import org.apache.tez.records.TezTaskAttemptID;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class TestTaskAttemptListenerImpl {
  public static class MockTaskAttemptListenerImpl extends TaskAttemptListenerImplTez {

    public MockTaskAttemptListenerImpl(AppContext context,
        JobTokenSecretManager jobTokenSecretManager,
        ContainerHeartbeatHandler chh, TaskHeartbeatHandler thh) {
      super(context, thh, chh, jobTokenSecretManager);
    }

    @Override
    protected void startRpcServer() {
      //Empty
    }
    
    @Override
    protected void stopRpcServer() {
      //Empty
    }
  }
  
  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void testGetTask() throws IOException {
    AppContext appCtx = mock(AppContext.class);
    EventHandler mockHandler = mock(EventHandler.class);
    AMContainerMap amContainers = mock(AMContainerMap.class);
    
    // Request to get a task for Container1 returns null.
    ContainerId containerId1 = BuilderUtils.newContainerId(1, 1, 1, 1);
    AMContainerImpl amContainer1 = mock(AMContainerImpl.class);
    when(amContainer1.pullTaskContext()).thenReturn(null);
    when(amContainers.get(containerId1)).thenReturn(amContainer1);
    
    MRTaskContext task = mock(MRTaskContext.class);
    TezTaskAttemptID taId = new TezTaskAttemptID("1", 1, MRTaskType.MAP.toString(), 1, 1);
    when(task.getTaskAttemptId()).thenReturn(taId);
    
    
    // Request to get a task for Container2 returns task.
    ContainerId containerId2 = BuilderUtils.newContainerId(1, 1,1,2);
    AMContainerImpl amContainer2 = mock(AMContainerImpl.class);
    when(amContainer2.pullTaskContext()).thenReturn(task);
    when(amContainers.get(containerId2)).thenReturn(amContainer2);
    
    when(appCtx.getAllContainers()).thenReturn(amContainers);
    when(appCtx.getEventHandler()).thenReturn(mockHandler);
    
    JobTokenSecretManager secret = mock(JobTokenSecretManager.class); 
    TaskHeartbeatHandler thh = mock(TaskHeartbeatHandler.class);
    ContainerHeartbeatHandler chh = mock(ContainerHeartbeatHandler.class);
    
    MockTaskAttemptListenerImpl listener = 
      new MockTaskAttemptListenerImpl(appCtx, secret, chh, thh);
    Configuration conf = new Configuration();
    listener.init(conf);
    listener.start();

    ContainerContext context1 = new ContainerContext(containerId1, "");
    
    ContainerContext context2 = new ContainerContext(containerId2, "");
    ContainerTask result = null;
    
    // Verify ask before registration.
    //The JVM ID has not been registered yet so we should kill it.
    result = listener.getTask(context1);
    assertNotNull(result);
    assertTrue(result.shouldDie());
    verify(chh, never()).pinged(any(ContainerId.class));

    // Verify ask after JVM registration, but before container is assigned a task.
    listener.registerRunningContainer(containerId1);
    result = listener.getTask(context1);
    assertNull(result);
    verify(chh, times(1)).pinged(any(ContainerId.class));
    
    // Verify ask after JVM registration, and when the container has a task.
    listener.registerRunningContainer(containerId2);
    result = listener.getTask(context2);
    assertNotNull(result);
    assertFalse(result.shouldDie());
    assertTrue(result.getMrTaskContext() == task);
    verify(chh, times(2)).pinged(any(ContainerId.class));
    ArgumentCaptor<Event> ac = ArgumentCaptor.forClass(Event.class);
    verify(mockHandler).handle(ac.capture());
    Event cEvent = ac.getValue();
    assertTrue(cEvent.getClass().equals(TaskAttemptEventStartedRemotely.class));
    TaskAttemptEventStartedRemotely event = (TaskAttemptEventStartedRemotely)cEvent; 
    assertTrue(event.getType() == TaskAttemptEventType.TA_STARTED_REMOTELY);
    assertTrue(event.getContainerId().equals(containerId2));
    
    // Verify ask after JVM is unregistered.
    listener.unregisterRunningContainer(containerId1);
    result = listener.getTask(context1);
    assertNotNull(result);
    assertTrue(result.shouldDie());

    listener.stop();
  }
}
