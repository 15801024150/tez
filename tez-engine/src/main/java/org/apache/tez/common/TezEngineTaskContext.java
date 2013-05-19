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

package org.apache.tez.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.io.Text;
import org.apache.tez.dag.records.TezTaskAttemptID;

public class TezEngineTaskContext extends TezTaskContext {

  // These two could be replaced by a TezConfiguration / DagSpec.
  private List<InputSpec> inputSpecList;
  private List<OutputSpec> outputSpecList;
  private String processorName;
  
  public TezEngineTaskContext() {
    super();
  }

  public TezEngineTaskContext(TezTaskAttemptID taskAttemptID, String user,
      String jobName, String vertexName, String processorName,
      List<InputSpec> inputSpecList, List<OutputSpec> outputSpecList) {
    super(taskAttemptID, user, jobName, vertexName);
    this.inputSpecList = inputSpecList;
    this.outputSpecList = outputSpecList;
    if (this.inputSpecList == null) {
      inputSpecList = new ArrayList<InputSpec>(0);
    }
    if (this.outputSpecList == null) {
      outputSpecList = new ArrayList<OutputSpec>(0);
    }
    this.inputSpecList = inputSpecList;
    this.outputSpecList = outputSpecList;
    this.processorName = processorName;
  }

  public String getRuntimeName() {
    // FIXME. Add this to the DAG configuration, and fetch from there.
    return "org.apache.tez.mapreduce.task.MRRuntimeTask";
  }

  public String getProcessorName() {
    return processorName;
  }
  
  public List<InputSpec> getInputSpecList() {
    return this.inputSpecList;
  }
  
  public List<OutputSpec> getOutputSpecList() {
    return this.outputSpecList;
  }
  
  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    Text.writeString(out, processorName);
    out.writeInt(inputSpecList.size());
    for (InputSpec inputSpec : inputSpecList) {
      inputSpec.write(out);
    }
    out.writeInt(outputSpecList.size());
    for (OutputSpec outputSpec : outputSpecList) {
      outputSpec.write(out);
    }
  }
  
  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    
    processorName = Text.readString(in);
    int numInputSpecs = in.readInt();
    inputSpecList = new ArrayList<InputSpec>(numInputSpecs);
    for (int i = 0; i < numInputSpecs; i++) {
      InputSpec inputSpec = new InputSpec();
      inputSpec.readFields(in);
      inputSpecList.add(inputSpec);
    }
    int numOutputSpecs = in.readInt();
    outputSpecList = new ArrayList<OutputSpec>(numOutputSpecs);
    for (int i = 0; i < numOutputSpecs; i++) {
      OutputSpec outputSpec = new OutputSpec();
      outputSpec.readFields(in);
      outputSpecList.add(outputSpec);
    }
  }

  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("processorName=" + processorName
        + ", inputSpecListSize=" + inputSpecList.size()
        + ", outputSpecListSize=" + outputSpecList.size());
    sb.append(", inputSpecList=[");
    for (InputSpec i : inputSpecList) {
      sb.append("{" + i.toString() + "}, ");
    }
    sb.append("], outputSpecList=[");
    for (OutputSpec i : outputSpecList) {
      sb.append("{" + i.toString() + "}, ");
    }
    sb.append("]");
    return sb.toString();
  }
}
