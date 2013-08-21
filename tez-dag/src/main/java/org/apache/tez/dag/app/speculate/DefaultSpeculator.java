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

package org.apache.tez.dag.app.speculate;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.oldrecords.TaskAttemptState;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.dag.DAG;
import org.apache.tez.dag.app.dag.Task;
import org.apache.tez.dag.app.dag.TaskAttempt;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventStatusUpdate.TaskAttemptStatus;
import org.apache.tez.dag.app.dag.event.TaskEvent;
import org.apache.tez.dag.app.dag.event.TaskEventType;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;
import org.apache.tez.dag.records.TezVertexID;
import org.apache.tez.dag.utils.TezBuilderUtils;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;

// FIXME does not handle multiple vertices
public class DefaultSpeculator extends AbstractService implements
    Speculator {

  private static final long ON_SCHEDULE = Long.MIN_VALUE;
  private static final long ALREADY_SPECULATING = Long.MIN_VALUE + 1;
  private static final long TOO_NEW = Long.MIN_VALUE + 2;
  private static final long PROGRESS_IS_GOOD = Long.MIN_VALUE + 3;
  private static final long NOT_RUNNING = Long.MIN_VALUE + 4;
  private static final long TOO_LATE_TO_SPECULATE = Long.MIN_VALUE + 5;

  private static final long SOONEST_RETRY_AFTER_NO_SPECULATE = 1000L * 1L;
  private static final long SOONEST_RETRY_AFTER_SPECULATE = 1000L * 15L;

  private static final double PROPORTION_RUNNING_TASKS_SPECULATABLE = 0.1;
  private static final double PROPORTION_TOTAL_TASKS_SPECULATABLE = 0.01;
  private static final int  MINIMUM_ALLOWED_SPECULATIVE_TASKS = 10;

  private static final Log LOG = LogFactory.getLog(DefaultSpeculator.class);

  private final ConcurrentMap<TezTaskID, Boolean> runningTasks
      = new ConcurrentHashMap<TezTaskID, Boolean>();

  private final Map<Task, AtomicBoolean> pendingSpeculations
      = new ConcurrentHashMap<Task, AtomicBoolean>();

  // These are the current needs, not the initial needs.  For each job, these
  //  record the number of attempts that exist and that are actively
  //  waiting for a container [as opposed to running or finished]
  // TODO handle multiple dags
  private final ConcurrentMap<TezVertexID, AtomicInteger> vertexContainerNeeds
      = new ConcurrentHashMap<TezVertexID, AtomicInteger>();

  private final Set<TezTaskID> mayHaveSpeculated = new HashSet<TezTaskID>();

  private final Configuration conf;
  private AppContext context;
  private Thread speculationBackgroundThread = null;
  private BlockingQueue<SpeculatorEvent> eventQueue
      = new LinkedBlockingQueue<SpeculatorEvent>();
  private TaskRuntimeEstimator estimator;

  private BlockingQueue<Object> scanControl = new LinkedBlockingQueue<Object>();

  private final Clock clock;

  private final EventHandler<TaskEvent> eventHandler;

  public DefaultSpeculator(Configuration conf, AppContext context) {
    this(conf, context, context.getClock());
  }

  public DefaultSpeculator(Configuration conf, AppContext context, Clock clock) {
    this(conf, context, getEstimator(conf, context), clock);
  }

  static private TaskRuntimeEstimator getEstimator
      (Configuration conf, AppContext context) {
    TaskRuntimeEstimator estimator;

    try {
      // "yarn.mapreduce.job.task.runtime.estimator.class"
      Class<? extends TaskRuntimeEstimator> estimatorClass
          = conf.getClass(MRJobConfig.MR_AM_TASK_ESTIMATOR,
                          LegacyTaskRuntimeEstimator.class,
                          TaskRuntimeEstimator.class);

      Constructor<? extends TaskRuntimeEstimator> estimatorConstructor
          = estimatorClass.getConstructor();

      estimator = estimatorConstructor.newInstance();

      estimator.contextualize(conf, context);
    } catch (InstantiationException ex) {
      LOG.error("Can't make a speculation runtime extimator", ex);
      throw new TezUncheckedException(ex);
    } catch (IllegalAccessException ex) {
      LOG.error("Can't make a speculation runtime extimator", ex);
      throw new TezUncheckedException(ex);
    } catch (InvocationTargetException ex) {
      LOG.error("Can't make a speculation runtime extimator", ex);
      throw new TezUncheckedException(ex);
    } catch (NoSuchMethodException ex) {
      LOG.error("Can't make a speculation runtime extimator", ex);
      throw new TezUncheckedException(ex);
    }

  return estimator;
  }

  // This constructor is designed to be called by other constructors.
  //  However, it's public because we do use it in the test cases.
  // Normally we figure out our own estimator.
  public DefaultSpeculator
      (Configuration conf, AppContext context,
       TaskRuntimeEstimator estimator, Clock clock) {
    super(DefaultSpeculator.class.getName());

    this.conf = conf;
    this.context = context;
    this.estimator = estimator;
    this.clock = clock;
    this.eventHandler = context.getEventHandler();
  }

/*   *************************************************************    */

  // This is the task-mongering that creates the two new threads -- one for
  //  processing events from the event queue and one for periodically
  //  looking for speculation opportunities

  @Override
  public void serviceStart() {
    Runnable speculationBackgroundCore
        = new Runnable() {
            @Override
            public void run() {
              while (!Thread.currentThread().isInterrupted()) {
                long backgroundRunStartTime = clock.getTime();
                try {
                  int speculations = computeSpeculations();
                  long mininumRecomp
                      = speculations > 0 ? SOONEST_RETRY_AFTER_SPECULATE
                                         : SOONEST_RETRY_AFTER_NO_SPECULATE;

                  long wait = Math.max(mininumRecomp,
                        clock.getTime() - backgroundRunStartTime);

                  if (speculations > 0) {
                    LOG.info("We launched " + speculations
                        + " speculations.  Sleeping " + wait + " milliseconds.");
                  }

                  Object pollResult
                      = scanControl.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  LOG.error("Background thread returning, interrupted : " + e);
                  e.printStackTrace(System.out);
                  return;
                }
              }
            }
          };
    speculationBackgroundThread = new Thread
        (speculationBackgroundCore, "DefaultSpeculator background processing");
    speculationBackgroundThread.start();
  }

  @Override
  public void serviceStop() {
    // this could be called before background thread is established
    if (speculationBackgroundThread != null) {
      speculationBackgroundThread.interrupt();
    }
  }

  @Override
  public void handleAttempt(TaskAttemptStatus status) {
    long timestamp = clock.getTime();
    statusUpdate(status, timestamp);
  }

  // This section is not part of the Speculator interface; it's used only for
  //  testing
  public boolean eventQueueEmpty() {
    return eventQueue.isEmpty();
  }

  // This interface is intended to be used only for test cases.
  public void scanForSpeculations() {
    LOG.info("We got asked to run a debug speculation scan.");
    // debug
    System.out.println("We got asked to run a debug speculation scan.");
    System.out.println("There are " + scanControl.size()
        + " events stacked already.");
    scanControl.add(new Object());
    Thread.yield();
  }


/*   *************************************************************    */

  // This section contains the code that gets run for a SpeculatorEvent

  private AtomicInteger containerNeed(TezTaskID taskID) {
    TezVertexID vId = taskID.getVertexID();

    AtomicInteger result = vertexContainerNeeds.get(vId);

    if (result == null) {
      vertexContainerNeeds.putIfAbsent(vId, new AtomicInteger(0));
      result = vertexContainerNeeds.get(vId);
    }

    return result;
  }

  private synchronized void processSpeculatorEvent(SpeculatorEvent event) {
    switch (event.getType()) {
      case ATTEMPT_STATUS_UPDATE:
        statusUpdate(event.getReportedStatus(), event.getTimestamp());
        break;

      case TASK_CONTAINER_NEED_UPDATE:
      {
        AtomicInteger need = containerNeed(event.getTaskID());
        need.addAndGet(event.containersNeededChange());
        break;
      }

      case ATTEMPT_START:
      {
        LOG.info("ATTEMPT_START " + event.getTaskID());
        estimator.enrollAttempt
            (event.getReportedStatus(), event.getTimestamp());
        break;
      }

      case JOB_CREATE:
      {
        LOG.info("JOB_CREATE " + event.getJobID());
        estimator.contextualize(getConfig(), context);
        break;
      }
    }
  }

  /**
   * Absorbs one TaskAttemptStatus
   *
   * @param reportedStatus the status report that we got from a task attempt
   *        that we want to fold into the speculation data for this job
   * @param timestamp the time this status corresponds to.  This matters
   *        because statuses contain progress.
   */
  protected void statusUpdate(TaskAttemptStatus reportedStatus, long timestamp) {

    String stateString = reportedStatus.taskState.toString();

    TezTaskAttemptID attemptID = reportedStatus.id;
    TezTaskID taskID = attemptID.getTaskID();
    DAG job = context.getCurrentDAG();

    if (job == null) {
      return;
    }

    Task task = job.getVertex(taskID.getVertexID()).getTask(taskID);

    if (task == null) {
      return;
    }

    estimator.updateAttempt(reportedStatus, timestamp);

    // If the task is already known to be speculation-bait, don't do anything
    if (pendingSpeculations.get(task) != null) {
      if (pendingSpeculations.get(task).get()) {
        return;
      }
    }

    if (stateString.equals(TaskAttemptState.RUNNING.name())) {
      runningTasks.putIfAbsent(taskID, Boolean.TRUE);
    } else {
      runningTasks.remove(taskID, Boolean.TRUE);
    }
  }

/*   *************************************************************    */

// This is the code section that runs periodically and adds speculations for
//  those jobs that need them.


  // This can return a few magic values for tasks that shouldn't speculate:
  //  returns ON_SCHEDULE if thresholdRuntime(taskID) says that we should not
  //     considering speculating this task
  //  returns ALREADY_SPECULATING if that is true.  This has priority.
  //  returns TOO_NEW if our companion task hasn't gotten any information
  //  returns PROGRESS_IS_GOOD if the task is sailing through
  //  returns NOT_RUNNING if the task is not running
  //
  // All of these values are negative.  Any value that should be allowed to
  //  speculate is 0 or positive.
  private long speculationValue(TezTaskID taskID, long now) {
    DAG job = context.getCurrentDAG();
    Task task = job.getVertex(taskID.getVertexID()).getTask(taskID);
    Map<TezTaskAttemptID, TaskAttempt> attempts = task.getAttempts();
    long acceptableRuntime = Long.MIN_VALUE;
    long result = Long.MIN_VALUE;

    if (!mayHaveSpeculated.contains(taskID)) {
      acceptableRuntime = estimator.thresholdRuntime(taskID);
      if (acceptableRuntime == Long.MAX_VALUE) {
        return ON_SCHEDULE;
      }
    }

    TezTaskAttemptID runningTaskAttemptID = null;

    int numberRunningAttempts = 0;

    for (TaskAttempt taskAttempt : attempts.values()) {
      if (taskAttempt.getState() == TaskAttemptState.RUNNING
          || taskAttempt.getState() == TaskAttemptState.STARTING) {
        if (++numberRunningAttempts > 1) {
          return ALREADY_SPECULATING;
        }
        runningTaskAttemptID = taskAttempt.getID();

        long estimatedRunTime = estimator.estimatedRuntime(runningTaskAttemptID);

        long taskAttemptStartTime
            = estimator.attemptEnrolledTime(runningTaskAttemptID);
        if (taskAttemptStartTime > now) {
          // This background process ran before we could process the task
          //  attempt status change that chronicles the attempt start
          return TOO_NEW;
        }

        long estimatedEndTime = estimatedRunTime + taskAttemptStartTime;

        long estimatedReplacementEndTime
            = now + estimator.estimatedNewAttemptRuntime(taskID);

        if (estimatedEndTime < now) {
          return PROGRESS_IS_GOOD;
        }

        if (estimatedReplacementEndTime >= estimatedEndTime) {
          return TOO_LATE_TO_SPECULATE;
        }

        result = estimatedEndTime - estimatedReplacementEndTime;
      }
    }

    // If we are here, there's at most one task attempt.
    if (numberRunningAttempts == 0) {
      return NOT_RUNNING;
    }



    if (acceptableRuntime == Long.MIN_VALUE) {
      acceptableRuntime = estimator.thresholdRuntime(taskID);
      if (acceptableRuntime == Long.MAX_VALUE) {
        return ON_SCHEDULE;
      }
    }

    return result;
  }

  //Add attempt to a given Task.
  protected void addSpeculativeAttempt(TezTaskID taskID) {
    LOG.info
        ("DefaultSpeculator.addSpeculativeAttempt -- we are speculating " + taskID);
    eventHandler.handle(new TaskEvent(taskID, TaskEventType.T_ADD_SPEC_ATTEMPT));
    mayHaveSpeculated.add(taskID);
  }

  @Override
  public void handle(SpeculatorEvent event) {
    processSpeculatorEvent(event);
  }


  private int maybeScheduleAMapSpeculation() {
    return maybeScheduleASpeculation(0);
  }

  private int maybeScheduleAReduceSpeculation() {
    return maybeScheduleASpeculation(1);
  }

  private int maybeScheduleASpeculation(int vertexId) {
    int successes = 0;

    long now = clock.getTime();

    // FIXME this needs to be fixed for a DAG
    // TODO handle multiple dags
    for (ConcurrentMap.Entry<TezVertexID, AtomicInteger> vertexEntry :
        vertexContainerNeeds.entrySet()) {
      // This race conditon is okay.  If we skip a speculation attempt we
      //  should have tried because the event that lowers the number of
      //  containers needed to zero hasn't come through, it will next time.
      // Also, if we miss the fact that the number of containers needed was
      //  zero but increased due to a failure it's not too bad to launch one
      //  container prematurely.
      if (vertexEntry.getValue().get() > 0) {
        continue;
      }

      int numberSpeculationsAlready = 0;
      int numberRunningTasks = 0;

      // loop through the tasks of the kind
      DAG job = context.getCurrentDAG();

      Map<TezTaskID, Task> tasks =
          job.getVertex(TezBuilderUtils.newVertexID(job.getID(), vertexId)).getTasks();

      int numberAllowedSpeculativeTasks
          = (int) Math.max(MINIMUM_ALLOWED_SPECULATIVE_TASKS,
                           PROPORTION_TOTAL_TASKS_SPECULATABLE * tasks.size());

      TezTaskID bestTaskID = null;
      long bestSpeculationValue = -1L;

      // this loop is potentially pricey.
      // TODO track the tasks that are potentially worth looking at
      for (Map.Entry<TezTaskID, Task> taskEntry : tasks.entrySet()) {
        long mySpeculationValue = speculationValue(taskEntry.getKey(), now);

        if (mySpeculationValue == ALREADY_SPECULATING) {
          ++numberSpeculationsAlready;
        }

        if (mySpeculationValue != NOT_RUNNING) {
          ++numberRunningTasks;
        }

        if (mySpeculationValue > bestSpeculationValue) {
          bestTaskID = taskEntry.getKey();
          bestSpeculationValue = mySpeculationValue;
        }
      }
      numberAllowedSpeculativeTasks
          = (int) Math.max(numberAllowedSpeculativeTasks,
                           PROPORTION_RUNNING_TASKS_SPECULATABLE * numberRunningTasks);

      // If we found a speculation target, fire it off
      if (bestTaskID != null
          && numberAllowedSpeculativeTasks > numberSpeculationsAlready) {
        addSpeculativeAttempt(bestTaskID);
        ++successes;
      }
    }

    return successes;
  }

  private int computeSpeculations() {
    // We'll try to issue one map and one reduce speculation per job per run
    return maybeScheduleAMapSpeculation() + maybeScheduleAReduceSpeculation();
  }
}
