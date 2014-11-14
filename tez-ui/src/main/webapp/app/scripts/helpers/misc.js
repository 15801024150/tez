/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

App.Helpers.misc = {
  getStatusClassForEntity: function(dag) {
    var st = dag.get('status');
    switch(st) {
      case 'FAILED':
        return 'failed';
      case 'KILLED':
        return 'killed';
      case 'RUNNING':
        return 'running';
      case 'ERROR':
        return 'error';
      case 'SUCCEEDED':
        var counterGroups = dag.get('counterGroups');
        var numFailedTasks = this.getCounterValueForDag(counterGroups,
          dag.get('id'), 'org.apache.tez.common.counters.DAGCounter',
          'NUM_FAILED_TASKS'
        ); 

        if (numFailedTasks > 0) {
          return 'warning';
        }

        return 'success';
      default:
        return 'submitted';
    }
  },

	getCounterValueForDag: function(counterGroups, dagID, counterGroupName, counterName) {
		if (!counterGroups) {
			return 0;
		}

		var cgName = dagID + '/' + counterGroupName;
		var cg = 	counterGroups.findBy('id', cgName);
		if (!cg) {
			return 0;
		}
		var counters = cg.get('counters');
		if (!counters) {
			return 0;
		}
		
		var counter = counters.findBy('id', cgName + '/' + counterName);
		if (!counter) return 0;

		return counter.get('value');
	},

  isValidDagStatus: function(status) {
    return $.inArray(status, ['SUBMITTED', 'INITING', 'RUNNING', 'SUCCEEDED',
      'KILLED', 'FAILED', 'ERROR']) != -1;
  },

  isValidTaskStatus: function(status) {
    return $.inArray(status, ['RUNNING', 'SUCCEEDED', 'FAILED', 'KILLED']) != -1;
  },

  dagStatusUIOptions: [
    { label: 'All', id: null },
    { label: 'Submitted', id: 'SUBMITTED' },
    { label: 'Running', id: 'RUNNING' },
    { label: 'Succeeded', id: 'SUCCEEDED' },
    { label: 'Failed', id: 'FAILED' },
    { label: 'Killed', id: 'KILLED' },
    { label: 'Error', id: 'ERROR' },
  ],

  vertexStatusUIOptions: [
    { label: 'All', id: null },
    { label: 'Running', id: 'RUNNING' },
    { label: 'Succeeded', id: 'SUCCEEDED' },
    { label: 'Failed', id: 'FAILED' },
    { label: 'Killed', id: 'KILLED' },
    { label: 'Error', id: 'ERROR' },
  ],

  taskStatusUIOptions: [
    { label: 'All', id: null },
    { label: 'Running', id: 'RUNNING' },
    { label: 'Succeeded', id: 'SUCCEEDED' },
    { label: 'Failed', id: 'FAILED' },
    { label: 'Killed', id: 'KILLED' },
  ],

  defaultQueryParamsConfig: {
    refreshModel: true,
    replace: true
  }

}