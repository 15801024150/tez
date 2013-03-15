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

package org.apache.hadoop.mapreduce.v2.app2;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.PrivilegedExceptionAction;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileOutputCommitter;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalContainerAllocator;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.jobhistory.AMStartedEvent;
import org.apache.hadoop.mapreduce.jobhistory.ContainerHeartbeatHandler;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEventHandler2;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryParser.TaskInfo;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.mapreduce.v2.api.records.AMInfo;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskType;
import org.apache.hadoop.mapreduce.v2.app2.client.ClientService;
import org.apache.hadoop.mapreduce.v2.app2.client.MRClientService;
import org.apache.hadoop.mapreduce.v2.app2.job.Job;
import org.apache.hadoop.mapreduce.v2.app2.job.Task;
import org.apache.hadoop.mapreduce.v2.app2.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app2.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.event.JobFinishEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskEvent;
import org.apache.hadoop.mapreduce.v2.app2.job.event.TaskEventType;
import org.apache.hadoop.mapreduce.v2.app2.job.impl.JobImpl;
import org.apache.hadoop.mapreduce.v2.app2.launcher.ContainerLauncher;
import org.apache.hadoop.mapreduce.v2.app2.launcher.ContainerLauncherImpl;
import org.apache.hadoop.mapreduce.v2.app2.local.LocalContainerRequestor;
import org.apache.hadoop.mapreduce.v2.app2.metrics.MRAppMetrics;
import org.apache.hadoop.mapreduce.v2.app2.recover.Recovery;
import org.apache.hadoop.mapreduce.v2.app2.recover.RecoveryService;
import org.apache.hadoop.mapreduce.v2.app2.rm.AMSchedulerEvent;
import org.apache.hadoop.mapreduce.v2.app2.rm.AMSchedulerEventType;
import org.apache.hadoop.mapreduce.v2.app2.rm.ContainerAllocator;
import org.apache.hadoop.mapreduce.v2.app2.rm.ContainerRequestor;
import org.apache.hadoop.mapreduce.v2.app2.rm.NMCommunicatorEventType;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMCommunicator;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMCommunicatorEvent;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMCommunicatorEventType;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMContainerAllocator;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMContainerRequestor;
import org.apache.hadoop.mapreduce.v2.app2.rm.RMContainerRequestor.ContainerRequest;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainer;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerEventType;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerMap;
import org.apache.hadoop.mapreduce.v2.app2.rm.container.AMContainerState;
import org.apache.hadoop.mapreduce.v2.app2.rm.node.AMNodeEventType;
import org.apache.hadoop.mapreduce.v2.app2.rm.node.AMNodeMap;
import org.apache.hadoop.mapreduce.v2.app2.speculate.DefaultSpeculator;
import org.apache.hadoop.mapreduce.v2.app2.speculate.Speculator;
import org.apache.hadoop.mapreduce.v2.app2.speculate.SpeculatorEvent;
import org.apache.hadoop.mapreduce.v2.app2.taskclean.TaskCleaner;
import org.apache.hadoop.mapreduce.v2.app2.taskclean.TaskCleanerImpl;
import org.apache.hadoop.mapreduce.v2.util.MRBuilderUtils;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.yarn.Clock;
import org.apache.hadoop.yarn.ClusterInfo;
import org.apache.hadoop.yarn.SystemClock;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.YarnUncaughtExceptionHandler;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.Dispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.hadoop.yarn.service.CompositeService;
import org.apache.hadoop.yarn.service.Service;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.tez.mapreduce.hadoop.DeprecatedKeys;
import org.apache.tez.mapreduce.hadoop.TezTaskUmbilicalProtocol;
import org.apache.tez.mapreduce.hadoop.TaskAttemptListenerImplTez;

/**
 * The Map-Reduce Application Master.
 * The state machine is encapsulated in the implementation of Job interface.
 * All state changes happens via Job interface. Each event
 * results in a Finite State Transition in Job.
 *
 * MR AppMaster is the composition of loosely coupled services. The services
 * interact with each other via events. The components resembles the
 * Actors model. The component acts on received event and send out the
 * events to other components.
 * This keeps it highly concurrent with no or minimal synchronization needs.
 *
 * The events are dispatched by a central Dispatch mechanism. All components
 * register to the Dispatcher.
 *
 * The information is shared across different components using AppContext.
 */

@SuppressWarnings("rawtypes")
public class MRAppMaster extends CompositeService {

  private static final Log LOG = LogFactory.getLog(MRAppMaster.class);

  /**
   * Priority of the MRAppMaster shutdown hook.
   */
  public static final int SHUTDOWN_HOOK_PRIORITY = 30;

