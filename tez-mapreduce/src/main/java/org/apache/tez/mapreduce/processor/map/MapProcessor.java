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
package org.apache.tez.mapreduce.processor.map;

import java.io.IOException;
import java.util.concurrent.FutureTask;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.MapRunnable;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.map.WrappedMapper;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tez.common.TezEngineTaskContext;
import org.apache.tez.common.TezTaskStatus;
import org.apache.tez.common.counters.TaskCounter;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.engine.api.Input;
import org.apache.tez.engine.api.Master;
import org.apache.tez.engine.api.Output;
import org.apache.tez.engine.api.Processor;
import org.apache.tez.engine.common.sort.SortingOutput;
import org.apache.tez.mapreduce.hadoop.IDConverter;
import org.apache.tez.mapreduce.hadoop.mapred.TaskAttemptContextImpl;
import org.apache.tez.mapreduce.input.SimpleInput;
import org.apache.tez.mapreduce.output.SimpleOutput;
import org.apache.tez.mapreduce.processor.MRTask;
import org.apache.tez.mapreduce.processor.MRTaskReporter;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class MapProcessor extends MRTask implements Processor {

  private static final Log LOG = LogFactory.getLog(MapProcessor.class);

  private Progress mapPhase;

  public MapProcessor(TezEngineTaskContext context) throws IOException {
    super(context);
  }
  


  @Override
  public void initialize(Configuration conf, Master master) throws IOException,
  InterruptedException {
    super.initialize(conf, master);
  }

  @Override
  public void process(
      final Input[] ins,
      final Output[] outs)
          throws IOException, InterruptedException {

    MRTaskReporter reporter = new MRTaskReporter(getTaskReporter());
    boolean useNewApi = jobConf.getUseNewMapper();
    initTask(jobConf, taskAttemptId.getTaskID().getVertexID().getDAGId(),
        reporter, useNewApi);

    if (ins.length != 1
        || outs.length != 1) {
      throw new IOException("Cannot handle multiple inputs or outputs"
          + ", inputCount=" + ins.length
          + ", outputCount=" + outs.length);
    }
    Input in = ins[0];
    Output out = outs[0];
    
    // Sanity check
    if (!(in instanceof SimpleInput)) {
      throw new IOException("Unknown input! - " + in.getClass());
    }
    SimpleInput input = (SimpleInput)in;
    input.setTask(this);

    if (out instanceof SimpleOutput) {
      initCommitter(jobConf, useNewApi, false);
      ((SimpleOutput)out).setTask(this);
    } else if (out instanceof SortingOutput) {
      initCommitter(jobConf, useNewApi, true);
      initPartitioner(jobConf);
      ((SortingOutput)out).setTask(this);
    }

    // If there are no reducers then there won't be any sort. Hence the map 
    // phase will govern the entire attempt's progress.
    if (tezEngineTaskContext.getOutputSpecList().get(0).getNumOutputs() == 0) {
      mapPhase = getProgress().addPhase("map");
    } else {
      // If there are reducers then the entire attempt's progress will be 
      // split between the map phase (67%) and the sort phase (33%).
      mapPhase = getProgress().addPhase("map");
    }


    if (useNewApi) {
      runNewMapper(jobConf, reporter, input, out, getTaskReporter());
    } else {
      runOldMapper(jobConf, reporter, input, out, getTaskReporter());
    }

    done(out.getOutputContext(), reporter);
  }

  public void close() throws IOException, InterruptedException {
    // TODO Auto-generated method stub

  }
  
  void runOldMapper(
      final JobConf job,
      final MRTaskReporter reporter,
      final SimpleInput input,
      final Output output,
      final Master master
      ) throws IOException, InterruptedException {
    
    FutureTask<Void> initInputFuture = initInputAsync(input);
    FutureTask<Void> initOutputFuture = initOutputAsync(output);
    
    RecordReader in = new OldRecordReader(input);
        
    int numReduceTasks = tezEngineTaskContext.getOutputSpecList().get(0)
        .getNumOutputs();
    LOG.info("numReduceTasks: " + numReduceTasks);

    OutputCollector collector = new OldOutputCollector(output);

    MapRunnable runner =
        (MapRunnable)ReflectionUtils.newInstance(job.getMapRunnerClass(), job);

    // Wait for input/output to be initialized before starting processing.
    LOG.info("Waiting on input initialization");
    waitForInputInitialization(initInputFuture);

    LOG.info("Waiting on output initialization");
    waitForInputInitialization(initOutputFuture);

    try {
      runner.run(in, collector, (Reporter)reporter);
      mapPhase.complete();
      // start the sort phase only if there are reducers
      if (numReduceTasks > 0) {
        setPhase(TezTaskStatus.Phase.SORT);
      }
      this.statusUpdate();
    } finally {
      //close
      in.close();                               // close input
      output.close();
    }
  }

  private void runNewMapper(final JobConf job,
      MRTaskReporter reporter,
      final SimpleInput in,
      Output out,
      final Master master
      ) throws IOException, InterruptedException {
    // make a task context so we can get the classes
    
    FutureTask<Void> initInputFuture = initInputAsync(in);
    FutureTask<Void> initOutputFuture = initOutputAsync(out);
    
    org.apache.hadoop.mapreduce.TaskAttemptContext taskContext =
        new TaskAttemptContextImpl(job, taskAttemptId, reporter);

    // make a mapper
    org.apache.hadoop.mapreduce.Mapper mapper;
    try {
      mapper = (org.apache.hadoop.mapreduce.Mapper)
          ReflectionUtils.newInstance(taskContext.getMapperClass(), job);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    }

    if (!(in instanceof SimpleInput)) {
      throw new IOException("Unknown input! - " + in.getClass());
    }

    org.apache.hadoop.mapreduce.RecordReader input =
        new NewRecordReader(in);

    org.apache.hadoop.mapreduce.RecordWriter output = 
        new NewOutputCollector(out);

    // Wait for input/output to be initialized before starting processing.
    LOG.info("Waiting on input initialization");
    waitForInputInitialization(initInputFuture);

    LOG.info("Waiting on output initialization");
    waitForInputInitialization(initOutputFuture);

    org.apache.hadoop.mapreduce.InputSplit split = in.getNewInputSplit();
    
    org.apache.hadoop.mapreduce.MapContext 
    mapContext = 
    new org.apache.tez.mapreduce.hadoop.mapreduce.MapContextImpl(
        job, IDConverter.toMRTaskAttemptId(taskAttemptId), 
        input, output, 
        getCommitter(), 
        reporter, split);

    org.apache.hadoop.mapreduce.Mapper.Context mapperContext = 
        new WrappedMapper().getMapContext(mapContext);

    input.initialize(split, mapperContext);
    mapper.run(mapperContext);
    mapPhase.complete();
    setPhase(TezTaskStatus.Phase.SORT);
    this.statusUpdate();
    input.close();
    output.close(mapperContext);
  }

  private static class NewRecordReader extends
      org.apache.hadoop.mapreduce.RecordReader {
    private final SimpleInput in;

    private NewRecordReader(SimpleInput in) {
      this.in = in;
    }

    @Override
    public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
        TaskAttemptContext context) throws IOException,
        InterruptedException {
      in.initializeNewRecordReader(split, context);
    }

    @Override
    public boolean nextKeyValue() throws IOException,
        InterruptedException {
      return in.hasNext();
    }

    @Override
    public Object getCurrentKey() throws IOException,
        InterruptedException {
      return in.getNextKey();
    }

    @Override
    public Object getCurrentValue() throws IOException,
        InterruptedException {
      return in.getNextValues().iterator().next();
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
      return in.getProgress();
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  private static class OldRecordReader implements RecordReader {
    private final SimpleInput simpleInput;

    private OldRecordReader(SimpleInput simpleInput) {
      this.simpleInput = simpleInput;
    }

    @Override
    public boolean next(Object key, Object value) throws IOException {
      simpleInput.setKey(key);
      simpleInput.setValue(value);
      try {
        return simpleInput.hasNext();
      } catch (InterruptedException ie) {
        throw new IOException(ie);
      }
    }

    @Override
    public Object createKey() {
      return simpleInput.getOldRecordReader().createKey();
    }

    @Override
    public Object createValue() {
      return simpleInput.getOldRecordReader().createValue();
    }

    @Override
    public long getPos() throws IOException {
      return simpleInput.getOldRecordReader().getPos();
    }

    @Override
    public void close() throws IOException {
      simpleInput.close();
    }

    @Override
    public float getProgress() throws IOException {
      try {
        return simpleInput.getProgress();
      } catch (InterruptedException ie) {
        throw new IOException(ie);
      }
    }
  }

  private static class OldOutputCollector 
  implements OutputCollector {
    private final Output output;
    
    OldOutputCollector(Output output) {
      this.output = output;
    }

    public void collect(Object key, Object value) throws IOException {
      try {
        output.write(key, value);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupt exception", ie);
      }
    }
  }

  private class NewOutputCollector
    extends org.apache.hadoop.mapreduce.RecordWriter {
    private final Output out;

    NewOutputCollector(Output out) throws IOException {
      this.out = out;
    }

    @Override
    public void write(Object key, Object value) throws IOException, InterruptedException {
      out.write(key, value);
    }

    @Override
    public void close(TaskAttemptContext context
                      ) throws IOException, InterruptedException {
      out.close();
    }
  }

  @Override
  public void localizeConfiguration(JobConf jobConf) 
      throws IOException, InterruptedException {
    super.localizeConfiguration(jobConf);
    jobConf.setBoolean(JobContext.TASK_ISMAP, true);
  }
  
  @Override
  public TezCounter getOutputRecordsCounter() {
    return reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);
  }

  @Override
  public TezCounter getInputRecordsCounter() {
    return reporter.getCounter(TaskCounter.MAP_INPUT_RECORDS);

  }
}
