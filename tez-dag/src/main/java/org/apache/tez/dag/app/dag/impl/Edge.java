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

package org.apache.tez.dag.app.dag.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.app.dag.EdgeManager;
import org.apache.tez.dag.app.dag.Task;
import org.apache.tez.dag.app.dag.Vertex;
import org.apache.tez.dag.app.dag.event.TaskAttemptEventOutputFailed;
import org.apache.tez.dag.app.dag.event.TaskEventAddTezEvent;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.dag.records.TezTaskID;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.api.events.DataMovementEvent;
import org.apache.tez.runtime.api.events.InputFailedEvent;
import org.apache.tez.runtime.api.events.InputReadErrorEvent;
import org.apache.tez.runtime.api.impl.EventMetaData;
import org.apache.tez.runtime.api.impl.InputSpec;
import org.apache.tez.runtime.api.impl.OutputSpec;
import org.apache.tez.runtime.api.impl.TezEvent;
import org.apache.tez.runtime.api.impl.EventMetaData.EventProducerConsumerType;

public class Edge {

  private EdgeProperty edgeProperty;
  private EdgeManager edgeManager;
  @SuppressWarnings("rawtypes")
  private EventHandler eventHandler;
  private AtomicBoolean bufferEvents = new AtomicBoolean(false);
  private List<TezEvent> destinationEventBuffer = new ArrayList<TezEvent>();
  private List<TezEvent> sourceEventBuffer = new ArrayList<TezEvent>();
  private Vertex sourceVertex;
  private Vertex destinationVertex; // this may end up being a list for shared edge

  @SuppressWarnings("rawtypes")
  public Edge(EdgeProperty edgeProperty, EventHandler eventHandler) {
    this.edgeProperty = edgeProperty;
    this.eventHandler = eventHandler;
    switch (edgeProperty.getDataMovementType()) {
    case ONE_TO_ONE:
      edgeManager = new OneToOneEdgeManager();
      break;
    case BROADCAST:
      edgeManager = new BroadcastEdgeManager();
      break;
    case SCATTER_GATHER:
      edgeManager = new ScatterGatherEdgeManager();
      break;
    default:
      String message = "Unknown edge data movement type: "
          + edgeProperty.getDataMovementType();
      throw new TezUncheckedException(message);
    }
  }
  
  public EdgeProperty getEdgeProperty() {
    return this.edgeProperty;
  }
  
  public EdgeManager getEdgeManager() {
    return this.edgeManager;
  }
  
  public void setEdgeManager(EdgeManager edgeManager) {
    if(edgeManager == null) {
      throw new TezUncheckedException("Edge manager cannot be null");
    }
    this.edgeManager = edgeManager;
  }
  
  public void setSourceVertex(Vertex sourceVertex) {
    if (this.sourceVertex != null && this.sourceVertex != sourceVertex) {
      throw new TezUncheckedException("Source vertex exists: "
          + sourceVertex.getName());
    }
    this.sourceVertex = sourceVertex;
  }

  public void setDestinationVertex(Vertex destinationVertex) {
    if (this.destinationVertex != null
        && this.destinationVertex != destinationVertex) {
      throw new TezUncheckedException("Destination vertex exists: "
          + destinationVertex.getName());
    }
    this.destinationVertex = destinationVertex;
  }

  public InputSpec getDestinationSpec(int destinationTaskIndex) {
    return new InputSpec(sourceVertex.getName(),
        edgeProperty.getEdgeDestination(),
        edgeManager.getNumDestinationTaskInputs(sourceVertex.getTotalTasks(),
            destinationTaskIndex));
  }

  public OutputSpec getSourceSpec(int sourceTaskIndex) {
    return new OutputSpec(destinationVertex.getName(),
        edgeProperty.getEdgeSource(), edgeManager.getNumSourceTaskOutputs(
            destinationVertex.getTotalTasks(), sourceTaskIndex));
  }
  
  public void startEventBuffering() {
    bufferEvents.set(true);
  }
  
  public void stopEventBuffering() {
    // assume only 1 entity will start and stop event buffering
    bufferEvents.set(false);
    for(TezEvent event : destinationEventBuffer) {
      sendTezEventToDestinationTasks(event);
    }
    destinationEventBuffer.clear();
    for(TezEvent event : sourceEventBuffer) {
      sendTezEventToSourceTasks(event);
    }
    sourceEventBuffer.clear();
  }
  