  protected Clock clock;
  protected Configuration conf;
  protected long startTime;
  protected long appSubmitTime;
  protected String appName;
  protected final ApplicationAttemptId appAttemptID;
  protected final ContainerId containerID;
  protected final String nmHost;
  protected final int nmPort;
  protected final int nmHttpPort;
  protected AMContainerMap containers;
  protected AMNodeMap nodes;
  protected final MRAppMetrics metrics;
  protected Map<TaskId, TaskInfo> completedTasksFromPreviousRun;
  protected List<AMInfo> amInfos;
  protected AppContext context;
  protected Dispatcher dispatcher;
  protected ClientService clientService;
  protected Recovery recoveryServ;
  protected ContainerLauncher containerLauncher;
  protected TaskCleaner taskCleaner;
  protected Speculator speculator;
  protected ContainerHeartbeatHandler containerHeartbeatHandler;
  protected TaskHeartbeatHandler taskHeartbeatHandler;
  protected TaskAttemptListener taskAttemptListener;
  protected JobTokenSecretManager jobTokenSecretManager =
      new JobTokenSecretManager();
  protected JobId jobId;
  protected boolean newApiCommitter;
  protected OutputCommitter committer;
  protected JobEventDispatcher jobEventDispatcher;
  protected EventHandler<JobHistoryEvent> jobHistoryEventHandler;
  protected AbstractService stagingDirCleanerService;
  protected boolean inRecovery = false;
  protected SpeculatorEventDispatcher speculatorEventDispatcher;
  protected ContainerRequestor containerRequestor;
  protected ContainerAllocator amScheduler;

  protected Job job;
  protected Credentials fsTokens = new Credentials(); // Filled during init
  protected UserGroupInformation currentUser; // Will be setup during init

  public MRAppMaster(ApplicationAttemptId applicationAttemptId,
      ContainerId containerId, String nmHost, int nmPort, int nmHttpPort,
      long appSubmitTime) {
    this(applicationAttemptId, containerId, nmHost, nmPort, nmHttpPort,
        new SystemClock(), appSubmitTime);
  }

  public MRAppMaster(ApplicationAttemptId applicationAttemptId,
      ContainerId containerId, String nmHost, int nmPort, int nmHttpPort,
      Clock clock, long appSubmitTime) {
    super(MRAppMaster.class.getName());
    this.clock = clock;
    this.startTime = clock.getTime();
    this.appSubmitTime = appSubmitTime;
    this.appAttemptID = applicationAttemptId;
    this.containerID = containerId;
    this.nmHost = nmHost;
    this.nmPort = nmPort;
    this.nmHttpPort = nmHttpPort;
    this.metrics = MRAppMetrics.create();
    LOG.info("Created MRAppMaster for application " + applicationAttemptId);
  }

  @Override
  public Configuration getConfig() {
    return this.conf;
  }

  @Override
  public void init(final Configuration config) {
    conf = config;
    conf.setBoolean(Dispatcher.DISPATCHER_EXIT_ON_ERROR_KEY, true);

    downloadTokensAndSetupUGI(conf);

    context = new RunningAppContext(conf);

    // Job name is the same as the app name util we support DAG of jobs
    // for an app later
    appName = conf.get(MRJobConfig.JOB_NAME, "<missing app name>");

    conf.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, appAttemptID.getAttemptId());

    newApiCommitter = false;
    jobId = MRBuilderUtils.newJobId(appAttemptID.getApplicationId(),
        appAttemptID.getApplicationId().getId());
    int numReduceTasks = conf.getInt(MRJobConfig.NUM_REDUCES, 0);
    if ((numReduceTasks > 0 &&
        conf.getBoolean("mapred.reducer.new-api", false)) ||
          (numReduceTasks == 0 &&
           conf.getBoolean("mapred.mapper.new-api", false)))  {
      newApiCommitter = true;
      LOG.info("Using mapred newApiCommitter.");
    }

    committer = createOutputCommitter(conf);
    boolean recoveryEnabled = conf.getBoolean(
        MRJobConfig.MR_AM_JOB_RECOVERY_ENABLE, true);
    boolean recoverySupportedByCommitter = committer.isRecoverySupported();
    if (recoveryEnabled && recoverySupportedByCommitter
        && appAttemptID.getAttemptId() > 1) {
      LOG.info("Recovery is enabled. "
          + "Will try to recover from previous life on best effort basis.");
      recoveryServ = createRecoveryService(context);
      addIfService(recoveryServ);
      dispatcher = recoveryServ.getDispatcher();
      clock = recoveryServ.getClock();
      inRecovery = true;
    } else {
      LOG.info("Not starting RecoveryService: recoveryEnabled: "
          + recoveryEnabled + " recoverySupportedByCommitter: "
          + recoverySupportedByCommitter + " ApplicationAttemptID: "
          + appAttemptID.getAttemptId());
      dispatcher = createDispatcher();
      addIfService(dispatcher);
    }

    taskHeartbeatHandler = createTaskHeartbeatHandler(context, conf);
    addIfService(taskHeartbeatHandler);

    containerHeartbeatHandler = createContainerHeartbeatHandler(context, conf);
    addIfService(containerHeartbeatHandler);

    //service to handle requests to TaskUmbilicalProtocol
    taskAttemptListener = createTaskAttemptListener(context,
        taskHeartbeatHandler, containerHeartbeatHandler);
    addIfService(taskAttemptListener);

    containers = new AMContainerMap(containerHeartbeatHandler,
        taskAttemptListener, context);
    addIfService(containers);
    dispatcher.register(AMContainerEventType.class, containers);

    nodes = new AMNodeMap(dispatcher.getEventHandler(), context);
    addIfService(nodes);
    dispatcher.register(AMNodeEventType.class, nodes);

    //service to do the task cleanup
    taskCleaner = createTaskCleaner(context);
    addIfService(taskCleaner);

    //service to handle requests from JobClient
    clientService = createClientService(context);
    addIfService(clientService);

    //service to log job history events
    jobHistoryEventHandler = createJobHistoryHandler(context);
    dispatcher.register(org.apache.hadoop.mapreduce.jobhistory.EventType.class,
        jobHistoryEventHandler);

