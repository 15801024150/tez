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
package org.apache.tez.engine.common.shuffle.impl;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.engine.common.TezEngineUtils;
import org.apache.tez.engine.newapi.Event;
import org.apache.tez.engine.newapi.TezInputContext;
import org.apache.tez.engine.newapi.events.InputReadErrorEvent;

import com.google.common.collect.Lists;

class ShuffleScheduler {
  static ThreadLocal<Long> shuffleStart = new ThreadLocal<Long>() {
    protected Long initialValue() {
      return 0L;
    }
  };

  private static final Log LOG = LogFactory.getLog(ShuffleScheduler.class);
  private static final int MAX_MAPS_AT_ONCE = 20;
  private static final long INITIAL_PENALTY = 10000;
  private static final float PENALTY_GROWTH_RATE = 1.3f;
  
  // TODO NEWTEZ May need to be a string if attempting to fetch from multiple inputs.
  private final Map<Integer, MutableInt> finishedMaps;
  private final int numInputs;
  private int remainingMaps;
  private Map<TaskAttemptIdentifier, MapHost> mapLocations = new HashMap<TaskAttemptIdentifier, MapHost>();
  //TODO NEWTEZ Clean this and other maps at some point
  private ConcurrentMap<String, TaskAttemptIdentifier> pathToIdentifierMap = new ConcurrentHashMap<String, TaskAttemptIdentifier>(); 
  private Set<MapHost> pendingHosts = new HashSet<MapHost>();
  private Set<TaskAttemptIdentifier> obsoleteMaps = new HashSet<TaskAttemptIdentifier>();
  
  private final Random random = new Random(System.currentTimeMillis());
  private final DelayQueue<Penalty> penalties = new DelayQueue<Penalty>();
  private final Referee referee = new Referee();
  private final Map<TaskAttemptIdentifier, IntWritable> failureCounts =
    new HashMap<TaskAttemptIdentifier,IntWritable>(); 
  private final Map<String,IntWritable> hostFailures = 
    new HashMap<String,IntWritable>();
  private final TezInputContext inputContext;
  private final Shuffle shuffle;
  private final int abortFailureLimit;
  private final TezCounter shuffledMapsCounter;
  private final TezCounter reduceShuffleBytes;
  private final TezCounter failedShuffleCounter;
  
  private final long startTime;
  private long lastProgressTime;
  
  private int maxMapRuntime = 0;
  private int maxFailedUniqueFetches = 5;
  private int maxFetchFailuresBeforeReporting;
  
  private long totalBytesShuffledTillNow = 0;
  private DecimalFormat  mbpsFormat = new DecimalFormat("0.00");

  private boolean reportReadErrorImmediately = true;
  
  public ShuffleScheduler(TezInputContext inputContext,
                          Configuration conf,
                          int tasksInDegree,
                          Shuffle shuffle,
                          TezCounter shuffledMapsCounter,
                          TezCounter reduceShuffleBytes,
                          TezCounter failedShuffleCounter) {
    this.inputContext = inputContext;
    this.numInputs = tasksInDegree;
    abortFailureLimit = Math.max(30, tasksInDegree / 10);
    remainingMaps = tasksInDegree;
  //TODO NEWTEZ May need to be a string or a more usable construct if attempting to fetch from multiple inputs. Define a taskId / taskAttemptId pair
    finishedMaps = new HashMap<Integer, MutableInt>(remainingMaps);
    this.shuffle = shuffle;
    this.shuffledMapsCounter = shuffledMapsCounter;
    this.reduceShuffleBytes = reduceShuffleBytes;
    this.failedShuffleCounter = failedShuffleCounter;
    this.startTime = System.currentTimeMillis();
    this.lastProgressTime = startTime;
    referee.start();
    this.maxFailedUniqueFetches = Math.min(tasksInDegree,
        this.maxFailedUniqueFetches);
    this.maxFetchFailuresBeforeReporting = 
        conf.getInt(
            TezJobConfig.TEZ_ENGINE_SHUFFLE_FETCH_FAILURES, 
            TezJobConfig.DEFAULT_TEZ_ENGINE_SHUFFLE_FETCH_FAILURES_LIMIT);
    this.reportReadErrorImmediately = 
        conf.getBoolean(
            TezJobConfig.TEZ_ENGINE_SHUFFLE_NOTIFY_READERROR, 
            TezJobConfig.DEFAULT_TEZ_ENGINE_SHUFFLE_NOTIFY_READERROR);
  }

