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

package org.apache.tez.dag.app.rm;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.RackResolver;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.DAGAppMasterState;
import org.apache.tez.dag.app.rm.TaskScheduler.TaskSchedulerAppCallback.AppFinalStatus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/* TODO not yet updating cluster nodes on every allocate response
 * from RMContainerRequestor
   import org.apache.tez.dag.app.rm.node.AMNodeEventNodeCountUpdated;
    if (clusterNmCount != lastClusterNmCount) {
      LOG.info("Num cluster nodes changed from " + lastClusterNmCount + " to "
          + clusterNmCount);
      eventHandler.handle(new AMNodeEventNodeCountUpdated(clusterNmCount));
    }
 */
public class TaskScheduler extends AbstractService
                             implements AMRMClientAsync.CallbackHandler {
  private static final Log LOG = LogFactory.getLog(TaskScheduler.class);

  public interface TaskSchedulerAppCallback {
    public class AppFinalStatus {
      public final FinalApplicationStatus exitStatus;
      public final String exitMessage;
      public final String postCompletionTrackingUrl;
      public AppFinalStatus(FinalApplicationStatus exitStatus,
                             String exitMessage,
                             String posCompletionTrackingUrl) {
        this.exitStatus = exitStatus;
        this.exitMessage = exitMessage;
        this.postCompletionTrackingUrl = posCompletionTrackingUrl;
      }
    }
    // upcall to app must be outside locks
    public void taskAllocated(Object task,
                               Object appCookie,
                               Container container);
    // this may end up being called for a task+container pair that the app
    // has not heard about. this can happen because of a race between
    // taskAllocated() upcall and deallocateTask() downcall
    public void containerCompleted(Object taskLastAllocated,
                                    ContainerStatus containerStatus);
    public void containerBeingReleased(ContainerId containerId);
    public void nodesUpdated(List<NodeReport> updatedNodes);
    public void appShutdownRequested();
    public void setApplicationRegistrationData(
                                Resource maxContainerCapability,
                                Map<ApplicationAccessType, String> appAcls
                                );
    public void onError(Throwable t);
    public float getProgress();
    public AppFinalStatus getFinalAppStatus();
  }

  public interface ContainerSignatureMatcher {
    /**
     * Checks the compatibility between the specified container signatures.
     *
     * @return true if the first signature is a super set of the second
     *         signature.
     */
    public boolean isCompatible(Object cs1, Object cs2);
  }

  final TezAMRMClientAsync<CookieContainerRequest> amRmClient;
  final TaskSchedulerAppCallback realAppClient;
  final TaskSchedulerAppCallback appClientDelegate;
  final ContainerSignatureMatcher containerSignatureMatcher;
  ExecutorService appCallbackExecutor;

  // Container Re-Use configuration
  private boolean shouldReuseContainers;
  private boolean reuseRackLocal;
  private boolean reuseNonLocal;

  Map<Object, CookieContainerRequest> taskRequests =
                  new HashMap<Object, CookieContainerRequest>();
  // LinkedHashMap is need in getProgress()
  LinkedHashMap<Object, Container> taskAllocations =
                  new LinkedHashMap<Object, Container>();
  /**
   * Tracks last task assigned to a known container.
   */
  Map<ContainerId, Object> containerAssignments =
                  new HashMap<ContainerId, Object>();
  HashMap<ContainerId, Object> releasedContainers =
                  new HashMap<ContainerId, Object>();
  /**
   * Map of containers currently being held by the TaskScheduler.
   */
  Map<ContainerId, HeldContainer> heldContainers =
      new HashMap<ContainerId, HeldContainer>();
  
  Resource totalResources = Resource.newInstance(0, 0);
  Resource allocatedResources = Resource.newInstance(0, 0);
  
  final String appHostName;
  final int appHostPort;
  final String appTrackingUrl;
  final AppContext appContext;

  boolean isStopped = false;

  private ContainerAssigner NODE_LOCAL_ASSIGNER = new NodeLocalContainerAssigner();
  private ContainerAssigner RACK_LOCAL_ASSIGNER = new RackLocalContainerAssigner();
  private ContainerAssigner NON_LOCAL_ASSIGNER = new NonLocalContainerAssigner();

  DelayedContainerManager delayedContainerManager;
  long localitySchedulingDelay;
  long sessionDelay;
  
  class CRCookie {
    // Do not use these variables directly. Can caused mocked unit tests to fail.
    private Object task;
    private Object appCookie;
    private Object containerSignature;
    
    CRCookie(Object task, Object appCookie, Object containerSignature) {
      this.task = task;
      this.appCookie = appCookie;
      this.containerSignature = containerSignature;
    }

    Object getTask() {
      return task;
    }

    Object getAppCookie() {
      return appCookie;
    }
    
    Object getContainerSignature() {
      return containerSignature;
    }
  }

  class CookieContainerRequest extends ContainerRequest {
    CRCookie cookie;

    public CookieContainerRequest(
        Resource capability,
        String[] hosts,
        String[] racks,
        Priority priority,
        CRCookie cookie) {
      super(capability, hosts, racks, priority);
      this.cookie = cookie;
    }

    CRCookie getCookie() {
      return cookie;
    }
  }

  public TaskScheduler(TaskSchedulerAppCallback appClient,
                        ContainerSignatureMatcher containerSignatureMatcher,
                        String appHostName,
                        int appHostPort,
                        String appTrackingUrl,
                        AppContext appContext) {
    super(TaskScheduler.class.getName());
    this.realAppClient = appClient;
    this.appCallbackExecutor = createAppCallbackExecutorService();
    this.containerSignatureMatcher = containerSignatureMatcher;
    this.appClientDelegate = createAppCallbackDelegate(appClient);
    this.amRmClient = TezAMRMClientAsync.createAMRMClientAsync(1000, this);
    this.appHostName = appHostName;
    this.appHostPort = appHostPort;
    this.appTrackingUrl = appTrackingUrl;
    this.appContext = appContext;
  }

  @Private
  @VisibleForTesting
  TaskScheduler(TaskSchedulerAppCallback appClient,
      ContainerSignatureMatcher containerSignatureMatcher,
      String appHostName,
      int appHostPort,
      String appTrackingUrl,
      TezAMRMClientAsync<CookieContainerRequest> client,
      AppContext appContext) {
    super(TaskScheduler.class.getName());
    this.realAppClient = appClient;
    this.appCallbackExecutor = createAppCallbackExecutorService();
    this.containerSignatureMatcher = containerSignatureMatcher;
    this.appClientDelegate = createAppCallbackDelegate(appClient);
    this.amRmClient = client;
    this.appHostName = appHostName;
    this.appHostPort = appHostPort;
    this.appTrackingUrl = appTrackingUrl;
    this.appContext = appContext;
  }

  private ExecutorService createAppCallbackExecutorService() {
    return Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
        .setNameFormat("TaskSchedulerAppCaller #%d").setDaemon(true).build());
  }
  
  public Resource getAvailableResources() {
    return amRmClient.getAvailableResources();
  }

  public int getClusterNodeCount() {
    return amRmClient.getClusterNodeCount();
  }

  TaskSchedulerAppCallback createAppCallbackDelegate(
      TaskSchedulerAppCallback realAppClient) {
    return new TaskSchedulerAppCallbackWrapper(realAppClient,
        appCallbackExecutor);
  }
  
  // AbstractService methods
  @Override
  public synchronized void serviceInit(Configuration conf) {

    amRmClient.init(conf);
    int heartbeatIntervalMax = conf.getInt(
        TezConfiguration.TEZ_AM_RM_HEARTBEAT_INTERVAL_MS_MAX,
        TezConfiguration.TEZ_AM_RM_HEARTBEAT_INTERVAL_MS_MAX_DEFAULT);
    amRmClient.setHeartbeatInterval(heartbeatIntervalMax);

    shouldReuseContainers = conf.getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED_DEFAULT);
    reuseRackLocal = conf.getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_RACK_FALLBACK_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_RACK_FALLBACK_ENABLED_DEFAULT);
    reuseNonLocal = conf
      .getBoolean(
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_NON_LOCAL_FALLBACK_ENABLED,
        TezConfiguration.TEZ_AM_CONTAINER_REUSE_NON_LOCAL_FALLBACK_ENABLED_DEFAULT);
    Preconditions.checkArgument(
      ((!reuseRackLocal && !reuseNonLocal) || (reuseRackLocal)),
      "Re-use Rack-Local cannot be disabled if Re-use Non-Local has been"
      + " enabled");

    localitySchedulingDelay = conf.getLong(
      TezConfiguration.TEZ_AM_CONTAINER_REUSE_LOCALITY_DELAY_ALLOCATION_MILLIS,
      TezConfiguration.TEZ_AM_CONTAINER_REUSE_LOCALITY_DELAY_ALLOCATION_MILLIS_DEFAULT);
    Preconditions.checkArgument(localitySchedulingDelay >= 0,
        "Locality Scheduling delay should be >=0");

    sessionDelay = conf.getLong(
        TezConfiguration.TEZ_AM_CONTAINER_SESSION_DELAY_ALLOCATION_MILLIS,
        TezConfiguration.TEZ_AM_CONTAINER_SESSION_DELAY_ALLOCATION_MILLIS_DEFAULT);
    Preconditions.checkArgument(sessionDelay >= 0 || sessionDelay == -1,
      "Session delay should be either -1 or >=0");

    delayedContainerManager = new DelayedContainerManager();
    LOG.info("TaskScheduler initialized with configuration: " +
            "maxRMHeartbeatInterval: " + heartbeatIntervalMax +
            ", containerReuseEnabled: " + shouldReuseContainers +
            ", reuseRackLocal: " + reuseRackLocal +
            ", reuseNonLocal: " + reuseNonLocal + 
            ", localitySchedulingDelay: " + localitySchedulingDelay +
            ", sessionDelay=" + sessionDelay);
  }

  @Override
  public void serviceStart() {
    try {
      RegisterApplicationMasterResponse response;
      synchronized (this) {
        amRmClient.start();
        response = amRmClient.registerApplicationMaster(appHostName,
                                                        appHostPort,
                                                        appTrackingUrl);
      }
      // upcall to app outside locks
      appClientDelegate.setApplicationRegistrationData(
          response.getMaximumResourceCapability(),
          response.getApplicationACLs());

      delayedContainerManager.start();
    } catch (YarnException e) {
      LOG.error("Yarn Exception while registering", e);
      throw new TezUncheckedException(e);
    } catch (IOException e) {
      LOG.error("IO Exception while registering", e);
      throw new TezUncheckedException(e);
    }
  }

  @Override
  public void serviceStop() throws InterruptedException {
    // upcall to app outside of locks
    AppFinalStatus status = appClientDelegate.getFinalAppStatus();
    try {
      delayedContainerManager.shutdown();
      // Wait for contianers to be released.
      delayedContainerManager.join(2000l);
      // TODO TEZ-36 dont unregister automatically after reboot sent by RM
      synchronized (this) {
        isStopped = true;
        amRmClient.unregisterApplicationMaster(status.exitStatus,
                                               status.exitMessage,
                                               status.postCompletionTrackingUrl);
      }

      // call client.stop() without lock client will attempt to stop the callback
      // operation and at the same time the callback operation might be trying
      // to get our lock.
      amRmClient.stop();
      appCallbackExecutor.shutdown();
      appCallbackExecutor.awaitTermination(1000l, TimeUnit.MILLISECONDS);
    } catch (YarnException e) {
      LOG.error("Yarn Exception while unregistering ", e);
      throw new TezUncheckedException(e);
    } catch (IOException e) {
      LOG.error("IOException while unregistering ", e);
      throw new TezUncheckedException(e);
    }
  }

  // AMRMClientAsync interface methods
  @Override
  public void onContainersCompleted(List<ContainerStatus> statuses) {
    if(isStopped) {
      return;
    }
    Map<Object, ContainerStatus> appContainerStatus =
                        new HashMap<Object, ContainerStatus>(statuses.size());
    synchronized (this) {
      for(ContainerStatus containerStatus : statuses) {
        ContainerId completedId = containerStatus.getContainerId();
        HeldContainer delayedContainer = heldContainers.get(completedId);

        Object task = releasedContainers.remove(completedId);
        if(task != null){
          if (delayedContainer != null) {
            LOG.warn("Held container sohuld be null since releasedContainer is not");
          }
          // TODO later we may want to check if exit code matched expectation
          // e.g. successful container should not come back fail exit code after
          // being released
          // completion of a container we had released earlier
          // an allocated container completed. notify app
          LOG.info("Released container completed:" + completedId +
                   " last allocated to task: " + task);
          appContainerStatus.put(task, containerStatus);
          continue;
        }

        // not found in released containers. check currently allocated containers
        // no need to release this container as the RM has already completed it
        task = unAssignContainer(completedId, false);
        if (delayedContainer != null) {
          heldContainers.remove(completedId);
          Resources.subtract(allocatedResources, delayedContainer.getContainer().getResource());
        } else {
          LOG.warn("Held container expected to be not null for a non-AM-released container");
        }
        if(task != null) {
          // completion of a container we have allocated currently
          // an allocated container completed. notify app
          LOG.info("Allocated container completed:" + completedId +
                   " last allocated to task: " + task);
          appContainerStatus.put(task, containerStatus);
          continue;
        }

        // container neither allocated nor released
        LOG.info("Ignoring unknown container: " + containerStatus.getContainerId());
      }
    }

    // upcall to app must be outside locks
    for (Entry<Object, ContainerStatus> entry : appContainerStatus.entrySet()) {
      appClientDelegate.containerCompleted(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void onContainersAllocated(List<Container> containers) {
    if (isStopped) {
      return;
    }
    Map<CookieContainerRequest, Container> assignedContainers;

    synchronized (this) {
      List<Container> modifiableContainerList = Lists.newLinkedList(containers);
      assignedContainers = assignNewlyAllocatedContainers(
          modifiableContainerList);
    }

    // upcall to app must be outside locks
    informAppAboutAssignments(assignedContainers);
  }

  /**
   * Tries assigning the list of specified containers. Optionally, release
   * containers or add them to the delayed container queue.
   *
   * The flags apply to all containers in the specified lists. So, separate
   * calls should be made based on the expected behaviour.
   *
   * @param containers
   *          The list of containers to be assigned. The list *may* be modified
   *          in place based on allocations and releases.
   * @return Assignments.
   */
  private synchronized Map<CookieContainerRequest, Container>
      assignNewlyAllocatedContainers(Iterable<Container> containers) {

    Map<CookieContainerRequest, Container> assignedContainers =
        new HashMap<CookieContainerRequest, Container>();
    assignNewContainersWithLocation(containers,
      NODE_LOCAL_ASSIGNER, assignedContainers);
    assignNewContainersWithLocation(containers,
      RACK_LOCAL_ASSIGNER, assignedContainers);
    assignNewContainersWithLocation(containers,
      NON_LOCAL_ASSIGNER, assignedContainers);

    // Release any unassigned containers given by the RM
    releaseUnassignedContainers(containers);

    return assignedContainers;
  }

  private synchronized Map<CookieContainerRequest, Container>
      tryAssignReUsedContainers(Iterable<Container> containers) {

    Map<CookieContainerRequest, Container> assignedContainers =
      new HashMap<CookieContainerRequest, Container>();

    // Honor locality and match as many as possible
    assignReUsedContainersWithLocation(containers,
      NODE_LOCAL_ASSIGNER, assignedContainers, true);
    assignReUsedContainersWithLocation(containers,
      RACK_LOCAL_ASSIGNER, assignedContainers, true);
    assignReUsedContainersWithLocation(containers,
      NON_LOCAL_ASSIGNER, assignedContainers, true);

    return assignedContainers;
  }

  /**
   * Try to assign a re-used container
   * @param heldContainer Container to be used to assign to tasks
   * @return Assigned container map
   */

  private synchronized Map<CookieContainerRequest, Container>
      assignDelayedContainer(HeldContainer heldContainer) {

    DAGAppMasterState state = appContext.getAMState();

    if (LOG.isDebugEnabled()) {
      LOG.info("Trying to assign a delayed container"
        + ", containerId=" + heldContainer.getContainer().getId()
        + ", nextScheduleTime=" + heldContainer.getNextScheduleTime()
        + ", containerExpiryTime=" + heldContainer.getContainerExpiryTime()
        + ", AMState=" + state
        + ", matchLevel=" + heldContainer.getLocalityMatchLevel()
        + ", taskRequestsCount=" + taskRequests.size());
    }

    if (state.equals(DAGAppMasterState.IDLE) || taskRequests.isEmpty()) {
      // reset locality level on held container
      // if sessionDelay defined, push back into delayed queue if not already
      // done so

      heldContainer.resetLocalityMatchLevel();
      long currentTime = System.currentTimeMillis();
      if (heldContainer.getContainerExpiryTime() <= currentTime
          && sessionDelay != -1) {
        LOG.info("Container's session delay expired. Releasing container"
          + ", containerId=" + heldContainer.container.getId()
          + ", containerExpiryTime="
          + heldContainer.getContainerExpiryTime()
          + ", sessionDelay=" + sessionDelay);
        releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
      } else {
        if (!appContext.isSession()) {
          releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
        } else {
          // only put back in queue if this is a session
          heldContainer.resetLocalityMatchLevel();
          delayedContainerManager.addDelayedContainer(
            heldContainer.getContainer(),
            currentTime + localitySchedulingDelay);
        }
      }
    } else if (state.equals(DAGAppMasterState.RUNNING)) {
      HeldContainer.LocalityMatchLevel localityMatchLevel =
        heldContainer.getLocalityMatchLevel();
      Map<CookieContainerRequest, Container> assignedContainers =
        new HashMap<CookieContainerRequest, Container>();

      Container containerToAssign = heldContainer.container;

      // Each time a container is seen, we try node, rack and non-local in that
      // order depending on matching level allowed

      // if match level is NEW or NODE, match only at node-local
      // always try node local matches for other levels
      if (localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NEW)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NODE)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.RACK)
          || localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.NON_LOCAL)) {
        assignReUsedContainerWithLocation(containerToAssign,
            NODE_LOCAL_ASSIGNER, assignedContainers, true);
        if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
          LOG.info("Failed to assign tasks to delayed container using node"
            + ", containerId=" + heldContainer.getContainer().getId());
        }
      }

      // if re-use allowed at rack
      // match against rack if match level is RACK or NON-LOCAL
      // if scheduling delay is 0, match at RACK allowed without a sleep
      if (assignedContainers.isEmpty()) {
        if (reuseRackLocal && (localitySchedulingDelay == 0 ||
          (localityMatchLevel.equals(HeldContainer.LocalityMatchLevel.RACK)
            || localityMatchLevel.equals(
              HeldContainer.LocalityMatchLevel.NON_LOCAL)))) {
          assignReUsedContainerWithLocation(containerToAssign,
              RACK_LOCAL_ASSIGNER, assignedContainers, false);
          if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
            LOG.info("Failed to assign tasks to delayed container using rack"
              + ", containerId=" + heldContainer.getContainer().getId());
          }
        }
      }

      // if re-use allowed at non-local
      // match against rack if match level is NON-LOCAL
      // if scheduling delay is 0, match at NON-LOCAL allowed without a sleep
      if (assignedContainers.isEmpty()) {
        if (reuseNonLocal && (localitySchedulingDelay == 0
            || localityMatchLevel.equals(
                HeldContainer.LocalityMatchLevel.NON_LOCAL))) {
         assignReUsedContainerWithLocation(containerToAssign,
              NON_LOCAL_ASSIGNER, assignedContainers, false);
          if (LOG.isDebugEnabled() && assignedContainers.isEmpty()) {
            LOG.info("Failed to assign tasks to delayed container using non-local"
                + ", containerId=" + heldContainer.getContainer().getId());
          }
        }
      }

      if (assignedContainers.isEmpty()) {

        long currentTime = System.currentTimeMillis();

        // Release container if final expiry time is reached
        if (heldContainer.getContainerExpiryTime() <= currentTime
          && sessionDelay != -1) {
          LOG.info("Container's session delay expired. Releasing container"
            + ", containerId=" + heldContainer.container.getId()
            + ", containerExpiryTime="
            + heldContainer.getContainerExpiryTime()
            + ", sessionDelay=" + sessionDelay);
          releaseUnassignedContainers(
            Lists.newArrayList(heldContainer.container));
        } else {

          // Let's decide if this container has hit the end of the road

          // EOL true if container's match level is NON-LOCAL
          boolean hitFinalMatchLevel = localityMatchLevel.equals(
            HeldContainer.LocalityMatchLevel.NON_LOCAL);
          if (!hitFinalMatchLevel) {
            // EOL also true if locality delay is 0
            // or rack-local or non-local is disabled
            heldContainer.incrementLocalityMatchLevel();
            if (localitySchedulingDelay == 0 ||
                (!reuseRackLocal
                  || (!reuseNonLocal &&
                    heldContainer.getLocalityMatchLevel().equals(
                        HeldContainer.LocalityMatchLevel.NON_LOCAL)))) {
              hitFinalMatchLevel = true;
            }
          }

          if (hitFinalMatchLevel) {
            // Are there any pending requests at any priority?
            // release if there are tasks or this is not a session
            if (!taskRequests.isEmpty() || !appContext.isSession()) {
              LOG.info("Releasing held container as either there are pending but "
                + " unmatched requests or this is not a session"
                + ", containerId=" + heldContainer.container.getId()
                + ", pendingTasks=" + !taskRequests.isEmpty()
                + ", isSession=" + appContext.isSession());
              releaseUnassignedContainers(
                Lists.newArrayList(heldContainer.container));
            } else {
              // if no tasks, treat this like an idle session
              heldContainer.resetLocalityMatchLevel();
              delayedContainerManager.addDelayedContainer(
                heldContainer.getContainer(),
                currentTime + localitySchedulingDelay);
            }
          } else {
            // Schedule delay container to match at a later try
            delayedContainerManager.addDelayedContainer(
                heldContainer.getContainer(),
                currentTime + localitySchedulingDelay);
          }
        }
      } else if (LOG.isDebugEnabled()) {
        LOG.debug("Delayed container assignment successful"
            + ", containerId=" + heldContainer.getContainer().getId());
      }

      return assignedContainers;
    } else {
      // ignore all other cases?
      LOG.warn("Received a request to assign re-used containers when AM was "
        + " in state: " + state + ". Ignoring request and releasing container"
        + ": " + heldContainer.getContainer().getId());
      releaseUnassignedContainers(Lists.newArrayList(heldContainer.container));
    }

    return null;
  }

  public synchronized void resetMatchLocalityForAllHeldContainers() {
    for (HeldContainer heldContainer : heldContainers.values()) {
      heldContainer.resetLocalityMatchLevel();
    }
    synchronized(delayedContainerManager) {
      delayedContainerManager.notify();
    }
  }

  @Override
  public void onShutdownRequest() {
    if(isStopped) {
      return;
    }
    // upcall to app must be outside locks
    appClientDelegate.appShutdownRequested();
  }

  @Override
  public void onNodesUpdated(List<NodeReport> updatedNodes) {
    if(isStopped) {
      return;
    }
    // ignore bad nodes for now
    // upcall to app must be outside locks
    appClientDelegate.nodesUpdated(updatedNodes);
  }

  @Override
  public float getProgress() {
    if(isStopped) {
      return 1;
    }

    if(totalResources.getMemory() == 0) {
      // assume this is the first allocate callback. nothing is allocated.
      // available resource = totalResource
      // TODO this will not handle dynamic changes in resources
      totalResources = Resources.clone(getAvailableResources());
      LOG.info("App total resource memory: " + totalResources.getMemory() +
               " cpu: " + totalResources.getVirtualCores() +
               " taskAllocations: " + taskAllocations.size());
    }

    preemptIfNeeded();

    return appClientDelegate.getProgress();
  }

  @Override
  public void onError(Throwable t) {
    if(isStopped) {
      return;
    }
    appClientDelegate.onError(t);
  }

  public Resource getTotalResources() {
    return totalResources;
  }

  public synchronized void allocateTask(
      Object task,
      Resource capability,
      String[] hosts,
      String[] racks,
      Priority priority,
      Object containerSignature,
      Object clientCookie) {

    // XXX Have ContainerContext implement an interface defined by TaskScheduler.
    // TODO check for nulls etc
    // TODO extra memory allocation
    CRCookie cookie = new CRCookie(task, clientCookie, containerSignature);
    CookieContainerRequest request = new CookieContainerRequest(
      capability, hosts, racks, priority, cookie);

    addTaskRequest(task, request);
    // See if any of the delayedContainers can be used for this task.
    delayedContainerManager.tryAssigningAll();
    LOG.info("Allocation request for task: " + task +
      " with request: " + request);
  }

  /**
   * @param task
   *          the task to de-allocate.
   * @param taskSucceeded
   *          specify whether the task succeeded or failed.
   * @return true if a container is assigned to this task.
   */
  public boolean deallocateTask(Object task, boolean taskSucceeded) {
    Map<CookieContainerRequest, Container> assignedContainers = null;

    synchronized (this) {
      CookieContainerRequest request = removeTaskRequest(task);
      if (request != null) {
        // task not allocated yet
        LOG.info("Deallocating task: " + task + " before allocation");
        return false;
      }

      // task request not present. Look in allocations
      Container container = doBookKeepingForTaskDeallocate(task);
      if (container == null) {
        // task neither requested nor allocated.
        LOG.info("Ignoring removal of unknown task: " + task);
        return false;
      } else {
        LOG.info("Deallocated task: " + task + " from container: "
            + container.getId());

        if (!taskSucceeded || !shouldReuseContainers) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Releasing container, containerId=" + container.getId()
                + ", taskSucceeded=" + taskSucceeded
                + ", reuseContainersFlag=" + shouldReuseContainers);
          }
          releaseContainer(container.getId());
        } else {
          // Don't attempt to delay containers if delay is 0.
          HeldContainer heldContainer = heldContainers.get(container.getId());
          heldContainer.resetLocalityMatchLevel();
          long currentTime = System.currentTimeMillis();
          if (sessionDelay > 0) {
            heldContainer.setContainerExpiryTime(currentTime + sessionDelay);
          }
          assignedContainers = assignDelayedContainer(heldContainer);
        }
      }
    }

    // up call outside of the lock.
    if (assignedContainers != null && assignedContainers.size() == 1) {
      informAppAboutAssignments(assignedContainers);
    }
    return true;
  }

  public synchronized Object deallocateContainer(ContainerId containerId) {
    Object task = unAssignContainer(containerId, true);
    if(task != null) {
      LOG.info("Deallocated container: " + containerId +
        " from task: " + task);
      return task;
    }

    LOG.info("Ignoring dealloction of unknown container: " + containerId);
    return null;
  }

  synchronized void preemptIfNeeded() {
    Resource freeResources = Resources.subtract(totalResources,
      allocatedResources);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Allocated resource memory: " + allocatedResources.getMemory() +
        " cpu:" + allocatedResources.getVirtualCores());
    }
    assert freeResources.getMemory() >= 0;

    CookieContainerRequest highestPriRequest = null;
    for(CookieContainerRequest request : taskRequests.values()) {
      if(highestPriRequest == null) {
        highestPriRequest = request;
      } else if(isHigherPriority(request.getPriority(),
                                   highestPriRequest.getPriority())){
        highestPriRequest = request;
      }
    }
    if(highestPriRequest != null &&
       !fitsIn(highestPriRequest.getCapability(), freeResources)) {
      // highest priority request will not fit in existing free resources
      // free up some more
      // TODO this is subject to error wrt RM resource normalization
      Map.Entry<Object, Container> preemptedEntry = null;
      for(Map.Entry<Object, Container> entry : taskAllocations.entrySet()) {
        if(!isHigherPriority(highestPriRequest.getPriority(),
                             entry.getValue().getPriority())) {
          // higher or same priority
          continue;
        }
        if(preemptedEntry == null ||
           !isHigherPriority(entry.getValue().getPriority(),
                             preemptedEntry.getValue().getPriority())) {
          // keep the lower priority or the one added later
          preemptedEntry = entry;
        }
      }
      if(preemptedEntry != null) {
        // found something to preempt
        LOG.info("Preempting task: " + preemptedEntry.getKey() +
            " to free resource for request: " + highestPriRequest +
            " . Current free resources: " + freeResources);
        deallocateContainer(preemptedEntry.getValue().getId());
        // app client will be notified when after container is killed
        // and we get its completed container status
      }
    }
  }

  private boolean fitsIn(Resource toFit, Resource resource) {
    // YARN-893 prevents using correct library code
    //return Resources.fitsIn(toFit, resource);
    return resource.getMemory() >= toFit.getMemory();
  }

  private CookieContainerRequest getMatchingRequestWithPriority(
      Container container,
      String location) {
    Priority priority = container.getPriority();
    Resource capability = container.getResource();
    List<? extends Collection<CookieContainerRequest>> requestsList =
        amRmClient.getMatchingRequests(priority, location, capability);

    if (!requestsList.isEmpty()) {
      // pick first one
      for (Collection<CookieContainerRequest> requests : requestsList) {
        for (CookieContainerRequest cookieContainerRequest : requests) {
          if (canAssignTaskToContainer(cookieContainerRequest, container)) {
            return cookieContainerRequest;
          }
        }
      }
    }

    return null;
  }

  private CookieContainerRequest getMatchingRequestWithoutPriority(
      Container container,
      String location) {
    Resource capability = container.getResource();
    List<? extends Collection<CookieContainerRequest>> pRequestsList =
      amRmClient.getMatchingRequestsForTopPriority(location, capability);
    if (pRequestsList == null || pRequestsList.isEmpty()) {
      return null;
    }
    for (Collection<CookieContainerRequest> requests : pRequestsList) {
      for (CookieContainerRequest cookieContainerRequest : requests) {
        if (canAssignTaskToContainer(cookieContainerRequest, container)) {
          return cookieContainerRequest;
        }
      }
    }
    return null;
  }

  private boolean canAssignTaskToContainer(
      CookieContainerRequest cookieContainerRequest, Container container) {
    HeldContainer heldContainer = heldContainers.get(container.getId());
    if (heldContainer == null) { // New container.
      return true;
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Trying to match task to a held container, "
            + " containerId=" + heldContainer.container.getId());
      }
      if (containerSignatureMatcher.isCompatible(heldContainer
          .getContainerSignature(), cookieContainerRequest.getCookie()
          .getContainerSignature())) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Matched delayed container to task"
            + " containerId=" + heldContainer.container.getId());
        }
        return true;
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Failed to match delayed container to task"
        + " containerId=" + heldContainer.container.getId());
    }
    return false;
  }

  private Object getTask(CookieContainerRequest request) {
    return request.getCookie().getTask();
  }

  private void releaseContainer(ContainerId containerId) {
    Object assignedTask = containerAssignments.remove(containerId);
    if (assignedTask != null) {
      // A task was assigned to this container at some point. Inform the app.
      appClientDelegate.containerBeingReleased(containerId);
    }
    HeldContainer delayedContainer = heldContainers.remove(containerId);
    if (delayedContainer != null) {
      Resources.subtractFrom(allocatedResources, delayedContainer.getContainer().getResource());
    }
    amRmClient.releaseAssignedContainer(containerId);
    if (assignedTask != null) {
      // A task was assigned at some point. Add to release list since we are
      // releasing the container.
      releasedContainers.put(containerId, assignedTask);
    }
  }

  private void assignContainer(Object task,
      Container container,
      CookieContainerRequest assigned) {
    CookieContainerRequest request = removeTaskRequest(task);
    assert request != null;
    //assert assigned.equals(request);

    Container result = taskAllocations.put(task, container);
    assert result == null;
    containerAssignments.put(container.getId(), task);
    if (heldContainers.put(container.getId(), new HeldContainer(container,
        -1, -1, assigned.getCookie().getContainerSignature())) == null) {
      Resources.addTo(allocatedResources, container.getResource());
    }
  }

  private CookieContainerRequest removeTaskRequest(Object task) {
    CookieContainerRequest request = taskRequests.remove(task);
    if(request != null) {
      // remove all references of the request from AMRMClient
      amRmClient.removeContainerRequest(request);
    }
    return request;
  }

  private void addTaskRequest(Object task,
                                CookieContainerRequest request) {
    // TODO TEZ-37 fix duplicate handling
    taskRequests.put(task, request);
    amRmClient.addContainerRequest(request);
  }

  private Container doBookKeepingForTaskDeallocate(Object task) {
    Container container = taskAllocations.remove(task);
    if (container == null) {
      return null;
    }
    return container;
  }

  private Object unAssignContainer(ContainerId containerId,
                                    boolean releaseIfFound) {
    // Not removing. containerAssignments tracks the last task run on a
    // container.
    Object task = containerAssignments.get(containerId);
    if(task == null) {
      return null;
    }
    Container container = taskAllocations.remove(task);
    assert container != null;
    if(releaseIfFound) {
      releaseContainer(containerId);
    }
    return task;
  }

  private boolean isHigherPriority(Priority lhs, Priority rhs) {
    return lhs.getPriority() < rhs.getPriority();
  }

  private synchronized void assignNewContainersWithLocation(
      Iterable<Container> containers,
      ContainerAssigner assigner,
      Map<CookieContainerRequest, Container> assignedContainers) {

    Iterator<Container> containerIterator = containers.iterator();
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      CookieContainerRequest assigned =
        assigner.assignNewContainer(container);
      if (assigned != null) {
        assignedContainers.put(assigned, container);
        containerIterator.remove();
      }
    }
  }

  private synchronized void assignReUsedContainersWithLocation(
      Iterable<Container> containers,
      ContainerAssigner assigner,
      Map<CookieContainerRequest, Container> assignedContainers,
      boolean honorLocality) {

    Iterator<Container> containerIterator = containers.iterator();
    while (containerIterator.hasNext()) {
      Container container = containerIterator.next();
      CookieContainerRequest assigned =
        assigner.assignReUsedContainer(container, honorLocality);
      if (assigned != null) {
        assignedContainers.put(assigned, container);
        containerIterator.remove();
      }
    }
  }

  private synchronized void assignReUsedContainerWithLocation(
    Container container,
    ContainerAssigner assigner,
    Map<CookieContainerRequest, Container> assignedContainers,
    boolean honorLocality) {

    CookieContainerRequest assigned =
      assigner.assignReUsedContainer(container, honorLocality);
    if (assigned != null) {
      assignedContainers.put(assigned, container);
    }
  }

  private void releaseUnassignedContainers(Iterable<Container> containers) {
    for (Container container : containers) {
      releaseContainer(container.getId());
      LOG.info("Releasing container, No RM requests matching container: "
          + container);
    }
  }

  private void informAppAboutAssignment(CookieContainerRequest assigned,
      Container container) {
    appClientDelegate.taskAllocated(getTask(assigned),
        assigned.getCookie().getAppCookie(), container);
  }

  private void informAppAboutAssignments(
      Map<CookieContainerRequest, Container> assignedContainers) {
    if (assignedContainers == null || assignedContainers.isEmpty()) {
      return;
    }
    for (Entry<CookieContainerRequest, Container> entry : assignedContainers
        .entrySet()) {
      informAppAboutAssignment(entry.getKey(), entry.getValue());
    }
  }

  private abstract class ContainerAssigner {

    protected final String locality;

    protected ContainerAssigner(String locality) {
      this.locality = locality;
    }

    public abstract CookieContainerRequest assignNewContainer(
        Container container);

    public abstract CookieContainerRequest assignReUsedContainer(
      Container container, boolean honorLocality);

    public void doBookKeepingForAssignedContainer(
        CookieContainerRequest assigned, Container container,
        String matchedLocation, boolean honorLocalityFlags) {
      if (assigned == null) {
        return;
      }
      Object task = getTask(assigned);
      assert task != null;

      LOG.info("Assigning container to task"
        + ", container=" + container
        + ", task=" + task
        + ", containerHost=" + container.getNodeId().getHost()
        + ", localityMatchType=" + locality
        + ", matchedLocation=" + matchedLocation
        + ", honorLocalityFlags=" + honorLocalityFlags
        + ", reusedContainer="
        + containerAssignments.containsKey(container.getId())
        + ", containerResourceMemory=" + container.getResource().getMemory()
        + ", containerResourceVCores="
        + container.getResource().getVirtualCores());

      assignContainer(task, container, assigned);
    }
  }
  
  private class NodeLocalContainerAssigner extends ContainerAssigner {

    NodeLocalContainerAssigner() {
      super("NodeLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = container.getNodeId().getHost();
      CookieContainerRequest assigned = getMatchingRequestWithPriority(
          container, location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(Container container,
        boolean honorLocality) {
      String location = container.getNodeId().getHost();
      CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
        container, location);
      doBookKeepingForAssignedContainer(assigned, container, location, true);
      return assigned;

    }
  }

  private class RackLocalContainerAssigner extends ContainerAssigner {

    RackLocalContainerAssigner() {
      super("RackLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = RackResolver.resolve(container.getNodeId().getHost())
          .getNetworkLocation();
      CookieContainerRequest assigned = getMatchingRequestWithPriority(container,
          location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(
      Container container, boolean honorLocality) {
      if (!honorLocality) {
        String location = RackResolver.resolve(container.getNodeId().getHost())
          .getNetworkLocation();
        CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
            container, location);
        doBookKeepingForAssignedContainer(assigned, container, location,
            honorLocality);
        return assigned;
      }
      return null;
    }
  }

  private class NonLocalContainerAssigner extends ContainerAssigner {

    NonLocalContainerAssigner() {
      super("NonLocal");
    }

    @Override
    public CookieContainerRequest assignNewContainer(Container container) {
      String location = ResourceRequest.ANY;
      CookieContainerRequest assigned = getMatchingRequestWithPriority(container,
          location);
      doBookKeepingForAssignedContainer(assigned, container, location, false);
      return assigned;
    }

    @Override
    public CookieContainerRequest assignReUsedContainer(Container container,
        boolean honorLocality) {
      if (!honorLocality) {
        String location = ResourceRequest.ANY;
        CookieContainerRequest assigned = getMatchingRequestWithoutPriority(
          container, location);
        doBookKeepingForAssignedContainer(assigned, container, location,
            honorLocality);
        return assigned;
      }
      return null;
    }

  }
  
  
  @VisibleForTesting
  class DelayedContainerManager extends Thread {

    class HeldContainerTimerComparator implements Comparator<HeldContainer> {

      @Override
      public int compare(HeldContainer c1,
          HeldContainer c2) {
        return (int) (c1.getNextScheduleTime() - c2.getNextScheduleTime());
      }
    }

    private PriorityBlockingQueue<HeldContainer> delayedContainers =
      new PriorityBlockingQueue<HeldContainer>(20,
        new HeldContainerTimerComparator());

    private volatile boolean tryAssigningAll = false;
    private volatile boolean running = true;

    DelayedContainerManager() {
      super.setName("DelayedContainerManager");
    }
    
    @Override
    public void run() {
      while(running) {
        // Try assigning all containers if there's a request to do so.
        if (tryAssigningAll) {
          doAssignAll();
          tryAssigningAll = false;
        }

        // Try allocating containers which have timed out.
        // Required since these containers may get assigned without
        // locality at this point.
        if (delayedContainers.peek() == null) {
          try {
            synchronized(this) {
              this.wait();
            }
            // Re-loop to see if tryAssignAll is set.
            continue;
          } catch (InterruptedException e) {
            LOG.info("AllocatedContainerManager Thread interrupted");
          }
        } else {
          HeldContainer heldContainer = delayedContainers.peek();
          if (heldContainer == null) {
            continue;
          }
          long currentTs = System.currentTimeMillis();
          long nextScheduleTs = heldContainer.getNextScheduleTime();
          if (currentTs >= nextScheduleTs) {
            // Remove the container and try scheduling it.
            heldContainer = delayedContainers.poll();
            if (heldContainer == null) {
              continue;
            }
            Map<CookieContainerRequest, Container> assignedContainers;
            synchronized(TaskScheduler.this) {
              assignedContainers = assignDelayedContainer(heldContainer);
            }
            informAppAboutAssignments(assignedContainers);
          } else {
            synchronized(this) {
              try {
                // Wait for the next container to be assignable
                heldContainer = delayedContainers.peek();
                long diff = localitySchedulingDelay;
                if (heldContainer != null) {
                  diff = heldContainer.getNextScheduleTime() - currentTs;
                }
                if (diff > 0) {
                  this.wait(diff);
                }
              } catch (InterruptedException e) {
                LOG.info("AllocatedContainerManager Thread interrupted");
              }
            }
          }
        }
      }
      releasePendingContainers();
    }
    
    private void doAssignAll() {
      // The allocatedContainers queue should not be modified in the middle of an iteration over it.
      // Synchronizing here on TaskScheduler.this to prevent this from happening.
      // The call to assignAll from within this method should NOT add any
      // elements back to the allocatedContainers list. Since they're all
      // delayed elements, de-allocation should not happen either - leaving the
      // list of delayed containers intact, except for the contaienrs which end
      // up getting assigned.
      if (delayedContainers.isEmpty()) {
        return;
      }

      Map<CookieContainerRequest, Container> assignedContainers;
      synchronized(TaskScheduler.this) {
        // honor reuse-locality flags (container not timed out yet), Don't queue
        // (already in queue), don't release (release happens when containers
        // time-out)
        if (LOG.isDebugEnabled()) {
          LOG.debug("Trying to assign all delayed containers to newly received"
            + " tasks");
        }
        assignedContainers = tryAssignReUsedContainers(
          new ContainerIterable(delayedContainers));
      }
      // Inform app
      informAppAboutAssignments(assignedContainers);
    }
    
    /**
     * Indicate that an attempt should be made to allocate all available containers.
     * Intended to be used in cases where new Container requests come in 
     */
    public void tryAssigningAll() {
      this.tryAssigningAll = true;
      synchronized(this) {
        this.notify();
      }
    }

    public void shutdown() {
      this.running = false;
      this.interrupt();
    }
    
    private void releasePendingContainers() {
      List<HeldContainer> pendingContainers = Lists.newArrayListWithCapacity(
        delayedContainers.size());
      delayedContainers.drainTo(pendingContainers);
      releaseUnassignedContainers(new ContainerIterable(pendingContainers));
    }

    private void addDelayedContainer(Container container,
        long nextScheduleTime) {
      HeldContainer delayedContainer = heldContainers.get(container.getId());
      if (delayedContainer == null) {
        // Currently only already running containers are added to this. Hence
        // this condition should never occur.
        // Change this if and when all containers are handled by this construct.
        throw new TezUncheckedException("Attempting to add a non-running"
            + " container to the delayed container list");
      } else {
        delayedContainer.setNextScheduleTime(nextScheduleTime);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding container to delayed queue"
          + ", containerId=" + delayedContainer.getContainer().getId()
          + ", nextScheduleTime=" + delayedContainer.getNextScheduleTime()
          + ", containerExpiry=" + delayedContainer.getContainerExpiryTime());
      }
      boolean added = delayedContainers.offer(delayedContainer);
      synchronized(this) {
        this.notify();
      }
      if (!added) {
        releaseUnassignedContainers(Lists.newArrayList(container));
      }
    }

  }

  private class ContainerIterable implements Iterable<Container> {

    private final Iterable<HeldContainer> delayedContainers;

    ContainerIterable(Iterable<HeldContainer> delayedContainers) {
      this.delayedContainers = delayedContainers;
    }

    @Override
    public Iterator<Container> iterator() {

      final Iterator<HeldContainer> delayedContainerIterator = delayedContainers
          .iterator();

      return new Iterator<Container>() {

        @Override
        public boolean hasNext() {
          return delayedContainerIterator.hasNext();
        }

        @Override
        public Container next() {
          return delayedContainerIterator.next().getContainer();
        }

        @Override
        public void remove() {
          delayedContainerIterator.remove();
        }
      };
    }
  }

  static class HeldContainer {

    enum LocalityMatchLevel {
      NEW,
      NODE,
      RACK,
      NON_LOCAL
    }

    Container container;
    private long nextScheduleTime;
    private Object containerSignature;
    private LocalityMatchLevel localityMatchLevel;
    private long containerExpiryTime;
    
    HeldContainer(Container container,
        long nextScheduleTime,
        long containerExpiryTime,
        Object containerParams) {
      this.container = container;
      this.nextScheduleTime = nextScheduleTime;
      this.containerSignature = containerParams;
      this.localityMatchLevel = LocalityMatchLevel.NODE;
      this.containerExpiryTime = containerExpiryTime;
    }
    
    public Container getContainer() {
      return this.container;
    }
    
    public long getNextScheduleTime() {
      return this.nextScheduleTime;
    }
    
    public void setNextScheduleTime(long nextScheduleTime) {
      this.nextScheduleTime = nextScheduleTime;
    }

    public long getContainerExpiryTime() {
      return this.containerExpiryTime;
    }

    public void setContainerExpiryTime(long containerExpiryTime) {
      this.containerExpiryTime = containerExpiryTime;
    }

    public Object getContainerSignature() {
      return this.containerSignature;
    }

    public synchronized void resetLocalityMatchLevel() {
      localityMatchLevel = LocalityMatchLevel.NEW;
    }

    public synchronized void incrementLocalityMatchLevel() {
      if (localityMatchLevel.equals(LocalityMatchLevel.NEW)) {
        localityMatchLevel = LocalityMatchLevel.NODE;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.NODE)) {
        localityMatchLevel = LocalityMatchLevel.RACK;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.RACK)) {
        localityMatchLevel = LocalityMatchLevel.NON_LOCAL;
      } else if (localityMatchLevel.equals(LocalityMatchLevel.NON_LOCAL)) {
        throw new TezUncheckedException("Cannot increment locality level "
          + " from current NON_LOCAL for container: " + container.getId());
      }
    }

    public LocalityMatchLevel getLocalityMatchLevel() {
      return this.localityMatchLevel;
    }

    @Override
    public String toString() {
      return "HeldContainer: id: " + container.getId()
          + ", nextScheduleTime: " + nextScheduleTime
          + ", localityMatchLevel=" + localityMatchLevel
          + ", signature: "
          + (containerSignature != null? containerSignature.toString():"null");
    }
  }
}