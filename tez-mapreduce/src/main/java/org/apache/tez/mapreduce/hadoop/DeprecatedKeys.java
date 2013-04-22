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

package org.apache.tez.mapreduce.hadoop;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.dag.api.DAGConfiguration;



public class DeprecatedKeys {

  // This could be done via deprecation.
  private static Map<String, String> mrParamToDAGParamMap = new HashMap<String, String>();

  public static Map<String, String> getMRToDAGParamMap() {
    return Collections.unmodifiableMap(mrParamToDAGParamMap);
  }
 
  static {
    addDeprecatedKeys();
    
    mrParamToDAGParamMap.put(MRJobConfig.JOB_SUBMIT_DIR, DAGConfiguration.JOB_SUBMIT_DIR);
    mrParamToDAGParamMap.put(MRJobConfig.APPLICATION_TOKENS_FILE, DAGConfiguration.APPLICATION_TOKENS_FILE);
    
    mrParamToDAGParamMap.put(MRJobConfig.JOB_NAME, DAGConfiguration.JOB_NAME);
    
    mrParamToDAGParamMap.put(MRJobConfig.MR_AM_JOB_SPECULATOR, DAGConfiguration.DAG_AM_SPECULATOR_CLASS);
    
    // TODO Default value handling.
    mrParamToDAGParamMap.put(MRJobConfig.MR_AM_TASK_LISTENER_THREAD_COUNT, DAGConfiguration.DAG_AM_TASK_LISTENER_THREAD_COUNT);
    
    mrParamToDAGParamMap.put(MRJobConfig.USER_NAME, DAGConfiguration.USER_NAME);
  }

  // TODO TEZAM4 Sometime, make sure this gets loaded by default. Insteaf of the current initialization in MRAppMaster, TezChild.
  // Maybe define in an TEZConfiguration / TEZ JobConf variant.
  
  public static void init() {
  }
  
  private static void addDeprecatedKeys() {
    
    _(MRConfig.MAPRED_IFILE_READAHEAD, TezJobConfig.TEZ_ENGINE_IFILE_READAHEAD);

    _(MRConfig.MAPRED_IFILE_READAHEAD_BYTES, TezJobConfig.TEZ_ENGINE_IFILE_READAHEAD_BYTES);
    
    _(MRJobConfig.RECORDS_BEFORE_PROGRESS, TezJobConfig.RECORDS_BEFORE_PROGRESS);
    
    _(MRJobConfig.JOB_LOCAL_DIR, MRConfig.LOCAL_DIR);
        
    _(MRJobConfig.NUM_REDUCES, TezJobConfig.TEZ_ENGINE_TASK_OUTDEGREE);

    _(MRJobConfig.NUM_MAPS, TezJobConfig.TEZ_ENGINE_TASK_INDEGREE);
    
    _(MRJobConfig.IO_SORT_FACTOR, TezJobConfig.TEZ_ENGINE_IO_SORT_FACTOR);
    
    _(MRJobConfig.MAP_SORT_SPILL_PERCENT, TezJobConfig.TEZ_ENGINE_SORT_SPILL_PERCENT);
    
    _(MRJobConfig.IO_SORT_MB, TezJobConfig.TEZ_ENGINE_IO_SORT_MB);
    
    _(MRJobConfig.INDEX_CACHE_MEMORY_LIMIT, TezJobConfig.TEZ_ENGINE_INDEX_CACHE_MEMORY_LIMIT_BYTES);
    
    _(MRJobConfig.MAP_COMBINE_MIN_SPILLS, TezJobConfig.TEZ_ENGINE_COMBINE_MIN_SPILLS);
    
    _(MRJobConfig.COUNTERS_MAX_KEY, TezJobConfig.COUNTERS_MAX_KEY);
    
    _(MRJobConfig.COUNTER_GROUP_NAME_MAX_KEY, TezJobConfig.COUNTER_GROUP_NAME_MAX_KEY);
    
    _(MRJobConfig.COUNTER_NAME_MAX_KEY, TezJobConfig.COUNTER_NAME_MAX_KEY);
    
    _(MRJobConfig.COUNTER_GROUPS_MAX_KEY, TezJobConfig.COUNTER_GROUPS_MAX_KEY);
    
    _(MRJobConfig.SHUFFLE_PARALLEL_COPIES, TezJobConfig.TEZ_ENGINE_SHUFFLE_PARALLEL_COPIES);
    
    _(MRJobConfig.SHUFFLE_FETCH_FAILURES, TezJobConfig.TEZ_ENGINE_SHUFFLE_FETCH_FAILURES);
    
    _(MRJobConfig.SHUFFLE_NOTIFY_READERROR, TezJobConfig.TEZ_ENGINE_SHUFFLE_NOTIFY_READERROR);
    
    _(MRJobConfig.SHUFFLE_CONNECT_TIMEOUT, TezJobConfig.TEZ_ENGINE_SHUFFLE_CONNECT_TIMEOUT);
    
    _(MRJobConfig.SHUFFLE_READ_TIMEOUT, TezJobConfig.TEZ_ENGINE_SHUFFLE_READ_TIMEOUT);
    
    _(MRConfig.SHUFFLE_SSL_ENABLED_KEY, TezJobConfig.TEZ_ENGINE_SHUFFLE_ENABLE_SSL);
    
    _(MRJobConfig.SHUFFLE_INPUT_BUFFER_PERCENT, TezJobConfig.TEZ_ENGINE_SHUFFLE_INPUT_BUFFER_PERCENT);
    
    _(MRJobConfig.SHUFFLE_MEMORY_LIMIT_PERCENT, TezJobConfig.TEZ_ENGINE_SHUFFLE_MEMORY_LIMIT_PERCENT);
    
    _(MRJobConfig.SHUFFLE_MERGE_PERCENT, TezJobConfig.TEZ_ENGINE_SHUFFLE_MERGE_PERCENT);
    
    _(MRJobConfig.REDUCE_MEMTOMEM_THRESHOLD, TezJobConfig.TEZ_ENGINE_SHUFFLE_MEMTOMEM_SEGMENTS);
    
    _(MRJobConfig.REDUCE_MEMTOMEM_ENABLED, TezJobConfig.TEZ_ENGINE_SHUFFLE_ENABLE_MEMTOMEM);
    
    _(MRJobConfig.SHUFFLE_INPUT_BUFFER_PERCENT, TezJobConfig.TEZ_ENGINE_INPUT_BUFFER_PERCENT);
    
    _(MRJobConfig.MAPREDUCE_JOB_CREDENTIALS_BINARY, TezJobConfig.DAG_CREDENTIALS_BINARY);
  }

  private static void _(String oldKey, String newKey) {
    Configuration.addDeprecation(oldKey, newKey);
  }
}