  public synchronized void copySucceeded(TaskAttemptIdentifier srcAttemptIdentifier, 
                                         MapHost host,
                                         long bytes,
                                         long milis,
                                         MapOutput output
                                         ) throws IOException {
    String taskIdentifier = TezEngineUtils.getTaskAttemptIdentifier(srcAttemptIdentifier.getTaskIndex(), srcAttemptIdentifier.getAttemptNumber());
    failureCounts.remove(taskIdentifier);
    hostFailures.remove(host.getHostName());
    
    if (!isFinishedTaskTrue(srcAttemptIdentifier.getTaskIndex())) {
      output.commit();
      if(incrementTaskCopyAndCheckCompletion(srcAttemptIdentifier.getTaskIndex())) {
        shuffledMapsCounter.increment(1);
        if (--remainingMaps == 0) {
          notifyAll();
        }
      }

      // update the status
      lastProgressTime = System.currentTimeMillis();
      totalBytesShuffledTillNow += bytes;
      logProgress();
      reduceShuffleBytes.increment(bytes);
      if (LOG.isDebugEnabled()) {
        LOG.debug("src task: "
            + TezEngineUtils.getTaskAttemptIdentifier(
                inputContext.getSourceVertexName(), srcAttemptIdentifier.getTaskIndex(),
                srcAttemptIdentifier.getAttemptNumber()) + " done");
      }
    }
  }

  private void logProgress() {
    float mbs = (float) totalBytesShuffledTillNow / (1024 * 1024);
    int mapsDone = numInputs - remainingMaps;
    long secsSinceStart = (System.currentTimeMillis() - startTime) / 1000 + 1;

    float transferRate = mbs / secsSinceStart;
    LOG.info("copy(" + mapsDone + " of " + numInputs + " at "
        + mbpsFormat.format(transferRate) + " MB/s)");
  }

  public synchronized void copyFailed(TaskAttemptIdentifier srcAttempt,
                                      MapHost host,
                                      boolean readError) {
    host.penalize();
    int failures = 1;
    if (failureCounts.containsKey(srcAttempt)) {
      IntWritable x = failureCounts.get(srcAttempt);
      x.set(x.get() + 1);
      failures = x.get();
    } else {
      failureCounts.put(srcAttempt, new IntWritable(1));      
    }
    String hostname = host.getHostName();
    if (hostFailures.containsKey(hostname)) {
      IntWritable x = hostFailures.get(hostname);
      x.set(x.get() + 1);
    } else {
      hostFailures.put(hostname, new IntWritable(1));
    }
    if (failures >= abortFailureLimit) {
      try {
        throw new IOException(failures
            + " failures downloading "
            + TezEngineUtils.getTaskAttemptIdentifier(
                inputContext.getSourceVertexName(), srcAttempt.getTaskIndex(),
                srcAttempt.getAttemptNumber()));
      } catch (IOException ie) {
        shuffle.reportException(ie);
      }
    }
    
    checkAndInformJobTracker(failures, srcAttempt, readError);

    checkReducerHealth();
    
    long delay = (long) (INITIAL_PENALTY *
        Math.pow(PENALTY_GROWTH_RATE, failures));
    
    penalties.add(new Penalty(host, delay));
    
    failedShuffleCounter.increment(1);
  }
  
  // Notify the JobTracker  
  // after every read error, if 'reportReadErrorImmediately' is true or
  // after every 'maxFetchFailuresBeforeReporting' failures
  private void checkAndInformJobTracker(
      int failures, TaskAttemptIdentifier srcAttempt, boolean readError) {
    if ((reportReadErrorImmediately && readError)
        || ((failures % maxFetchFailuresBeforeReporting) == 0)) {
      LOG.info("Reporting fetch failure for "
          + TezEngineUtils.getTaskAttemptIdentifier(
              inputContext.getSourceVertexName(), srcAttempt.getTaskIndex(),
              srcAttempt.getAttemptNumber()) + " to jobtracker.");

      List<Event> failedEvents = Lists.newArrayListWithCapacity(1);
      failedEvents.add(new InputReadErrorEvent("Fetch failure for "
          + TezEngineUtils.getTaskAttemptIdentifier(
              inputContext.getSourceVertexName(), srcAttempt.getTaskIndex(),
              srcAttempt.getAttemptNumber()) + " to jobtracker.", srcAttempt
          .getTaskIndex(), srcAttempt.getAttemptNumber()));

      inputContext.sendEvents(failedEvents);      
      //status.addFailedDependency(mapId);
    }
  }
    
