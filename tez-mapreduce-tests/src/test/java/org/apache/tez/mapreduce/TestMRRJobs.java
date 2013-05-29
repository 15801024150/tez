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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.RandomTextWriterJob;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobCounter;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.TaskReport;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.JarFinder;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.tez.mapreduce.examples.MRRSleepJob;
import org.apache.tez.mapreduce.hadoop.MRConfig;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestMRRJobs {

  private static final Log LOG = LogFactory.getLog(TestMRRJobs.class);

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

  private static String TEST_ROOT_DIR = "target"
      + Path.SEPARATOR + TestMRRJobs.class.getName() + "-tmpDir";

  private static Path TEST_ROOT_DIR_PATH =
      localFs.makeQualified(new Path(TEST_ROOT_DIR));
  static Path APP_JAR = new Path(TEST_ROOT_DIR_PATH, "MRAppJar.jar");
  static Path YARN_SITE_XML = new Path(TEST_ROOT_DIR_PATH, "yarn-site.xml");
  private static final String OUTPUT_ROOT_DIR = "/tmp" + Path.SEPARATOR +
      TestMRRJobs.class.getSimpleName();

  @BeforeClass
  public static void setup() throws IOException {
    try {
      conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, TEST_ROOT_DIR);
      dfsCluster = new MiniDFSCluster.Builder(conf).numDataNodes(2)
        .format(true).racks(null).build();
      remoteFs = dfsCluster.getFileSystem();
    } catch (IOException io) {
      throw new RuntimeException("problem starting mini dfs cluster", io);
    }

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    if (mrrTezCluster == null) {
      mrrTezCluster = new MiniMRRTezCluster(TestMRRJobs.class.getName(), 1,
          1, 1);
      Configuration conf = new Configuration();
      conf.set("fs.defaultFS", remoteFs.getUri().toString());   // use HDFS
      conf.set(MRJobConfig.MR_AM_STAGING_DIR, "/apps_staging_dir");
      mrrTezCluster.init(conf);
      mrrTezCluster.start();
    }

    localFs.copyFromLocalFile(new Path(MiniMRRTezCluster.APPJAR), APP_JAR);
    localFs.setPermission(APP_JAR, new FsPermission("700"));
    localFs.copyFromLocalFile(mrrTezCluster.getConfigFilePath(), YARN_SITE_XML);
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
  }

  @Test (timeout = 300000)
  public void testMRRSleepJob() throws IOException, InterruptedException,
      ClassNotFoundException {
    LOG.info("\n\n\nStarting testMRRSleepJob().");

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    Configuration sleepConf = new Configuration(mrrTezCluster.getConfig());

    MRRSleepJob sleepJob = new MRRSleepJob();
    sleepJob.setConf(sleepConf);

    Job job = sleepJob.createJob(1, 1, 1, 1, 1,
        1, 1, 1, 1, 1);

    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.addFileToClassPath(YARN_SITE_XML);
    job.setJarByClass(MRRSleepJob.class);
    job.setMaxMapAttempts(1); // speed up failures
    job.submit();
    String trackingUrl = job.getTrackingURL();
    String jobId = job.getJobID().toString();
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertTrue(succeeded);
    Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());
    Assert.assertTrue("Tracking URL was " + trackingUrl +
                      " but didn't Match Job ID " + jobId ,
          trackingUrl.endsWith(jobId.substring(jobId.lastIndexOf("_")) + "/"));

    // FIXME once counters and task progress can be obtained properly
    // TODO use dag client to test counters and task progress?
    // what about completed jobs?

  }

  @Test (timeout = 60000)
  public void testRandomWriter() throws IOException, InterruptedException,
      ClassNotFoundException {

    LOG.info("\n\n\nStarting testRandomWriter().");
    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    RandomTextWriterJob randomWriterJob = new RandomTextWriterJob();
    mrrTezCluster.getConfig().set(RandomTextWriterJob.TOTAL_BYTES, "3072");
    mrrTezCluster.getConfig().set(RandomTextWriterJob.BYTES_PER_MAP, "1024");
    Job job = randomWriterJob.createJob(mrrTezCluster.getConfig());
    Path outputDir = new Path(OUTPUT_ROOT_DIR, "random-output");
    FileOutputFormat.setOutputPath(job, outputDir);
    job.setSpeculativeExecution(false);
    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.addFileToClassPath(YARN_SITE_XML);
    job.setJarByClass(RandomTextWriterJob.class);
    job.setMaxMapAttempts(1); // speed up failures
    job.submit();
    String trackingUrl = job.getTrackingURL();
    String jobId = job.getJobID().toString();
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertTrue(succeeded);
    Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());
    Assert.assertTrue("Tracking URL was " + trackingUrl +
                      " but didn't Match Job ID " + jobId ,
          trackingUrl.endsWith(jobId.substring(jobId.lastIndexOf("_")) + "/"));

    // Make sure there are three files in the output-dir

    RemoteIterator<FileStatus> iterator =
        FileContext.getFileContext(mrrTezCluster.getConfig()).listStatus(
            outputDir);
    int count = 0;
    while (iterator.hasNext()) {
      FileStatus file = iterator.next();
      if (!file.getPath().getName()
          .equals(FileOutputCommitter.SUCCEEDED_FILE_NAME)) {
        count++;
      }
    }
    Assert.assertEquals("Number of part files is wrong!", 3, count);

  }


  @Test (timeout = 60000)
  public void testFailingJob() throws IOException, InterruptedException,
      ClassNotFoundException {

    LOG.info("\n\n\nStarting testFailingMapper().");

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    Configuration sleepConf = new Configuration(mrrTezCluster.getConfig());

    MRRSleepJob sleepJob = new MRRSleepJob();
    sleepJob.setConf(sleepConf);

    Job job = sleepJob.createJob(1, 1, 1, 1, 1,
        1, 1, 1, 1, 1);

    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.addFileToClassPath(YARN_SITE_XML);
    job.setJarByClass(MRRSleepJob.class);
    job.setMaxMapAttempts(1); // speed up failures
    job.getConfiguration().setBoolean(MRRSleepJob.MAP_FATAL_ERROR, true);
    job.getConfiguration().set(MRRSleepJob.MAP_ERROR_TASK_IDS, "*");

    job.submit();
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertFalse(succeeded);
    Assert.assertEquals(JobStatus.State.FAILED, job.getJobState());

    // FIXME once counters and task progress can be obtained properly
    // TODO verify failed task diagnostics
  }

  @Test (timeout = 300000)
  public void testFailingAttempt() throws IOException, InterruptedException,
      ClassNotFoundException {

    LOG.info("\n\n\nStarting testFailingMapper().");

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRRTezCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    Configuration sleepConf = new Configuration(mrrTezCluster.getConfig());

    MRRSleepJob sleepJob = new MRRSleepJob();
    sleepJob.setConf(sleepConf);

    Job job = sleepJob.createJob(1, 1, 1, 1, 1,
        1, 1, 1, 1, 1);

    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.addFileToClassPath(YARN_SITE_XML);
    job.setJarByClass(MRRSleepJob.class);
    job.setMaxMapAttempts(3); // speed up failures
    job.getConfiguration().setBoolean(MRRSleepJob.MAP_THROW_ERROR, true);
    job.getConfiguration().set(MRRSleepJob.MAP_ERROR_TASK_IDS, "0");

    job.submit();
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertTrue(succeeded);
    Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());

    // FIXME once counters and task progress can be obtained properly
    // TODO verify failed task diagnostics
  }


  /*
  //@Test (timeout = 60000)
  public void testMRRSleepJobWithSecurityOn() throws IOException,
      InterruptedException, ClassNotFoundException {

    LOG.info("\n\n\nStarting testMRRSleepJobWithSecurityOn().");

    if (!(new File(MiniMRRTezCluster.APPJAR)).exists()) {
      return;
    }

    mrrTezCluster.getConfig().set(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        "kerberos");
    mrrTezCluster.getConfig().set(YarnConfiguration.RM_KEYTAB, "/etc/krb5.keytab");
    mrrTezCluster.getConfig().set(YarnConfiguration.NM_KEYTAB, "/etc/krb5.keytab");
    mrrTezCluster.getConfig().set(YarnConfiguration.RM_PRINCIPAL,
        "rm/sightbusy-lx@LOCALHOST");
    mrrTezCluster.getConfig().set(YarnConfiguration.NM_PRINCIPAL,
        "nm/sightbusy-lx@LOCALHOST");

    UserGroupInformation.setConfiguration(mrrTezCluster.getConfig());

    // Keep it in here instead of after RM/NM as multiple user logins happen in
    // the same JVM.
    UserGroupInformation user = UserGroupInformation.getCurrentUser();

    LOG.info("User name is " + user.getUserName());
    for (Token<? extends TokenIdentifier> str : user.getTokens()) {
      LOG.info("Token is " + str.encodeToUrlString());
    }
    user.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {
        MRRSleepJob sleepJob = new MRRSleepJob();
        sleepJob.setConf(mrrTezCluster.getConfig());
        Job job = sleepJob.createJob(3, 0, 10000, 1, 0, 0);
        // //Job with reduces
        // Job job = sleepJob.createJob(3, 2, 10000, 1, 10000, 1);
        job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
        job.submit();
        String trackingUrl = job.getTrackingURL();
        String jobId = job.getJobID().toString();
        job.waitForCompletion(true);
        Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());
        Assert.assertTrue("Tracking URL was " + trackingUrl +
                          " but didn't Match Job ID " + jobId ,
          trackingUrl.endsWith(jobId.substring(jobId.lastIndexOf("_")) + "/"));
        return null;
      }
    });

    // TODO later:  add explicit "isUber()" checks of some sort
  }
  */

}
