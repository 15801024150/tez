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

package org.apache.tez.dag.utils;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Vector;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.YarnTezDagChild;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.records.TezVertexID;

public class TezEngineChildJVM {

    // FIXME 
  public static enum LogName {
    /** Log on the stdout of the task. */
    STDOUT ("stdout"),

    /** Log on the stderr of the task. */
    STDERR ("stderr"),
    
    /** Log on the map-reduce system logs of the task. */
    SYSLOG ("syslog"),
    
    /** The java profiler information. */
    PROFILE ("profile.out"),
    
    /** Log the debug script's stdout  */
    DEBUGOUT ("debugout");
        
    private String prefix;
    
    private LogName(String prefix) {
      this.prefix = prefix;
    }
    
    @Override
    public String toString() {
      return prefix;
    }
  }
  
  private static String getTaskLogFile(LogName filter) {
    return ApplicationConstants.LOG_DIR_EXPANSION_VAR + Path.SEPARATOR + 
        filter.toString();
  }
  
  public static List<String> getVMCommand(
      InetSocketAddress taskAttemptListenerAddr, TezConfiguration conf, 
      TezVertexID vertexId, 
      ContainerId containerId, ApplicationId jobID, boolean shouldProfile,
      String javaOpts) {

    Vector<String> vargs = new Vector<String>(9);

    vargs.add(Environment.JAVA_HOME.$() + "/bin/java");

    //set custom javaOpts
    vargs.add(javaOpts); 
    
//[Debug Task] Current simplest way to attach debugger to  Tez Child Task
// Uncomment the following, then launch a regular job
// Works best on one-box configured with a single container (hence one task at a time). 
//    LOG.error(" !!!!!!!!! Launching Child-Task in debug/suspend mode.  Attach to port 8003 !!!!!!!!");
//    vargs.add("-Xdebug -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,address=8003,server=y,suspend=y");
    
    Path childTmpDir = new Path(Environment.PWD.$(),
        YarnConfiguration.DEFAULT_CONTAINER_TEMP_DIR);
    vargs.add("-Djava.io.tmpdir=" + childTmpDir);
    
    // FIXME Setup the log4j properties

    // Decision to profile needs to be made in the scheduler.
    if (shouldProfile) {
      // FIXME add support for profiling
    }

    // Add main class and its arguments 
    vargs.add(YarnTezDagChild.class.getName());  // main of Child

    // pass TaskAttemptListener's address
    vargs.add(taskAttemptListenerAddr.getAddress().getHostAddress()); 
    vargs.add(Integer.toString(taskAttemptListenerAddr.getPort()));

    // Finally add the containerId.
    vargs.add(String.valueOf(containerId.toString()));
    vargs.add("1>" + getTaskLogFile(LogName.STDOUT));
    vargs.add("2>" + getTaskLogFile(LogName.STDERR));

    // Final commmand
    StringBuilder mergedCommand = new StringBuilder();
    for (CharSequence str : vargs) {
      mergedCommand.append(str).append(" ");
    }
    Vector<String> vargsFinal = new Vector<String>(1);
    vargsFinal.add(mergedCommand.toString());
    return vargsFinal;
  }

}
