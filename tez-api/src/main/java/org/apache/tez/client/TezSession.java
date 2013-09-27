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

package org.apache.tez.client;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolBlockingPB;
import org.apache.tez.dag.api.client.rpc.DAGClientRPCImpl;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolRPC.ShutdownSessionRequestProto;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolRPC.SubmitDAGRequestProto;
import org.apache.tez.dag.api.records.DAGProtos.DAGPlan;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ServiceException;

public class TezSession {

  private static final Log LOG = LogFactory.getLog(TezSession.class);

  private final String sessionName;
  private ApplicationId applicationId;
  private LocalResource tezConfPBLRsrc = null;
  private final TezSessionConfiguration sessionConfig;
  private YarnClient yarnClient;
  private Map<String, LocalResource> tezJarResources;
  private boolean sessionStarted = false;

  public TezSession(String sessionName,
      ApplicationId applicationId,
      TezSessionConfiguration sessionConfig) {
    this.sessionName = sessionName;
    this.sessionConfig = sessionConfig;
    this.applicationId = applicationId;
  }

  public TezSession(String sessionName,
      TezSessionConfiguration sessionConfig) {
    this(sessionName, null, sessionConfig);
  }

  public synchronized void start() throws TezException, IOException {
    yarnClient = YarnClient.createYarnClient();
    yarnClient.init(sessionConfig.getYarnConfiguration());
    yarnClient.start();

    tezJarResources = TezClientUtils.setupTezJarsLocalResources(
        sessionConfig.getTezConfiguration());

    try {
      if (applicationId == null) {
        applicationId = yarnClient.createApplication().
            getNewApplicationResponse().getApplicationId();
      }

      ApplicationSubmissionContext appContext =
          TezClientUtils.createApplicationSubmissionContext(
              sessionConfig.getTezConfiguration(), applicationId,
              null, sessionName, sessionConfig.getAMConfiguration(),
              tezJarResources);
      // Set Tez Sessions to not retry on AM crashes
      appContext.setMaxAppAttempts(1);
      tezConfPBLRsrc = appContext.getAMContainerSpec().getLocalResources().get(
          TezConfiguration.TEZ_PB_BINARY_CONF_NAME);
      yarnClient.submitApplication(appContext);
    } catch (YarnException e) {
      throw new TezException(e);
    }
    sessionStarted = true;
  }

  public synchronized DAGClient submitDAG(DAG dag)
      throws TezException, IOException {
    if (!sessionStarted) {
      throw new TezUncheckedException("Session not started");
    }

    String dagId = null;
    LOG.info("Submitting dag to TezSession"
        + ", sessionName=" + sessionName
        + ", applicationId=" + applicationId);
    // Add tez jars to vertices too
    for (Vertex v : dag.getVertices()) {
      v.getTaskLocalResources().putAll(tezJarResources);
      if (null != tezConfPBLRsrc) {
        v.getTaskLocalResources().put(TezConfiguration.TEZ_PB_BINARY_CONF_NAME,
            tezConfPBLRsrc);
      }
    }
    DAGPlan dagPlan = dag.createDag(sessionConfig.getTezConfiguration());
    SubmitDAGRequestProto requestProto =
        SubmitDAGRequestProto.newBuilder().setDAGPlan(dagPlan).build();

    DAGClientAMProtocolBlockingPB proxy;
    while (true) {
      // FIXME implement a max time to wait for submit
      proxy = TezClientUtils.getAMProxy(yarnClient,
          sessionConfig.getYarnConfiguration(), applicationId);
      if (proxy != null) {
        break;
      }
      try {
        Thread.sleep(100l);
      } catch (InterruptedException e) {
        // Ignore
      }
    }

    try {
      dagId = proxy.submitDAG(null, requestProto).getDagId();
    } catch (ServiceException e) {
      throw new TezException(e);
    }
    LOG.info("Submitted dag to TezSession"
        + ", sessionName=" + sessionName
        + ", applicationId=" + applicationId
        + ", dagId=" + dagId);
    return new DAGClientRPCImpl(applicationId, dagId,
        sessionConfig.getTezConfiguration());
  }

  public synchronized void stop() throws TezException, IOException {
    LOG.info("Shutting down Tez Session"
        + ", sessionName=" + sessionName
        + ", applicationId=" + applicationId);
    try {
      DAGClientAMProtocolBlockingPB proxy = TezClientUtils.getAMProxy(
          yarnClient, sessionConfig.getYarnConfiguration(), applicationId);
      if (proxy != null) {
        ShutdownSessionRequestProto request =
            ShutdownSessionRequestProto.newBuilder().build();
        proxy.shutdownSession(null, request);
        return;
      }
    } catch (TezException e) {
      LOG.info("Failed to shutdown Tez Session via proxy", e);
    } catch (ServiceException e) {
      LOG.info("Failed to shutdown Tez Session via proxy", e);
    }
    LOG.info("Could not connect to AM, killing session via YARN"
        + ", sessionName=" + sessionName
        + ", applicationId=" + applicationId);
    try {
      yarnClient.killApplication(applicationId);
    } catch (YarnException e) {
      throw new TezException(e);
    }
  }

  public String getSessionName() {
    return sessionName;
  }

  @Private
  @VisibleForTesting
  public synchronized ApplicationId getApplicationId() {
    return applicationId;
  }
}
