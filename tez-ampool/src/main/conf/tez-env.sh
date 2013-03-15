# Copyright 2011 The Apache Software Foundation
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Set Tez-specific environment variables here.

# The only required environment variables are JAVA_HOME and HADOOP_HOME.  All others are
# optional.

# The java implementation to use.
export JAVA_HOME=${JAVA_HOME}

# Set HADOOP_HOME as needed by Tez
#export HADOOP_HOME=

# The maximum amount of heap to use, in MB. Default is 1024.
#export TEZ_HEAPSIZE=

# Where log files are stored.  $TEZ_HOME/logs by default.
#export TEZ_LOG_DIR=${TEZ_LOG_DIR}/$USER

# The directory where pid files are stored. /tmp by default.
# NOTE: this should be set to a directory that can only be written to by
#       the user that will run the tez daemons.  Otherwise there is the
#       potential for a symlink attack.
export TEZ_PID_DIR=${TEZ_PID_DIR}

# A string representing this instance of tez. $USER by default.
export TEZ_IDENT_STRING=$USER