    this.jobEventDispatcher = new JobEventDispatcher();

    //register the event dispatchers
    dispatcher.register(JobEventType.class, jobEventDispatcher);
    dispatcher.register(TaskEventType.class, new TaskEventDispatcher());
    dispatcher.register(TaskAttemptEventType.class,
        new TaskAttemptEventDispatcher());
    dispatcher.register(TaskCleaner.EventType.class, taskCleaner);

    if (conf.getBoolean(MRJobConfig.MAP_SPECULATIVE, false)
        || conf.getBoolean(MRJobConfig.REDUCE_SPECULATIVE, false)) {
      //optional service to speculate on task attempts' progress
      speculator = createSpeculator(conf, context);
      addIfService(speculator);
    }

    speculatorEventDispatcher = new SpeculatorEventDispatcher(conf);
    dispatcher.register(Speculator.EventType.class,
        speculatorEventDispatcher);

    //    TODO XXX: Rename to NMComm
    //    corresponding service to launch allocated containers via NodeManager
    //    containerLauncher = createNMCommunicator(context);
    containerLauncher = createContainerLauncher(context);
    addIfService(containerLauncher);
    dispatcher.register(NMCommunicatorEventType.class, containerLauncher);

    // service to allocate containers from RM (if non-uber) or to fake it (uber)
    containerRequestor = createContainerRequestor(clientService, context);
    addIfService(containerRequestor);
    dispatcher.register(RMCommunicatorEventType.class, containerRequestor);

    amScheduler = createAMScheduler(containerRequestor, context);
    addIfService(amScheduler);
    dispatcher.register(AMSchedulerEventType.class, amScheduler);

    // Add the staging directory cleaner before the history server but after
    // the container allocator so the staging directory is cleaned after
    // the history has been flushed but before unregistering with the RM.
    this.stagingDirCleanerService = createStagingDirCleaningService();
    addService(stagingDirCleanerService);


    // Add the JobHistoryEventHandler last so that it is properly stopped first.
    // This will guarantee that all history-events are flushed before AM goes
    // ahead with shutdown.
    // Note: Even though JobHistoryEventHandler is started last, if any
    // component creates a JobHistoryEvent in the meanwhile, it will be just be
    // queued inside the JobHistoryEventHandler
    addIfService(this.jobHistoryEventHandler);

