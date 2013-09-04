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

package org.apache.tez.engine.newapi;

public class UserEvent extends Event {

  /**
   * Index(i) of the i-th (physical) Input or Output that generated an Event.
   * For a Processor-generated event, this is ignored.
   */
  private final int sourceIndex;

  private int targetIndex;

  private final byte[] userPayload;

  public UserEvent(int index,
      byte[] userPayload) {
    super(EventType.USER);
    this.userPayload = userPayload;
    this.sourceIndex = index;
  }

  public UserEvent(byte[] userPayload) {
    this(0, userPayload);
  }

  public byte[] getUserPayload() {
    return userPayload;
  }

  public int getSourceIndex() {
    return sourceIndex;
  }

  public int getTargetIndex() {
    return targetIndex;
  }

  void setTargetIndex(int targetIndex) {
    this.targetIndex = targetIndex;
  }


}
