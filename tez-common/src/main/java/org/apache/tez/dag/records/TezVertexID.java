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

package org.apache.tez.dag.records;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.text.NumberFormat;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * TezVertexID represents the immutable and unique identifier for
 * a Vertex in a Tez DAG. Each TezVertexID encompasses multiple Tez Tasks.
 *
 * TezVertezID consists of 2 parts. The first part is the {@link TezDAGID},
 * that is the Tez DAG that this vertex belongs to. The second part is
 * the vertex number.
 *
 * @see TezDAGID
 * @see TezTaskID
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class TezVertexID extends TezID {
  public static final String VERTEX = "vertex";
  protected static final ThreadLocal<NumberFormat> idFormat = new ThreadLocal<NumberFormat>() {

    @Override
    public NumberFormat initialValue() {
      NumberFormat fmt = NumberFormat.getInstance();
      fmt.setGroupingUsed(false);
      fmt.setMinimumIntegerDigits(2);
      return fmt;
    }
  };

  private static LoadingCache<TezVertexID, TezVertexID> vertexIDCache = CacheBuilder.newBuilder().softValues().
      build(
          new CacheLoader<TezVertexID, TezVertexID>() {
            @Override
            public TezVertexID load(TezVertexID key) throws Exception {
              return key;
            }
          }
      );
  
  private TezDAGID dagId;

  // Public for Writable serialization. Verify if this is actually required.
  public TezVertexID() {
  }

  /**
   * Constructs a TaskID object from given {@link TezDAGID}.
   * @param applicationId JobID that this tip belongs to
   * @param type the {@link TezTaskType} of the task
   * @param id the tip number
   */
  public static TezVertexID getInstance(TezDAGID dagId, int id) {
    Preconditions.checkArgument(dagId != null, "DagID cannot be null");
    return vertexIDCache.getUnchecked(new TezVertexID(dagId, id));
  }

  private TezVertexID(TezDAGID dagId, int id) {
    super(id);
    this.dagId = dagId;
  }

  /** Returns the {@link TezDAGID} object that this tip belongs to */
  public TezDAGID getDAGId() {
    return dagId;
  }

  @Override
  public boolean equals(Object o) {
    if (!super.equals(o))
      return false;

    TezVertexID that = (TezVertexID)o;
    return this.dagId.equals(that.dagId);
  }

  /**Compare TaskInProgressIds by first jobIds, then by tip numbers and type.*/
  @Override
  public int compareTo(TezID o) {
    TezVertexID that = (TezVertexID)o;
    return this.dagId.compareTo(that.dagId);
  }

  @Override
  public String toString() {
    return appendTo(new StringBuilder(VERTEX)).toString();
  }

  @Override
  // Can't do much about this instance if used via the RPC layer. Any downstream
  // users can however avoid using this method.
  public void readFields(DataInput in) throws IOException {
    dagId = TezDAGID.readTezDAGID(in);
    super.readFields(in);
  }
  
  public static TezVertexID readTezVertexID(DataInput in) throws IOException {
    TezDAGID dagID = TezDAGID.readTezDAGID(in);
    int vertexIdInt = TezID.readID(in);
    return getInstance(dagID, vertexIdInt);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    dagId.write(out);
    super.write(out);
  }

  /**
   * Add the unique string to the given builder.
   * @param builder the builder to append to
   * @return the builder that was passed in
   */
  protected StringBuilder appendTo(StringBuilder builder) {
    return dagId.appendTo(builder).
        append(SEPARATOR).
        append(idFormat.get().format(id));
  }

  @Override
  public int hashCode() {
    return dagId.hashCode() * 530017 + id;
  }

}
