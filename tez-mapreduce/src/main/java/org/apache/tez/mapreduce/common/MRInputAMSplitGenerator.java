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

package org.apache.tez.mapreduce.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.Job;
import org.apache.tez.dag.api.VertexLocationHint.TaskLocationHint;
import org.apache.tez.mapreduce.hadoop.InputSplitInfoMem;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.protos.MRRuntimeProtos.MRInputUserPayloadProto;
import org.apache.tez.mapreduce.protos.MRRuntimeProtos.MRSplitProto;
import org.apache.tez.mapreduce.protos.MRRuntimeProtos.MRSplitsProto;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.TezRootInputInitializer;
import org.apache.tez.runtime.api.TezRootInputInitializerContext;
import org.apache.tez.runtime.api.events.RootInputConfigureVertexTasksEvent;
import org.apache.tez.runtime.api.events.RootInputDataInformationEvent;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

public class MRInputAMSplitGenerator implements TezRootInputInitializer {

  private static final Log LOG = LogFactory
      .getLog(MRInputAMSplitGenerator.class);

  public MRInputAMSplitGenerator() {
  }

  @Override
  public List<Event> initialize(TezRootInputInitializerContext rootInputContext)
      throws Exception {
    Stopwatch sw = null;
    if (LOG.isDebugEnabled()) {
      sw = new Stopwatch().start();
    }
    MRInputUserPayloadProto userPayloadProto = MRHelpers
        .parseMRInputPayload(rootInputContext.getUserPayload());
    if (LOG.isDebugEnabled()) {
      sw.stop();
      LOG.debug("Time to parse MRInput payload into prot: "
          + sw.elapsedMillis());
    }
    if (LOG.isDebugEnabled()) {
      sw.reset().start();
    }
    Configuration conf = MRHelpers.createConfFromByteString(userPayloadProto
        .getConfigurationBytes());
    if (LOG.isDebugEnabled()) {
      sw.stop();
      LOG.debug("Time converting ByteString to configuration: " + sw.elapsedMillis());
    }

    if (LOG.isDebugEnabled()) {
      sw.reset().start();
    }

    InputSplitInfoMem inputSplitInfo = null;
    String realInputFormatName = userPayloadProto.getInputFormatName(); 
    if ( realInputFormatName != null && !realInputFormatName.isEmpty()) {
      // split grouping on the AM
      JobConf jobConf = new JobConf(conf);
      if (jobConf.getUseNewMapper()) {
        LOG.info("Grouping mapreduce api input splits");
        Job job = Job.getInstance(conf);
        org.apache.hadoop.mapreduce.InputSplit[] splits = MRHelpers
            .generateNewSplits(job, realInputFormatName,
                rootInputContext.getNumTasks());
        SerializationFactory serializationFactory = new SerializationFactory(
            job.getConfiguration());

        MRSplitsProto.Builder splitsBuilder = MRSplitsProto.newBuilder();

        List<TaskLocationHint> locationHints = Lists
            .newArrayListWithCapacity(splits.length);
        for (org.apache.hadoop.mapreduce.InputSplit split : splits) {
          splitsBuilder.addSplits(MRHelpers.createSplitProto(split,
              serializationFactory));
          String rack = 
              ((org.apache.hadoop.mapreduce.split.TezGroupedSplit) split).getRack();
          if (rack == null) {
            locationHints.add(new TaskLocationHint(new HashSet<String>(Arrays
                .asList(split.getLocations())), null));
          } else {
            locationHints.add(new TaskLocationHint(null, 
                Collections.singleton(rack)));
          }
          locationHints.add(new TaskLocationHint(new HashSet<String>(Arrays
              .asList(split.getLocations())), null));
        }
        inputSplitInfo = new InputSplitInfoMem(splitsBuilder.build(),
            locationHints, splits.length);
      } else {
        LOG.info("Grouping mapred api input splits");
        org.apache.hadoop.mapred.InputSplit[] splits = MRHelpers
            .generateOldSplits(jobConf, realInputFormatName,
                rootInputContext.getNumTasks());
        List<TaskLocationHint> locationHints = Lists
            .newArrayListWithCapacity(splits.length);
        MRSplitsProto.Builder splitsBuilder = MRSplitsProto.newBuilder();
        for (org.apache.hadoop.mapred.InputSplit split : splits) {
          splitsBuilder.addSplits(MRHelpers.createSplitProto(split));
          String rack = 
              ((org.apache.hadoop.mapred.split.TezGroupedSplit) split).getRack();
          if (rack == null) {
            locationHints.add(new TaskLocationHint(new HashSet<String>(Arrays
                .asList(split.getLocations())), null));
          } else {
            locationHints.add(new TaskLocationHint(null, 
                Collections.singleton(rack)));
          }
        }
        inputSplitInfo = new InputSplitInfoMem(splitsBuilder.build(),
            locationHints, splits.length);
      }
    } else {
      inputSplitInfo = MRHelpers.generateInputSplitsToMem(conf);
    }
    if (LOG.isDebugEnabled()) {
      sw.stop();
      LOG.debug("Time to create splits to mem: " + sw.elapsedMillis());
    }

    List<Event> events = Lists.newArrayListWithCapacity(inputSplitInfo
        .getNumTasks() + 1);
    
    RootInputConfigureVertexTasksEvent configureVertexEvent = new RootInputConfigureVertexTasksEvent(
        inputSplitInfo.getNumTasks(), inputSplitInfo.getTaskLocationHints());
    events.add(configureVertexEvent);

    MRSplitsProto splitsProto = inputSplitInfo.getSplitsProto();

    int count = 0;
    for (MRSplitProto mrSplit : splitsProto.getSplitsList()) {
      // Unnecessary array copy, can be avoided by using ByteBuffer instead of a
      // raw array.
      RootInputDataInformationEvent diEvent = new RootInputDataInformationEvent(
          count++, mrSplit.toByteArray());
      events.add(diEvent);
    }
    return events;
  }

}
