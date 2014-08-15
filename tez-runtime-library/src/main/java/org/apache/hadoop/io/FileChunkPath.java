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

package org.apache.hadoop.io;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.fs.Path;

@Private
public class FileChunkPath extends Path {

  private long offset = -1;
  private long size = -1;
  private boolean hasOffset = false;

  public FileChunkPath(String pathString, long offset, long length) throws IllegalArgumentException {
    super(pathString);
    this.offset = offset;
    this.size = length;
    this.hasOffset = true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null) {
      return false;
    }

    boolean isPathEqual = super.equals(o);
    if (!isPathEqual || !(o instanceof FileChunkPath)) {
      return isPathEqual;
    }

    FileChunkPath that = (FileChunkPath) o;

    if (this.offset != that.offset || this.size != that.size) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + (int) (offset ^ (offset >>> 32));
    result = 31 * result + (int) (size ^ (size >>> 32));
    return result;
  }

  @Override
  public int compareTo(Object o) {
    int c;

    c = super.compareTo(o);
    if (c != 0 || !(o instanceof FileChunkPath)) {
      return c;
    }

    long lc;
    FileChunkPath that = (FileChunkPath)o;

    if ((lc = this.offset - that.offset) != 0) {
      return lc > 0 ? 1 : -1;
    }

    if ((lc = this.size - that.size) != 0) {
      return lc > 0 ? 1 : -1;
    }

    return 0;
  }

  public long getOffset() {
    return offset;
  }

  public long getLength() {
    return size;
  }

  public boolean hasOffset() {
    return hasOffset;
  }
}
