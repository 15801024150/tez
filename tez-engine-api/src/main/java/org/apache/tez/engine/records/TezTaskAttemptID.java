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

package org.apache.tez.engine.records;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * TezTaskAttemptID represents the immutable and unique identifier for
 * a task attempt. Each task attempt is one particular instance of a Tez Task
 * identified by its TezTaskID.
 *
 * TezTaskAttemptID consists of 2 parts. First part is the
 * {@link TezTaskID}, that this TaskAttemptID belongs to.
 * Second part is the task attempt number. <br>
 * <p>
 * Applications should never construct or parse TaskAttemptID strings
 * , but rather use appropriate constructors or {@link #forName(String)}
 * method.
 *
 * @see TezTaskID
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class TezTaskAttemptID extends TezID {
  public static final String ATTEMPT = "attempt";
  private TezTaskID taskId;
  
  public TezTaskAttemptID() {
    taskId = new TezTaskID();
  }
  
  /**
   * Constructs a TaskAttemptID object from given {@link TezTaskID}.  
   * @param taskId TaskID that this task belongs to  
   * @param id the task attempt number
   */
  public TezTaskAttemptID(TezTaskID taskId, int id) {
    super(id);
    if(taskId == null) {
      throw new IllegalArgumentException("taskId cannot be null");
    }
    this.taskId = taskId;
  }

  /** Returns the {@link TezTaskID} object that this task attempt belongs to */
  public TezTaskID getTaskID() {
    return taskId;
  }
  
  @Override
  public boolean equals(Object o) {
    if (!super.equals(o))
      return false;

    TezTaskAttemptID that = (TezTaskAttemptID)o;
    return this.taskId.equals(that.taskId);
  }
  
  /**
   * Add the unique string to the StringBuilder
   * @param builder the builder to append ot
   * @return the builder that was passed in.
   */
  protected StringBuilder appendTo(StringBuilder builder) {
    return taskId.appendTo(builder).append(SEPARATOR).append(id);
  }
  
  @Override
  public int hashCode() {
    return taskId.hashCode() * 539501 + id;
  }
  
  /**Compare TaskIds by first tipIds, then by task numbers. */
  @Override
  public int compareTo(TezID o) {
    TezTaskAttemptID that = (TezTaskAttemptID)o;
    int tipComp = this.taskId.compareTo(that.taskId);
    if(tipComp == 0) {
      return this.id - that.id;
    }
    else return tipComp;
  }
  @Override
  public String toString() { 
    return appendTo(new StringBuilder(ATTEMPT)).toString();
  }
  
  @Override
  public void readFields(DataInput in) throws IOException {
    taskId.readFields(in);
    super.readFields(in);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    taskId.write(out);
    super.write(out);
  }

  // FIXME TEZ DAG needs to be removed
  public static TezTaskAttemptID read(DataInput in) throws IOException {
    TezTaskAttemptID tId = new TezTaskAttemptID();
    tId.readFields(in);
    return tId;
  }
}