  private void checkReducerHealth() {
    final float MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT = 0.5f;
    final float MIN_REQUIRED_PROGRESS_PERCENT = 0.5f;
    final float MAX_ALLOWED_STALL_TIME_PERCENT = 0.5f;

    long totalFailures = failedShuffleCounter.getValue();
    int doneMaps = numInputs - remainingMaps;
    
    boolean reducerHealthy =
      (((float)totalFailures / (totalFailures + doneMaps))
          < MAX_ALLOWED_FAILED_FETCH_ATTEMPT_PERCENT);
    
    // check if the reducer has progressed enough
    boolean reducerProgressedEnough =
      (((float)doneMaps / numInputs)
          >= MIN_REQUIRED_PROGRESS_PERCENT);

    // check if the reducer is stalled for a long time
    // duration for which the reducer is stalled
    int stallDuration =
      (int)(System.currentTimeMillis() - lastProgressTime);
    
    // duration for which the reducer ran with progress
    int shuffleProgressDuration =
      (int)(lastProgressTime - startTime);

    // min time the reducer should run without getting killed
    int minShuffleRunDuration =
      (shuffleProgressDuration > maxMapRuntime)
      ? shuffleProgressDuration
          : maxMapRuntime;
    
    boolean reducerStalled =
      (((float)stallDuration / minShuffleRunDuration)
          >= MAX_ALLOWED_STALL_TIME_PERCENT);

    // kill if not healthy and has insufficient progress
    if ((failureCounts.size() >= maxFailedUniqueFetches ||
        failureCounts.size() == (numInputs - doneMaps))
        && !reducerHealthy
        && (!reducerProgressedEnough || reducerStalled)) {
      LOG.fatal("Shuffle failed with too many fetch failures " +
      "and insufficient progress!");
      String errorMsg = "Exceeded MAX_FAILED_UNIQUE_FETCHES; bailing-out.";
      shuffle.reportException(new IOException(errorMsg));
    }

  }
  
  public synchronized void tipFailed(int srcTaskIndex) {
    if (!isFinishedTaskTrue(srcTaskIndex)) {
      setFinishedTaskTrue(srcTaskIndex);
      if (--remainingMaps == 0) {
        notifyAll();
      }
      logProgress();
    }
  }
  
  public synchronized void addKnownMapOutput(String hostName,
                                             int partitionId,
                                             String hostUrl,
                                             TaskAttemptIdentifier srcAttempt) {
    String identifier = MapHost.createIdentifier(hostName, partitionId);
    MapHost host = mapLocations.get(identifier);
    if (host == null) {
      host = new MapHost(partitionId, hostName, hostUrl);
      assert identifier.equals(host.getIdentifier());
      mapLocations.put(srcAttempt, host);
    }
    host.addKnownMap(srcAttempt);
    pathToIdentifierMap.put(srcAttempt.getPathComponent(), srcAttempt);

    // Mark the host as pending
    if (host.getState() == MapHost.State.PENDING) {
      pendingHosts.add(host);
      notifyAll();
    }
  }
  
  public synchronized void obsoleteMapOutput(TaskAttemptIdentifier srcAttempt) {
    // The incoming srcAttempt does not contain a path component.
    obsoleteMaps.add(srcAttempt);
  }
  
  public synchronized void putBackKnownMapOutput(MapHost host,
                                                 TaskAttemptIdentifier srcAttempt) {
    host.addKnownMap(srcAttempt);
  }

  public synchronized MapHost getHost() throws InterruptedException {
      while(pendingHosts.isEmpty()) {
        wait();
      }
      
      MapHost host = null;
      Iterator<MapHost> iter = pendingHosts.iterator();
      int numToPick = random.nextInt(pendingHosts.size());
      for (int i=0; i <= numToPick; ++i) {
        host = iter.next();
      }
      
      pendingHosts.remove(host);     
      host.markBusy();
      
      LOG.info("Assigning " + host + " with " + host.getNumKnownMapOutputs() + 
               " to " + Thread.currentThread().getName());
      shuffleStart.set(System.currentTimeMillis());
      
      return host;
  }
  
  public TaskAttemptIdentifier getIdentifierForPathComponent(String pathComponent) {
    return pathToIdentifierMap.get(pathComponent);
  }
  
