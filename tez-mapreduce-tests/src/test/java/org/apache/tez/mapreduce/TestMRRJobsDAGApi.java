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

package org.apache.tez.mapreduce;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Apps;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.tez.client.AMConfiguration;
import org.apache.tez.client.TezClient;
import org.apache.tez.client.TezSession;
import org.apache.tez.client.TezSessionConfiguration;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.OutputDescriptor;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.EdgeProperty.ConnectionPattern;
import org.apache.tez.dag.api.EdgeProperty.SourceType;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.dag.api.client.DAGStatus.State;
import org.apache.tez.engine.lib.input.ShuffledMergedInput;
import org.apache.tez.engine.lib.output.OnFileSortedOutput;
import org.apache.tez.mapreduce.examples.MRRSleepJob;
import org.apache.tez.mapreduce.examples.MRRSleepJob.ISleepReducer;
import org.apache.tez.mapreduce.examples.MRRSleepJob.MRRSleepJobPartitioner;
import org.apache.tez.mapreduce.examples.MRRSleepJob.SleepInputFormat;
import org.apache.tez.mapreduce.examples.MRRSleepJob.SleepMapper;
import org.apache.tez.mapreduce.examples.MRRSleepJob.SleepReducer;
import org.apache.tez.mapreduce.hadoop.InputSplitInfo;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.hadoop.MultiStageMRConfToTezTranslator;
import org.apache.tez.mapreduce.processor.map.MapProcessor;
import org.apache.tez.mapreduce.processor.reduce.ReduceProcessor;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestMRRJobsDAGApi {

  private static final Log LOG = LogFactory.getLog(TestMRRJobsDAGApi.class);

  protected static MiniMRRTezCluster mrrTezCluster;
  protected static MiniDFSCluster dfsCluster;

  private static Configuration conf = new Configuration();
  private static FileSystem localFs;
  private static FileSystem remoteFs;
  static {
    try {
      localFs = FileSystem.getLocal(conf);
    } catch (IOException io) {
      throw new RuntimeException("problem getting local fs", io);
    }
  }

  private static String TEST_ROOT_DIR = "target" + Path.SEPARATOR
      + TestMRRJobsDAGApi.class.getName() + "-tmpDir";

  private static String TEST_ROOT_DIR_HDFS = "/tmp" + Path.SEPARATOR
      + TestMRRJobsDAGApi.class.getSimpleName();

  private static Path TEST_ROOT_DIR_PATH = localFs.makeQualified(new Path(
      TEST_ROOT_DIR));
  static Path APP_JAR = new Path(TEST_ROOT_DIR_PATH, "MRAppJar.jar");
  static Path YARN_SITE_XML = new Path(TEST_ROOT_DIR_PATH, "yarn-site.xml");

  static Path APP_JAR_HDFS = new Path(TEST_ROOT_DIR_HDFS, "MRAppJar.jar");
  static Path YARN_SITE_XML_HDFS = new Path(TEST_ROOT_DIR_HDFS, "yarn-site.xml");

  @BeforeClass
  public static void setup() throws IOException {
    try {
      conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, TEST_ROOT_DIR);
      dfsCluster = new MiniDFSCluster.Builder(conf).numDataNodes(2)
          .format(true).racks(null).build();
      remoteFs = dfsCluster.getFileSystem();
      APP_JAR_HDFS = remoteFs.makeQualified(APP_JAR_HDFS);
      YARN_SITE_XML_HDFS = remoteFs.makeQualified(YARN_SITE_XML_HDFS);
    } catch (IOException io) {
      throw new RuntimeException("problem starting mini dfs cluster", io);
    }

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
          + " not found. Not running test.");
      return;
    }

    if (mrrTezCluster == null) {
      mrrTezCluster = new MiniMRRTezCluster(TestMRRJobsDAGApi.class.getName(),
          1, 1, 1);
      Configuration conf = new Configuration();
      conf.set("fs.defaultFS", remoteFs.getUri().toString()); // use HDFS
      conf.set(MRJobConfig.MR_AM_STAGING_DIR, "/apps_staging_dir");
      conf.setLong(YarnConfiguration.DEBUG_NM_DELETE_DELAY_SEC, 0l);
      mrrTezCluster.init(conf);
      mrrTezCluster.start();
    }

    LOG.info("APP_JAR: " + APP_JAR);
    LOG.info("APP_JAR_HDFS: " + APP_JAR_HDFS);
    LOG.info("YARN_SITE_XML: " + YARN_SITE_XML);
    LOG.info("YARN_SITE_XML_HDFS: " + YARN_SITE_XML_HDFS);

    localFs.copyFromLocalFile(new Path(MiniMRRTezCluster.APPJAR), APP_JAR);
    localFs.setPermission(APP_JAR, new FsPermission("700"));
    localFs.copyFromLocalFile(mrrTezCluster.getConfigFilePath(), YARN_SITE_XML);

    remoteFs
        .copyFromLocalFile(new Path(MiniMRRTezCluster.APPJAR), APP_JAR_HDFS);
    remoteFs.copyFromLocalFile(mrrTezCluster.getConfigFilePath(),
        YARN_SITE_XML_HDFS);
  }

  @AfterClass
  public static void tearDown() {
    if (mrrTezCluster != null) {
      mrrTezCluster.stop();
      mrrTezCluster = null;
    }
    if (dfsCluster != null) {
      dfsCluster.shutdown();
      dfsCluster = null;
    }
    // TODO Add cleanup code.
  }

  // Submits a simple 5 stage sleep job using the DAG submit API instead of job
  // client.
  @Test(timeout = 60000)
  public void testMRRSleepJobDagSubmit() throws IOException,
  InterruptedException, TezException, ClassNotFoundException, YarnException {
    State finalState = testMRRSleepJobDagSubmitCore(false, false, false);

    Assert.assertEquals(DAGStatus.State.SUCCEEDED, finalState);
    // TODO Add additional checks for tracking URL etc. - once it's exposed by
    // the DAG API.
  }

  // Submits a simple 5 stage sleep job using the DAG submit API. Then kills it.
  @Test(timeout = 60000)
  public void testMRRSleepJobDagSubmitAndKill() throws IOException,
  InterruptedException, TezException, ClassNotFoundException, YarnException {
    State finalState = testMRRSleepJobDagSubmitCore(false, true, false);

    Assert.assertEquals(DAGStatus.State.KILLED, finalState);
    // TODO Add additional checks for tracking URL etc. - once it's exposed by
    // the DAG API.
  }

  // Submits a DAG to AM via RPC after AM has started
  @Test(timeout = 60000)
  public void testMRRSleepJobPlanViaRPC() throws IOException,
  InterruptedException, TezException, ClassNotFoundException, YarnException {
    State finalState = testMRRSleepJobDagSubmitCore(true, false, false);

    Assert.assertEquals(DAGStatus.State.SUCCEEDED, finalState);
  }

  // Submits a simple 5 stage sleep job using tez session. Then kills it.
  @Test(timeout = 60000)
  public void testMRRSleepJobDagSubmitAndKillViaRPC() throws IOException,
  InterruptedException, TezException, ClassNotFoundException, YarnException {
    State finalState = testMRRSleepJobDagSubmitCore(true, true, false);

    Assert.assertEquals(DAGStatus.State.KILLED, finalState);
    // TODO Add additional checks for tracking URL etc. - once it's exposed by
    // the DAG API.
  }

  // Create and close a tez session without submitting a job
  @Test(timeout = 60000)
  public void testTezSessionShutdown() throws IOException,
  InterruptedException, TezException, ClassNotFoundException, YarnException {
    testMRRSleepJobDagSubmitCore(true, false, true);
  }

  public State testMRRSleepJobDagSubmitCore(
      boolean dagViaRPC,
      boolean killDagWhileRunning,
      boolean closeSessionBeforeSubmit) throws IOException,
      InterruptedException, TezException, ClassNotFoundException, YarnException {
    LOG.info("\n\n\nStarting testMRRSleepJobDagSubmit().");

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
          + " not found. Not running test.");
      return State.ERROR;
    }

    JobConf stage1Conf = new JobConf(mrrTezCluster.getConfig());
    JobConf stage2Conf = new JobConf(mrrTezCluster.getConfig());
    JobConf stage3Conf = new JobConf(mrrTezCluster.getConfig());

    stage1Conf.setLong(MRRSleepJob.MAP_SLEEP_TIME, 1);
    stage1Conf.setInt(MRRSleepJob.MAP_SLEEP_COUNT, 1);
    stage1Conf.setInt(MRJobConfig.NUM_MAPS, 1);
    stage1Conf.set(MRJobConfig.MAP_CLASS_ATTR, SleepMapper.class.getName());
    stage1Conf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS,
        IntWritable.class.getName());
    stage1Conf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS,
        IntWritable.class.getName());
    stage1Conf.set(MRJobConfig.INPUT_FORMAT_CLASS_ATTR,
        SleepInputFormat.class.getName());
    stage1Conf.set(MRJobConfig.PARTITIONER_CLASS_ATTR,
        MRRSleepJobPartitioner.class.getName());

    stage2Conf.setLong(MRRSleepJob.REDUCE_SLEEP_TIME, 1);
    stage2Conf.setInt(MRRSleepJob.REDUCE_SLEEP_COUNT, 1);
    stage2Conf.setInt(MRJobConfig.NUM_REDUCES, 1);
    stage2Conf
        .set(MRJobConfig.REDUCE_CLASS_ATTR, ISleepReducer.class.getName());
    stage2Conf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS,
        IntWritable.class.getName());
    stage2Conf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS,
        IntWritable.class.getName());
    stage2Conf.set(MRJobConfig.PARTITIONER_CLASS_ATTR,
        MRRSleepJobPartitioner.class.getName());

    JobConf stage22Conf = new JobConf(stage2Conf);
    stage22Conf.setInt(MRJobConfig.NUM_REDUCES, 2);

    stage3Conf.setLong(MRRSleepJob.REDUCE_SLEEP_TIME, 1);
    stage3Conf.setInt(MRRSleepJob.REDUCE_SLEEP_COUNT, 1);
    stage3Conf.setInt(MRJobConfig.NUM_REDUCES, 1);
    stage3Conf.set(MRJobConfig.REDUCE_CLASS_ATTR, SleepReducer.class.getName());
    stage3Conf.set(MRJobConfig.MAP_OUTPUT_KEY_CLASS,
        IntWritable.class.getName());
    stage3Conf.set(MRJobConfig.MAP_OUTPUT_VALUE_CLASS,
        IntWritable.class.getName());
    stage3Conf.set(MRJobConfig.OUTPUT_FORMAT_CLASS_ATTR,
        NullOutputFormat.class.getName());

    MultiStageMRConfToTezTranslator.translateVertexConfToTez(stage1Conf, null);
    MultiStageMRConfToTezTranslator.translateVertexConfToTez(stage2Conf,
        stage1Conf);
    MultiStageMRConfToTezTranslator.translateVertexConfToTez(stage22Conf,
        stage1Conf);
    MultiStageMRConfToTezTranslator.translateVertexConfToTez(stage3Conf,
        stage2Conf); // this also works stage22 as it sets up keys etc

    MRHelpers.doJobClientMagic(stage1Conf);
    MRHelpers.doJobClientMagic(stage2Conf);
    MRHelpers.doJobClientMagic(stage22Conf);
    MRHelpers.doJobClientMagic(stage3Conf);

    Path remoteStagingDir = remoteFs.makeQualified(new Path("/tmp", String
        .valueOf(new Random().nextInt(100000))));
    InputSplitInfo inputSplitInfo = MRHelpers.generateInputSplits(stage1Conf,
        remoteStagingDir);
    InputSplitInfo inputSplitInfo1 = MRHelpers.generateInputSplits(stage1Conf,
        remoteStagingDir);

    DAG dag = new DAG("testMRRSleepJobDagSubmit");
    Vertex stage1Vertex = new Vertex("map", new ProcessorDescriptor(
        MapProcessor.class.getName()).setUserPayload(
        MRHelpers.createUserPayloadFromConf(stage1Conf)),
        inputSplitInfo.getNumTasks(), Resource.newInstance(256, 1));
    Vertex stage2Vertex = new Vertex("ireduce", new ProcessorDescriptor(
        ReduceProcessor.class.getName()).setUserPayload(
        MRHelpers.createUserPayloadFromConf(stage2Conf)),
        1, Resource.newInstance(256, 1));
    Vertex stage11Vertex = new Vertex("map1", new ProcessorDescriptor(
        MapProcessor.class.getName()).setUserPayload(
        MRHelpers.createUserPayloadFromConf(stage1Conf)),
        inputSplitInfo1.getNumTasks(),  Resource.newInstance(256, 1));
    Vertex stage22Vertex = new Vertex("ireduce1", new ProcessorDescriptor(
        ReduceProcessor.class.getName()).setUserPayload(
        MRHelpers.createUserPayloadFromConf(stage22Conf)),
        2, Resource.newInstance(256, 1));
    Vertex stage3Vertex = new Vertex("reduce", new ProcessorDescriptor(
        ReduceProcessor.class.getName()).setUserPayload(
        MRHelpers.createUserPayloadFromConf(stage3Conf)),
        1, Resource.newInstance(256, 1));

    LocalResource appJarLr = createLocalResource(remoteFs,
        remoteFs.makeQualified(APP_JAR_HDFS), LocalResourceType.FILE,
        LocalResourceVisibility.APPLICATION);
    LocalResource yarnSiteLr = createLocalResource(remoteFs,
        remoteFs.makeQualified(YARN_SITE_XML_HDFS), LocalResourceType.FILE,
        LocalResourceVisibility.APPLICATION);

    Map<String, LocalResource> commonLocalResources = new HashMap<String, LocalResource>();
    commonLocalResources.put(APP_JAR.getName(), appJarLr);

    Map<String, String> commonEnv = new HashMap<String, String>();
    // TODO Use utility method post TEZ-205.
    Apps.addToEnvironment(commonEnv, Environment.CLASSPATH.name(), ".");
    Apps.addToEnvironment(commonEnv, Environment.CLASSPATH.name(),
        System.getProperty("java.class.path"));

    // TODO Use utility method post TEZ-205.
    Map<String, LocalResource> stage1LocalResources = new HashMap<String, LocalResource>();
    stage1LocalResources.put(
        inputSplitInfo.getSplitsFile().getName(),
        createLocalResource(remoteFs, inputSplitInfo.getSplitsFile(),
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
    stage1LocalResources.put(
        inputSplitInfo.getSplitsMetaInfoFile().getName(),
        createLocalResource(remoteFs, inputSplitInfo.getSplitsMetaInfoFile(),
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
    stage1LocalResources.putAll(commonLocalResources);

    Map<String, LocalResource> stage11LocalResources = new HashMap<String, LocalResource>();
    stage11LocalResources.put(
        inputSplitInfo1.getSplitsFile().getName(),
        createLocalResource(remoteFs, inputSplitInfo1.getSplitsFile(),
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
    stage11LocalResources.put(
        inputSplitInfo1.getSplitsMetaInfoFile().getName(),
        createLocalResource(remoteFs, inputSplitInfo1.getSplitsMetaInfoFile(),
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
    stage11LocalResources.putAll(commonLocalResources);

    stage1Vertex.setJavaOpts(MRHelpers.getMapJavaOpts(stage1Conf));
    stage1Vertex.setTaskLocationsHint(inputSplitInfo.getTaskLocationHints());
    stage1Vertex.setTaskLocalResources(stage1LocalResources);
    stage1Vertex.setTaskEnvironment(commonEnv);

    stage11Vertex.setJavaOpts(MRHelpers.getMapJavaOpts(stage1Conf));
    stage11Vertex.setTaskLocationsHint(inputSplitInfo1.getTaskLocationHints());
    stage11Vertex.setTaskLocalResources(stage11LocalResources);
    stage11Vertex.setTaskEnvironment(commonEnv);
    // TODO env, resources

    stage2Vertex.setJavaOpts(MRHelpers.getReduceJavaOpts(stage2Conf));
    stage2Vertex.setTaskLocalResources(commonLocalResources);
    stage2Vertex.setTaskEnvironment(commonEnv);

    stage22Vertex.setJavaOpts(MRHelpers.getReduceJavaOpts(stage22Conf));
    stage22Vertex.setTaskLocalResources(commonLocalResources);
    stage22Vertex.setTaskEnvironment(commonEnv);

    stage3Vertex.setJavaOpts(MRHelpers.getReduceJavaOpts(stage3Conf));
    stage3Vertex.setTaskLocalResources(commonLocalResources);
    stage3Vertex.setTaskEnvironment(commonEnv);

    dag.addVertex(stage1Vertex);
    dag.addVertex(stage11Vertex);
    dag.addVertex(stage2Vertex);
    dag.addVertex(stage22Vertex);
    dag.addVertex(stage3Vertex);

    Edge edge1 = new Edge(stage1Vertex, stage2Vertex, new EdgeProperty(
        ConnectionPattern.BIPARTITE, SourceType.STABLE, new OutputDescriptor(
        OnFileSortedOutput.class.getName()), new InputDescriptor(
                ShuffledMergedInput.class.getName())));
    Edge edge11 = new Edge(stage11Vertex, stage22Vertex, new EdgeProperty(
        ConnectionPattern.BIPARTITE, SourceType.STABLE, new OutputDescriptor(
        OnFileSortedOutput.class.getName()), new InputDescriptor(
                ShuffledMergedInput.class.getName())));
    Edge edge2 = new Edge(stage2Vertex, stage3Vertex, new EdgeProperty(
        ConnectionPattern.BIPARTITE, SourceType.STABLE, new OutputDescriptor(
        OnFileSortedOutput.class.getName()), new InputDescriptor(
                ShuffledMergedInput.class.getName())));
    Edge edge3 = new Edge(stage22Vertex, stage3Vertex, new EdgeProperty(
        ConnectionPattern.BIPARTITE, SourceType.STABLE, new OutputDescriptor(
        OnFileSortedOutput.class.getName()), new InputDescriptor(
                ShuffledMergedInput.class.getName())));

    dag.addEdge(edge1);
    dag.addEdge(edge11);
    dag.addEdge(edge2);
    dag.addEdge(edge3);

    Map<String, LocalResource> amLocalResources =
        new HashMap<String, LocalResource>();
    amLocalResources.put("yarn-site.xml", yarnSiteLr);
    amLocalResources.putAll(commonLocalResources);

    TezConfiguration tezConf = new TezConfiguration(
            mrrTezCluster.getConfig());
    tezConf.set(TezConfiguration.TEZ_AM_STAGING_DIR,
        remoteStagingDir.toString());

    TezClient tezClient = new TezClient(tezConf);
    DAGClient dagClient = null;
    TezSession tezSession = null;
    TezSessionConfiguration tezSessionConfig;
    AMConfiguration amConfig = new AMConfiguration(
        "default", commonEnv, amLocalResources,
        tezConf, null);
    if(!dagViaRPC) {
      // TODO Use utility method post TEZ-205 to figure out AM arguments etc.
      dagClient = tezClient.submitDAGApplication(dag, amConfig);
    } else {
      tezSessionConfig = new TezSessionConfiguration(amConfig, tezConf);
      tezSession = new TezSession("testsession", tezSessionConfig);
      tezSession.start();
    }

    if (dagViaRPC && closeSessionBeforeSubmit) {
      YarnClient yarnClient = YarnClient.createYarnClient();
      yarnClient.init(mrrTezCluster.getConfig());
      yarnClient.start();
      boolean sentKillSession = false;
      while(true) {
        Thread.sleep(500l);
        ApplicationReport appReport =
            yarnClient.getApplicationReport(tezSession.getApplicationId());
        if (appReport == null) {
          continue;
        }
        YarnApplicationState appState = appReport.getYarnApplicationState();
        if (!sentKillSession) {
          if (appState == YarnApplicationState.RUNNING) {
            tezSession.stop();
            sentKillSession = true;
          }
        } else {
          if (appState == YarnApplicationState.FINISHED
              || appState == YarnApplicationState.KILLED
              || appState == YarnApplicationState.FAILED) {
            LOG.info("Application completed after sending session shutdown"
                + ", yarnApplicationState=" + appState
                + ", finalAppStatus=" + appReport.getFinalApplicationStatus());
            Assert.assertEquals(YarnApplicationState.FINISHED,
                appState);
            Assert.assertEquals(FinalApplicationStatus.UNDEFINED,
                appReport.getFinalApplicationStatus());
            break;
          }
        }
      }
      yarnClient.stop();
      return null;
    }

    if(dagViaRPC) {
      LOG.info("Submitting dag to tez session with appId="
          + tezSession.getApplicationId());
      dagClient = tezSession.submitDAG(dag);
    }
    DAGStatus dagStatus = dagClient.getDAGStatus();
    while (!dagStatus.isCompleted()) {
      LOG.info("Waiting for job to complete. Sleeping for 500ms."
          + " Current state: " + dagStatus.getState());
      Thread.sleep(500l);
      if(killDagWhileRunning
          && dagStatus.getState() == DAGStatus.State.RUNNING) {
        LOG.info("Killing running dag/session");
        if (dagViaRPC) {
          tezSession.stop();
        } else {
          dagClient.tryKillDAG();
        }
      }
      dagStatus = dagClient.getDAGStatus();
    }
    return dagStatus.getState();
  }

  private static LocalResource createLocalResource(FileSystem fc, Path file,
      LocalResourceType type, LocalResourceVisibility visibility)
      throws IOException {
    FileStatus fstat = fc.getFileStatus(file);
    URL resourceURL = ConverterUtils.getYarnUrlFromPath(fc.resolvePath(fstat
        .getPath()));
    long resourceSize = fstat.getLen();
    long resourceModificationTime = fstat.getModificationTime();

    return LocalResource.newInstance(resourceURL, type, visibility,
        resourceSize, resourceModificationTime);
  }
}