  @SuppressWarnings("unchecked")
  public void sendTezEventToSourceTasks(TezEvent tezEvent) {
    if (!bufferEvents.get()) {
      switch (tezEvent.getEventType()) {
      case INPUT_READ_ERROR_EVENT:
        InputReadErrorEvent event = (InputReadErrorEvent) tezEvent.getEvent();
        TezTaskAttemptID destAttemptId = tezEvent.getSourceInfo()
            .getTaskAttemptID();
        int destTaskIndex = destAttemptId.getTaskID().getId();
        int srcTaskIndex = edgeManager.routeEventToSourceTasks(destTaskIndex,
            event);
        int numConsumers = edgeManager.getDestinationConsumerTaskNumber(
            srcTaskIndex, destinationVertex.getTotalTasks());
        Task srcTask = sourceVertex.getTask(srcTaskIndex);
        if (srcTask == null) {
          throw new TezUncheckedException("Unexpected null task." +
              " sourceVertex=" + sourceVertex.getVertexId() +
              " srcIndex = " + srcTaskIndex +
              " destAttemptId=" + destAttemptId +
              " destIndex=" + destTaskIndex + 
              " edgeManager=" + edgeManager.getClass().getName());
        }
        TezTaskID srcTaskId = srcTask.getTaskId();
        int taskAttemptIndex = event.getVersion();
        TezTaskAttemptID srcTaskAttemptId = TezTaskAttemptID.getInstance(srcTaskId,
            taskAttemptIndex);
        eventHandler.handle(new TaskAttemptEventOutputFailed(srcTaskAttemptId,
            tezEvent, numConsumers));
        break;
      default:
        throw new TezUncheckedException("Unhandled tez event type: "
            + tezEvent.getEventType());
      }
    } else {
      sourceEventBuffer.add(tezEvent);
    }
  }
  
  public void sendTezEventToDestinationTasks(TezEvent tezEvent) {
    if (!bufferEvents.get()) {
      List<Integer> destTaskIndices = new ArrayList<Integer>();
      boolean isDataMovementEvent = true;
      switch (tezEvent.getEventType()) {
      case INPUT_FAILED_EVENT:
        isDataMovementEvent = false;
      case DATA_MOVEMENT_EVENT:
        Event event = tezEvent.getEvent();
        TezTaskAttemptID sourceAttemptId = tezEvent.getSourceInfo().getTaskAttemptID();
        int sourceTaskIndex = sourceAttemptId.getTaskID().getId();
        if (isDataMovementEvent) {
          edgeManager.routeEventToDestinationTasks((DataMovementEvent) event,
              sourceTaskIndex, destinationVertex.getTotalTasks(),
              destTaskIndices);
        } else {
          edgeManager.routeEventToDestinationTasks((InputFailedEvent) event,
              sourceTaskIndex, destinationVertex.getTotalTasks(),
              destTaskIndices);
        }
        for(Integer destTaskIndex : destTaskIndices) {
          EventMetaData destMeta = new EventMetaData(EventProducerConsumerType.INPUT, 
              destinationVertex.getName(), 
              sourceVertex.getName(), 
              null); // will be filled by Task when sending the event. Is it needed?
          if (isDataMovementEvent) {
            destMeta.setIndex(((DataMovementEvent)event).getTargetIndex());
          } else {
            destMeta.setIndex(((InputFailedEvent)event).getTargetIndex());
          }
          tezEvent.setDestinationInfo(destMeta);
          Task destTask = destinationVertex.getTask(destTaskIndex);
          if (destTask == null) {
            throw new TezUncheckedException("Unexpected null task." +
                " sourceVertex=" + sourceVertex.getVertexId() +
                " srcIndex = " + sourceTaskIndex +
                " destAttemptId=" + destinationVertex.getVertexId() +
                " destIndex=" + destTaskIndex + 
                " edgeManager=" + edgeManager.getClass().getName());
          }
          TezTaskID destTaskId = destTask.getTaskId();
          sendEventToTask(destTaskId, tezEvent);
        }        
        break;
      default:
        throw new TezUncheckedException("Unhandled tez event type: "
            + tezEvent.getEventType());
      }
    } else {
      destinationEventBuffer.add(tezEvent);
    }
  }
  
  @SuppressWarnings("unchecked")
  private void sendEventToTask(TezTaskID taskId, TezEvent tezEvent) {
    eventHandler.handle(new TaskEventAddTezEvent(taskId, tezEvent));
  }
  
}