  public synchronized List<TaskAttemptIdentifier> getMapsForHost(MapHost host) {
    List<TaskAttemptIdentifier> list = host.getAndClearKnownMaps();
    Iterator<TaskAttemptIdentifier> itr = list.iterator();
    List<TaskAttemptIdentifier> result = new ArrayList<TaskAttemptIdentifier>();
    int includedMaps = 0;
    int totalSize = list.size();
    // find the maps that we still need, up to the limit
    while (itr.hasNext()) {
      TaskAttemptIdentifier id = itr.next();
      if (!obsoleteMaps.contains(id) && !isFinishedTaskTrue(id.getTaskIndex())) {
        result.add(id);
        if (++includedMaps >= MAX_MAPS_AT_ONCE) {
          break;
        }
      }
    }
    // put back the maps left after the limit
    while (itr.hasNext()) {
      TaskAttemptIdentifier id = itr.next();
      if (!obsoleteMaps.contains(id) && !isFinishedTaskTrue(id.getTaskIndex())) {
        host.addKnownMap(id);
      }
    }
    LOG.info("assigned " + includedMaps + " of " + totalSize + " to " +
             host + " to " + Thread.currentThread().getName());
    return result;
  }

  public synchronized void freeHost(MapHost host) {
    if (host.getState() != MapHost.State.PENALIZED) {
      if (host.markAvailable() == MapHost.State.PENDING) {
        pendingHosts.add(host);
        notifyAll();
      }
    }
    LOG.info(host + " freed by " + Thread.currentThread().getName() + " in " + 
             (System.currentTimeMillis()-shuffleStart.get()) + "s");
  }
    
  public synchronized void resetKnownMaps() {
    mapLocations.clear();
    obsoleteMaps.clear();
    pendingHosts.clear();
    pathToIdentifierMap.clear();
  }

  /**
   * Utility method to check if the Shuffle data fetch is complete.
   * @return
   */
  public synchronized boolean isDone() {
    return remainingMaps == 0;
  }

  /**
   * Wait until the shuffle finishes or until the timeout.
   * @param millis maximum wait time
   * @return true if the shuffle is done
   * @throws InterruptedException
   */
  public synchronized boolean waitUntilDone(int millis
                                            ) throws InterruptedException {
    if (remainingMaps > 0) {
      wait(millis);
      return remainingMaps == 0;
    }
    return true;
  }
  
  /**
   * A structure that records the penalty for a host.
   */
  private static class Penalty implements Delayed {
    MapHost host;
    private long endTime;
    
    Penalty(MapHost host, long delay) {
      this.host = host;
      this.endTime = System.currentTimeMillis() + delay;
    }

    public long getDelay(TimeUnit unit) {
      long remainingTime = endTime - System.currentTimeMillis();
      return unit.convert(remainingTime, TimeUnit.MILLISECONDS);
    }

    public int compareTo(Delayed o) {
      long other = ((Penalty) o).endTime;
      return endTime == other ? 0 : (endTime < other ? -1 : 1);
    }
    
  }
  
  /**
   * A thread that takes hosts off of the penalty list when the timer expires.
   */
  private class Referee extends Thread {
    public Referee() {
      setName("ShufflePenaltyReferee");
      setDaemon(true);
    }

    public void run() {
      try {
        while (true) {
          // take the first host that has an expired penalty
          MapHost host = penalties.take().host;
          synchronized (ShuffleScheduler.this) {
            if (host.markAvailable() == MapHost.State.PENDING) {
              pendingHosts.add(host);
              ShuffleScheduler.this.notifyAll();
            }
          }
        }
      } catch (InterruptedException ie) {
        return;
      } catch (Throwable t) {
        shuffle.reportException(t);
      }
    }
  }
  
  public void close() throws InterruptedException {
    referee.interrupt();
    referee.join();
  }

  public synchronized void informMaxMapRunTime(int duration) {
    if (duration > maxMapRuntime) {
      maxMapRuntime = duration;
    }
  }
  
  void setFinishedTaskTrue(int srcTaskIndex) {
    synchronized(finishedMaps) {
      finishedMaps.put(srcTaskIndex, new MutableInt(shuffle.getReduceRange()));
    }
  }
  
  boolean incrementTaskCopyAndCheckCompletion(int srcTaskIndex) {
    synchronized(finishedMaps) {
      MutableInt result = finishedMaps.get(srcTaskIndex);
      if(result == null) {
        result = new MutableInt(0);
        finishedMaps.put(srcTaskIndex, result);
      }
      result.increment();
      return isFinishedTaskTrue(srcTaskIndex);
    }
  }
  
  boolean isFinishedTaskTrue(int srcTaskIndex) {
    synchronized (finishedMaps) {
      MutableInt result = finishedMaps.get(srcTaskIndex);
      if(result == null) {
        return false;
      }
      if (result.intValue() == shuffle.getReduceRange()) {
        return true;
      }
      return false;      
    }
  }
}