    super.init(conf);
  } // end of init()

  protected Dispatcher createDispatcher() {
    return new AsyncDispatcher();
  }

  protected OutputCommitter createOutputCommitter(Configuration conf) {
    OutputCommitter committer = null;

    LOG.info("OutputCommitter set in config "
        + conf.get("mapred.output.committer.class"));

    if (newApiCommitter) {
      org.apache.hadoop.mapreduce.v2.api.records.TaskId taskID = MRBuilderUtils
          .newTaskId(jobId, 0, TaskType.MAP);
      org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId attemptID = MRBuilderUtils
          .newTaskAttemptId(taskID, 0);
      TaskAttemptContext taskContext = new TaskAttemptContextImpl(conf,
          TypeConverter.fromYarn(attemptID));
      OutputFormat outputFormat;
      try {
        outputFormat = ReflectionUtils.newInstance(taskContext
            .getOutputFormatClass(), conf);
        committer = outputFormat.getOutputCommitter(taskContext);
      } catch (Exception e) {
        throw new YarnException(e);
      }
    } else {
      committer = ReflectionUtils.newInstance(conf.getClass(
          "mapred.output.committer.class", FileOutputCommitter.class,
          org.apache.hadoop.mapred.OutputCommitter.class), conf);
    }
    LOG.info("OutputCommitter is " + committer.getClass().getName());
    return committer;
  }

  protected boolean keepJobFiles(JobConf conf) {
    return (conf.getKeepTaskFilesPattern() != null || conf
        .getKeepFailedTaskFiles());
  }

  /**
   * Create the default file System for this job.
   * @param conf the conf object
   * @return the default filesystem for this job
   * @throws IOException
   */
  protected FileSystem getFileSystem(Configuration conf) throws IOException {
    return FileSystem.get(conf);
  }

  /**
   * clean up staging directories for the job.
   * @throws IOException
   */
  public void cleanupStagingDir() throws IOException {
    /* make sure we clean the staging files */
    String jobTempDir = null;
    FileSystem fs = getFileSystem(getConfig());
    try {
      if (!keepJobFiles(new JobConf(getConfig()))) {
        jobTempDir = getConfig().get(MRJobConfig.MAPREDUCE_JOB_DIR);
        if (jobTempDir == null) {
          LOG.warn("Job Staging directory is null");
          return;
        }
        Path jobTempDirPath = new Path(jobTempDir);
        LOG.info("Deleting staging directory " + FileSystem.getDefaultUri(getConfig()) +
            " " + jobTempDir);
        fs.delete(jobTempDirPath, true);
      }
    } catch(IOException io) {
      LOG.error("Failed to cleanup staging dir " + jobTempDir, io);
    }
  }

  /**
   * Exit call. Just in a function call to enable testing.
   */
  protected void sysexit() {
    System.exit(0);
  }
  protected class JobFinishEventHandlerCR implements EventHandler<JobFinishEvent> {
    // Considering TaskAttempts are marked as completed before a container exit,
    // it's very likely that a Container may not have "completed" by the time a
    // job completes. This would imply that TaskAtetmpts may not be at a FINAL
    // internal state (state machine state), and cleanup would not have happened.

    // Since the shutdown handler has been called in the same thread which
    // is handling all other async events, creating a separate thread for shutdown.
    //
    // For now, checking to see if all containers have COMPLETED, with a 5
    // second timeout before the exit.
    public void handle(JobFinishEvent event) {
      LOG.info("Handling JobFinished Event");
      AMShutdownRunnable r = new AMShutdownRunnable();
      Thread t = new Thread(r, "AMShutdownThread");
      t.start();
    }

    protected void maybeSendJobEndNotification() {
      if (getConfig().get(MRJobConfig.MR_JOB_END_NOTIFICATION_URL) != null) {
        try {
          LOG.info("Job end notification started for jobID : "
              + job.getReport().getJobId());
          JobEndNotifier notifier = new JobEndNotifier();
          notifier.setConf(getConfig());
          notifier.notify(job.getReport());
        } catch (InterruptedException ie) {
          LOG.warn("Job end notification interrupted for jobID : "
              + job.getReport().getJobId(), ie);
        }
      }
    }

    protected void stopAllServices() {
      try {
        // Stop all services
        // This will also send the final report to the ResourceManager
        LOG.info("Calling stop for all the services");
        stop();

      } catch (Throwable t) {
        LOG.warn("Graceful stop failed ", t);
      }
    }

    protected void exit() {
      LOG.info("Exiting MR AppMaster..GoodBye!");
      sysexit();
    }

    private void stopAM() {
      stopAllServices();
      exit();
    }

    protected boolean allContainersComplete() {
      for (AMContainer amContainer : context.getAllContainers().values()) {
        if (amContainer.getState() != AMContainerState.COMPLETED) {
          return false;
        }
      }
      return true;
    }

    protected boolean allTaskAttemptsComplete() {
      // TODO XXX: Implement.
      // TaskAttempts will transition to their final state machine state only
      // after a container is complete and sends out a TA_TERMINATED event.
      return true;
    }

    private class AMShutdownRunnable implements Runnable {
      @Override
      public void run() {
        maybeSendJobEndNotification();
        // TODO XXX Add a timeout.
        LOG.info("Waiting for all containers and TaskAttempts to complete");
        if (!job.isUber()) {
          while (!allContainersComplete() || !allTaskAttemptsComplete()) {
            try {
              synchronized (this) {
                wait(100l);
              }
            } catch (InterruptedException e) {
              LOG.info("AM Shutdown Thread interrupted. Exiting");
              break;
            }
          }
          LOG.info("All Containers and TaskAttempts Complete. Stopping services");
        } else {
          LOG.info("Uberized job. Not waiting for all containers to finish");
        }
        stopAM();
        LOG.info("AM Shutdown Thread Completing");
      }
    }
  }

  private class JobFinishEventHandler implements EventHandler<JobFinishEvent> {
    @Override
    public void handle(JobFinishEvent event) {
      // job has finished
      // this is the only job, so shut down the Appmaster
      // note in a workflow scenario, this may lead to creation of a new
      // job (FIXME?)
      // Send job-end notification
      if (getConfig().get(MRJobConfig.MR_JOB_END_NOTIFICATION_URL) != null) {
        try {
          LOG.info("Job end notification started for jobID : "
              + job.getReport().getJobId());
          JobEndNotifier notifier = new JobEndNotifier();
          notifier.setConf(getConfig());
          notifier.notify(job.getReport());
        } catch (InterruptedException ie) {
          LOG.warn("Job end notification interrupted for jobID : "
              + job.getReport().getJobId(), ie);
        }
      }

      // TODO:currently just wait for some time so clients can know the
      // final states. Will be removed once RM come on.
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      try {
        // Stop all services
        // This will also send the final report to the ResourceManager
        LOG.info("Calling stop for all the services");
        stop();

      } catch (Throwable t) {
        LOG.warn("Graceful stop failed ", t);
      }

      //Bring the process down by force.
      //Not needed after HADOOP-7140
      LOG.info("Exiting MR AppMaster..GoodBye!");
      sysexit();
    }
  }

  /**
   * create an event handler that handles the job finish event.
   * @return the job finish event handler.
   */
  protected EventHandler<JobFinishEvent> createJobFinishEventHandler() {
    return new JobFinishEventHandler();
  }

  /**
   * Create the recovery service.
   * @return an instance of the recovery service.
   */
  protected Recovery createRecoveryService(AppContext appContext) {
    return new RecoveryService(appContext, getCommitter());
  }

  /**
   * Create the RMContainerRequestor.
   *
   * @param clientService
   *          the MR Client Service.
   * @param appContext
   *          the application context.
   * @return an instance of the RMContainerRequestor.
   */
  protected ContainerRequestor createContainerRequestor(
      ClientService clientService, AppContext appContext) {
    return new ContainerRequestorRouter(clientService, appContext);
  }

  /**
   * Create the AM Scheduler.
   *
   * @param requestor
   *          The Container Requestor.
   * @param appContext
   *          the application context.
   * @return an instance of the AMScheduler.
   */
  protected ContainerAllocator createAMScheduler(ContainerRequestor requestor,
      AppContext appContext) {
    return new AMSchedulerRouter(requestor, appContext);
  }

  /** Create and initialize (but don't start) a single job. */
  protected Job createJob(Configuration conf) {

    // create single job
    Job newJob =
        new JobImpl(jobId, appAttemptID, conf, dispatcher.getEventHandler(),
            taskAttemptListener, jobTokenSecretManager, fsTokens, clock,
            completedTasksFromPreviousRun, metrics, committer, newApiCommitter,
            currentUser.getUserName(), appSubmitTime, amInfos,
            taskHeartbeatHandler, context);
    ((RunningAppContext) context).jobs.put(newJob.getID(), newJob);

    dispatcher.register(JobFinishEvent.Type.class,
        createJobFinishEventHandler());
    return newJob;
  } // end createJob()


  /**
   * Obtain the tokens needed by the job and put them in the UGI
   * @param conf
   */
  protected void downloadTokensAndSetupUGI(Configuration conf) {

    try {
      this.currentUser = UserGroupInformation.getCurrentUser();

      if (UserGroupInformation.isSecurityEnabled()) {
        // Read the file-system tokens from the localized tokens-file.
        Path jobSubmitDir =
            FileContext.getLocalFSFileContext().makeQualified(
                new Path(new File(MRJobConfig.JOB_SUBMIT_DIR)
                    .getAbsolutePath()));
        Path jobTokenFile =
            new Path(jobSubmitDir, MRJobConfig.APPLICATION_TOKENS_FILE);
        fsTokens.addAll(Credentials.readTokenStorageFile(jobTokenFile, conf));
        LOG.info("jobSubmitDir=" + jobSubmitDir + " jobTokenFile="
            + jobTokenFile);

        for (Token<? extends TokenIdentifier> tk : fsTokens.getAllTokens()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Token of kind " + tk.getKind()
                + "in current ugi in the AppMaster for service "
                + tk.getService());
          }
          currentUser.addToken(tk); // For use by AppMaster itself.
        }
      }
    } catch (IOException e) {
      throw new YarnException(e);
    }
  }

  protected void addIfService(Object object) {
    if (object instanceof Service) {
      addService((Service) object);
    }
  }

  protected EventHandler<JobHistoryEvent> createJobHistoryHandler(
      AppContext context) {
    return new JobHistoryEventHandler2(context, getStartCount());
  }

  protected AbstractService createStagingDirCleaningService() {
    return new StagingDirCleaningService();
  }

  protected Speculator createSpeculator(Configuration conf, AppContext context) {
    Class<? extends Speculator> speculatorClass;

    try {
      speculatorClass
          // "yarn.mapreduce.job.speculator.class"
          = conf.getClass(MRJobConfig.MR_AM_JOB_SPECULATOR,
                          DefaultSpeculator.class,
                          Speculator.class);
      Constructor<? extends Speculator> speculatorConstructor
          = speculatorClass.getConstructor
               (Configuration.class, AppContext.class);
      Speculator result = speculatorConstructor.newInstance(conf, context);

      return result;
    } catch (InstantiationException ex) {
      LOG.error("Can't make a speculator -- check "
          + MRJobConfig.MR_AM_JOB_SPECULATOR, ex);
      throw new YarnException(ex);
    } catch (IllegalAccessException ex) {
      LOG.error("Can't make a speculator -- check "
          + MRJobConfig.MR_AM_JOB_SPECULATOR, ex);
      throw new YarnException(ex);
    } catch (InvocationTargetException ex) {
      LOG.error("Can't make a speculator -- check "
          + MRJobConfig.MR_AM_JOB_SPECULATOR, ex);
      throw new YarnException(ex);
    } catch (NoSuchMethodException ex) {
      LOG.error("Can't make a speculator -- check "
          + MRJobConfig.MR_AM_JOB_SPECULATOR, ex);
      throw new YarnException(ex);
    }
  }

  protected TaskAttemptListener createTaskAttemptListener(AppContext context,
      TaskHeartbeatHandler thh, ContainerHeartbeatHandler chh) {
    TaskAttemptListener lis = new TaskAttemptListenerImplTez(context, thh, chh,
        jobTokenSecretManager);
    return lis;
  }

  protected TaskHeartbeatHandler createTaskHeartbeatHandler(AppContext context,
      Configuration conf) {
    TaskHeartbeatHandler thh = new TaskHeartbeatHandler(context, conf.getInt(
        MRJobConfig.MR_AM_TASK_LISTENER_THREAD_COUNT,
        MRJobConfig.DEFAULT_MR_AM_TASK_LISTENER_THREAD_COUNT));
    return thh;
  }

  protected ContainerHeartbeatHandler createContainerHeartbeatHandler(AppContext context,
      Configuration conf) {
    ContainerHeartbeatHandler chh = new ContainerHeartbeatHandler(context, conf.getInt(
        MRJobConfig.MR_AM_TASK_LISTENER_THREAD_COUNT,
        MRJobConfig.DEFAULT_MR_AM_TASK_LISTENER_THREAD_COUNT));
    // TODO XXX: Define a CONTAINER_LISTENER_THREAD_COUNT
    return chh;
  }


  protected TaskCleaner createTaskCleaner(AppContext context) {
    return new TaskCleanerImpl(context);
  }

  protected ContainerLauncher
      createContainerLauncher(final AppContext context) {
    return new ContainerLauncherImpl(context);
  }

  //TODO:should have an interface for MRClientService
  protected ClientService createClientService(AppContext context) {
    return new MRClientService(context);
  }

  public ApplicationId getAppID() {
    return appAttemptID.getApplicationId();
  }

  public ApplicationAttemptId getAttemptID() {
    return appAttemptID;
  }

  public JobId getJobId() {
    return jobId;
  }

  public OutputCommitter getCommitter() {
    return committer;
  }

  public boolean isNewApiCommitter() {
    return newApiCommitter;
  }

  public int getStartCount() {
    return appAttemptID.getAttemptId();
  }

  public AppContext getContext() {
    return context;
  }

  public Dispatcher getDispatcher() {
    return dispatcher;
  }

  public Map<TaskId, TaskInfo> getCompletedTaskFromPreviousRun() {
    return completedTasksFromPreviousRun;
  }

  public List<AMInfo> getAllAMInfos() {
    return amInfos;
  }

  public ContainerLauncher getContainerLauncher() {
    return containerLauncher;
  }

  public TaskAttemptListener getTaskAttemptListener() {
    return taskAttemptListener;
  }

  /**
   * By the time life-cycle of this router starts, job-init would have already
   * happened.
   */
  private final class ContainerRequestorRouter extends AbstractService
      implements ContainerRequestor {
    private final ClientService clientService;
    private final AppContext context;
    private ContainerRequestor real;

    public ContainerRequestorRouter(ClientService clientService,
        AppContext appContext) {
      super(ContainerRequestorRouter.class.getName());
      this.clientService = clientService;
      this.context = appContext;
    }

    @Override
    public void start() {
      if (job.isUber()) {
        real = new LocalContainerRequestor(clientService,
            context);
      } else {
        real = new RMContainerRequestor(clientService, context);
      }
      ((Service)this.real).init(getConfig());
      ((Service)this.real).start();
      super.start();
    }

    @Override
    public void stop() {
      if (real != null) {
        ((Service) real).stop();
      }
      super.stop();
    }

    @Override
    public void handle(RMCommunicatorEvent event) {
      real.handle(event);
    }

    @Override
    public Resource getAvailableResources() {
      return real.getAvailableResources();
    }

    @Override
    public void addContainerReq(ContainerRequest req) {
      real.addContainerReq(req);
    }

    @Override
    public void decContainerReq(ContainerRequest req) {
      real.decContainerReq(req);
    }

    public void setSignalled(boolean isSignalled) {
      ((RMCommunicator) real).setSignalled(isSignalled);
    }

    @Override
    public Map<ApplicationAccessType, String> getApplicationACLs() {
      return ((RMCommunicator)real).getApplicationAcls();
    }
  }

  /**
   * By the time life-cycle of this router starts, job-init would have already
   * happened.
   */
  private final class AMSchedulerRouter extends AbstractService
      implements ContainerAllocator {
    private final ContainerRequestor requestor;
    private final AppContext context;
    private ContainerAllocator containerAllocator;

    AMSchedulerRouter(ContainerRequestor requestor,
        AppContext context) {
      super(AMSchedulerRouter.class.getName());
      this.requestor = requestor;
      this.context = context;
    }

    @Override
    public synchronized void start() {
      if (job.isUber()) {
        this.containerAllocator = new LocalContainerAllocator(this.context,
            jobId, nmHost, nmPort, nmHttpPort, containerID,
            (TezTaskUmbilicalProtocol) taskAttemptListener, taskAttemptListener,
            (RMCommunicator) this.requestor);
      } else {
        this.containerAllocator = new RMContainerAllocator(this.requestor,
            this.context);
      }
      ((Service)this.containerAllocator).init(getConfig());
      ((Service)this.containerAllocator).start();
      super.start();
    }

    @Override
    public synchronized void stop() {
      if (containerAllocator != null) {
        ((Service) this.containerAllocator).stop();
        super.stop();
      }
    }

    @Override
    public void handle(AMSchedulerEvent event) {
      this.containerAllocator.handle(event);
    }
  }

  public TaskHeartbeatHandler getTaskHeartbeatHandler() {
    return taskHeartbeatHandler;
  }

  private final class StagingDirCleaningService extends AbstractService {
    StagingDirCleaningService() {
      super(StagingDirCleaningService.class.getName());
    }

    @Override
    public synchronized void stop() {
      try {
        cleanupStagingDir();
      } catch (IOException io) {
        LOG.error("Failed to cleanup staging dir: ", io);
      }
      super.stop();
    }
  }

  public class RunningAppContext implements AppContext {

    protected final Map<JobId, Job> jobs = new ConcurrentHashMap<JobId, Job>();
    protected Configuration conf;
    protected final ClusterInfo clusterInfo = new ClusterInfo();

    public RunningAppContext(Configuration config) {
      this.conf = config;
    }

    public void setConfiguration(Configuration config) {
      this.conf = config;
    }

    @Override
    public ApplicationAttemptId getApplicationAttemptId() {
      return appAttemptID;
    }

    @Override
    public ApplicationId getApplicationID() {
      return appAttemptID.getApplicationId();
    }

    @Override
    public String getApplicationName() {
      return appName;
    }

    @Override
    public long getStartTime() {
      return startTime;
    }

    @Override
    public Job getJob(JobId jobID) {
      return jobs.get(jobID);
    }

    @Override
    public Map<JobId, Job> getAllJobs() {
      return jobs;
    }

    @Override
    public EventHandler getEventHandler() {
      return dispatcher.getEventHandler();
    }

    @Override
    public CharSequence getUser() {
      return this.conf.get(MRJobConfig.USER_NAME);
    }

    @Override
    public Clock getClock() {
      return clock;
    }

    @Override
    public ClusterInfo getClusterInfo() {
      return this.clusterInfo;
    }

    @Override
    public AMContainerMap getAllContainers() {
      return containers;
    }

    @Override
    public AMNodeMap getAllNodes() {
      return nodes;
    }

    @Override
    public Map<ApplicationAccessType, String> getApplicationACLs() {
      if (getServiceState() != STATE.STARTED) {
        throw new YarnException(
            "Cannot get ApplicationACLs before all services have started");
      }
      return containerRequestor.getApplicationACLs();
    }
  }

  @Override
  public void start() {
    startAM(this.conf);

    //start all the components
    super.start();

    // All components have started, start the job.
    startJobs();
  }

  @SuppressWarnings("unchecked")
  protected void startAM(Configuration config) {
    this.conf = config;

    // Pull completedTasks etc from recovery
    if (inRecovery) {
      completedTasksFromPreviousRun = recoveryServ.getCompletedTasks();
      amInfos = recoveryServ.getAMInfos();
    }

    // / Create the AMInfo for the current AppMaster
    if (amInfos == null) {
      amInfos = new LinkedList<AMInfo>();
    }
    AMInfo amInfo =
        MRBuilderUtils.newAMInfo(appAttemptID, startTime, containerID, nmHost,
            nmPort, nmHttpPort);
    amInfos.add(amInfo);

    // /////////////////// Create the job itself.
    job = createJob(getConfig());

    // End of creating the job.

    // Send out an MR AM inited event for this AM and all previous AMs.
    for (AMInfo info : amInfos) {
      dispatcher.getEventHandler().handle(
          new JobHistoryEvent(job.getID(), new AMStartedEvent(info
              .getAppAttemptId(), info.getStartTime(), info.getContainerId(),
              info.getNodeManagerHost(), info.getNodeManagerPort(), info
                  .getNodeManagerHttpPort())));
    }

    // metrics system init is really init & start.
    // It's more test friendly to put it here.
    DefaultMetricsSystem.initialize("MRAppMaster");

    // create a job event for job intialization
    JobEvent initJobEvent = new JobEvent(job.getID(), JobEventType.JOB_INIT);
    // Send init to the job (this does NOT trigger job execution)
    // This is a synchronous call, not an event through dispatcher. We want
    // job-init to be done completely here.
    jobEventDispatcher.handle(initJobEvent);


    // JobImpl's InitTransition is done (call above is synchronous), so the
    // "uber-decision" (MR-1220) has been made.  Query job and switch to
    // ubermode if appropriate (by registering different container-allocator
    // and container-launcher services/event-handlers).

    if (job.isUber()) {
      speculatorEventDispatcher.disableSpeculation();
      LOG.info("MRAppMaster uberizing job " + job.getID()
               + " in local container (\"uber-AM\") on node "
               + nmHost + ":" + nmPort + ".");
    } else {
      // send init to speculator only for non-uber jobs.
      // This won't yet start as dispatcher isn't started yet.
      dispatcher.getEventHandler().handle(
          new SpeculatorEvent(job.getID(), clock.getTime()));
      LOG.info("MRAppMaster launching normal, non-uberized, multi-container "
               + "job " + job.getID() + ".");
    }

  }

  /**
   * This can be overridden to instantiate multiple jobs and create a
   * workflow.
   *
   * TODO:  Rework the design to actually support this.  Currently much of the
   * job stuff has been moved to init() above to support uberization (MR-1220).
   * In a typical workflow, one presumably would want to uberize only a subset
   * of the jobs (the "small" ones), which is awkward with the current design.
   */
  @SuppressWarnings("unchecked")
  protected void startJobs() {
    /** create a job-start event to get this ball rolling */
    JobEvent startJobEvent = new JobEvent(job.getID(), JobEventType.JOB_START);
    /** send the job-start event. this triggers the job execution. */
    dispatcher.getEventHandler().handle(startJobEvent);
  }

  public class JobEventDispatcher implements EventHandler<JobEvent> {
    @SuppressWarnings("unchecked")
    @Override
    public void handle(JobEvent event) {
      ((EventHandler<JobEvent>)context.getJob(event.getJobId())).handle(event);
    }
  }

  public class TaskEventDispatcher implements EventHandler<TaskEvent> {
    @SuppressWarnings("unchecked")
    @Override
    public void handle(TaskEvent event) {
      Task task = context.getJob(event.getTaskID().getJobId()).getTask(
          event.getTaskID());
      ((EventHandler<TaskEvent>)task).handle(event);
    }
  }

  public class TaskAttemptEventDispatcher
          implements EventHandler<TaskAttemptEvent> {
    @SuppressWarnings("unchecked")
    @Override
    public void handle(TaskAttemptEvent event) {
      Job job = context.getJob(event.getTaskAttemptID().getTaskId().getJobId());
      Task task = job.getTask(event.getTaskAttemptID().getTaskId());
      TaskAttempt attempt = task.getAttempt(event.getTaskAttemptID());
      ((EventHandler<TaskAttemptEvent>) attempt).handle(event);
    }
  }

  public class SpeculatorEventDispatcher implements
      EventHandler<SpeculatorEvent> {
    private final Configuration conf;
    private volatile boolean disabled;
    public SpeculatorEventDispatcher(Configuration config) {
      this.conf = config;
    }
    @Override
    public void handle(SpeculatorEvent event) {
      if (disabled) {
        return;
      }

      TaskId tId = event.getTaskID();
      TaskType tType = null;
      /* event's TaskId will be null if the event type is JOB_CREATE or
       * ATTEMPT_STATUS_UPDATE
       */
      if (tId != null) {
        tType = tId.getTaskType();
      }
      boolean shouldMapSpec =
              conf.getBoolean(MRJobConfig.MAP_SPECULATIVE, false);
      boolean shouldReduceSpec =
              conf.getBoolean(MRJobConfig.REDUCE_SPECULATIVE, false);

      /* The point of the following is to allow the MAP and REDUCE speculative
       * config values to be independent:
       * IF spec-exec is turned on for maps AND the task is a map task
       * OR IF spec-exec is turned on for reduces AND the task is a reduce task
       * THEN call the speculator to handle the event.
       */
      if ( (shouldMapSpec && (tType == null || tType == TaskType.MAP))
        || (shouldReduceSpec && (tType == null || tType == TaskType.REDUCE))) {
        // Speculator IS enabled, direct the event to there.
        speculator.handle(event);
      }
    }

    public void disableSpeculation() {
      disabled = true;
    }

  }

  protected static void validateInputParam(String value, String param)
      throws IOException {
    if (value == null) {
      String msg = param + " is null";
      LOG.error(msg);
      throw new IOException(msg);
    }
  }

  public static void main(String[] args) {
    try {
      Thread.setDefaultUncaughtExceptionHandler(new YarnUncaughtExceptionHandler());
      DeprecatedKeys.init();
      String containerIdStr =
          System.getenv(ApplicationConstants.AM_CONTAINER_ID_ENV);
      String nodeHostString = System.getenv(ApplicationConstants.NM_HOST_ENV);
      String nodePortString = System.getenv(ApplicationConstants.NM_PORT_ENV);
      String nodeHttpPortString =
          System.getenv(ApplicationConstants.NM_HTTP_PORT_ENV);
      String appSubmitTimeStr =
          System.getenv(ApplicationConstants.APP_SUBMIT_TIME_ENV);

      validateInputParam(containerIdStr,
          ApplicationConstants.AM_CONTAINER_ID_ENV);
      validateInputParam(nodeHostString, ApplicationConstants.NM_HOST_ENV);
      validateInputParam(nodePortString, ApplicationConstants.NM_PORT_ENV);
      validateInputParam(nodeHttpPortString,
          ApplicationConstants.NM_HTTP_PORT_ENV);
      validateInputParam(appSubmitTimeStr,
          ApplicationConstants.APP_SUBMIT_TIME_ENV);

      ContainerId containerId = ConverterUtils.toContainerId(containerIdStr);
      ApplicationAttemptId applicationAttemptId =
          containerId.getApplicationAttemptId();
      long appSubmitTime = Long.parseLong(appSubmitTimeStr);

      MRAppMaster appMaster =
          new MRAppMaster(applicationAttemptId, containerId, nodeHostString,
              Integer.parseInt(nodePortString),
              Integer.parseInt(nodeHttpPortString), appSubmitTime);
      ShutdownHookManager.get().addShutdownHook(
        new MRAppMasterShutdownHook(appMaster), SHUTDOWN_HOOK_PRIORITY);
      YarnConfiguration conf = new YarnConfiguration(new JobConf());
      conf.addResource(new Path(MRJobConfig.JOB_CONF_FILE));
      String jobUserName = System
          .getenv(ApplicationConstants.Environment.USER.name());
      conf.set(MRJobConfig.USER_NAME, jobUserName);
      // Do not automatically close FileSystem objects so that in case of
      // SIGTERM I have a chance to write out the job history. I'll be closing
      // the objects myself.
      conf.setBoolean("fs.automatic.close", false);
      initAndStartAppMaster(appMaster, conf, jobUserName);
    } catch (Throwable t) {
      LOG.fatal("Error starting MRAppMaster", t);
      System.exit(1);
    }
  }

  // The shutdown hook that runs when a signal is received AND during normal
  // close of the JVM.
  static class MRAppMasterShutdownHook implements Runnable {
    MRAppMaster appMaster;
    MRAppMasterShutdownHook(MRAppMaster appMaster) {
      this.appMaster = appMaster;
    }
    public void run() {
      LOG.info("MRAppMaster received a signal. Signaling RMCommunicator and "
        + "JobHistoryEventHandler.");
      // Notify the JHEH and RMCommunicator that a SIGTERM has been received so
      // that they don't take too long in shutting down

      // Signal the RMCommunicator.
      ((ContainerRequestorRouter) appMaster.containerRequestor)
          .setSignalled(true);

      if(appMaster.jobHistoryEventHandler != null) {
        ((JobHistoryEventHandler2) appMaster.jobHistoryEventHandler)
            .setSignalled(true);
      }
      appMaster.stop();
    }
  }

  protected static void initAndStartAppMaster(final MRAppMaster appMaster,
      final YarnConfiguration conf, String jobUserName) throws IOException,
      InterruptedException {
    UserGroupInformation.setConfiguration(conf);
    UserGroupInformation appMasterUgi = UserGroupInformation
        .createRemoteUser(jobUserName);
    appMasterUgi.doAs(new PrivilegedExceptionAction<Object>() {
      @Override
      public Object run() throws Exception {
        appMaster.init(conf);
        appMaster.start();
        return null;
      }
    });
  }
}
