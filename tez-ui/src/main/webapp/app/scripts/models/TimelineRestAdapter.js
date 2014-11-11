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

var typeToPathMap = {
  dag: 'TEZ_DAG_ID',
  vertex: 'TEZ_VERTEX_ID',
  task: 'TEZ_TASK_ID',
  taskAttempt: 'TEZ_TASK_ATTEMPT_ID',
  tezApp: 'TEZ_APPLICATION'
};

App.TimelineRESTAdapter = DS.RESTAdapter.extend({
	namespace: 'ws/v1/timeline',

	pathForType: function(type) {
		return typeToPathMap[type];
	},

});

App.TimelineSerializer = DS.RESTSerializer.extend({
	extractSingle: function(store, primaryType, rawPayload, recordId) {
		// rest serializer expects singular form of model as the root key.
		var payload = {};
		payload[primaryType.typeKey] = rawPayload;
		return this._super(store, primaryType, payload, recordId);
	},

	extractArray: function(store, primaryType, rawPayload) {
		// restserializer expects a plural of the model but TimelineServer returns
		// it in entities.
		var payload = {};
		payload[primaryType.typeKey.pluralize()] = rawPayload.entities;
		return this._super(store, primaryType, payload);
	},

  // normalizes countergroups returns counterGroups and counters.
  normalizeCounterGroupsHelper: function(parentType, parentID, entity) {
    // create empty countergroups if not there - to make code below easier.
    entity.otherinfo.counters = entity.otherinfo.counters || {}
    entity.otherinfo.counters.counterGroups = entity.otherinfo.counters.counterGroups || [];

    var counterGroups = [];
    var counters = [];

    var counterGroupsIDs = entity.otherinfo.counters.counterGroups.map(function(counterGroup) {
      var cg = {
        id: parentID + '/' + counterGroup.counterGroupName,
        name: counterGroup.counterGroupName,
        displayName: counterGroup.counterGroupDisplayName,
        parentID: { // polymorphic requires type and id.
          type: parentType,
          id: parentID
        }
      };
      cg.counters = counterGroup.counters.map(function(counter){
        var c = {
          id: cg.id + '/' + counter.counterName,
          name: counter.counterName,
          displayName: counter.counterDisplayName,
          value: counter.counterValue,
          parentID: cg.id
        };
        counters.push(c);
        return c.id;
      });
      counterGroups.push(cg);
      return cg.id;
    });

    return {
      counterGroups: counterGroups,
      counters: counters,
      counterGroupsIDs: counterGroupsIDs
    }
  }
});


var timelineJsonToDagMap = {
  id: 'entity',
  submittedTime: 'starttime',
  startTime: 'otherinfo.startTime',
  endTime: 'otherinfo.endTime',
  name: 'primaryfilters.dagName.0',
  user: 'primaryfilters.user.0',
  applicationId: 'otherinfo.applicationId',
  status: 'otherinfo.status',
  diagnostics: 'otherinfo.diagnostics',
  counterGroups: 'counterGroups'
};

App.DagSerializer = App.TimelineSerializer.extend({
  _normalizeSingleDagPayload: function(dag) {
    var normalizedCounterGroupData = this.normalizeCounterGroupsHelper('dag', dag.entity, 
      dag);
    dag.counterGroups = normalizedCounterGroupData.counterGroupsIDs;
    delete dag.otherinfo.counters;

    return {
      dag: dag,
      counterGroups: normalizedCounterGroupData.counterGroups,
      counters: normalizedCounterGroupData.counters
    };
  },

  normalizePayload: function(rawPayload){

    if (!!rawPayload.dags) {
      // multiple dags - cames here through _findAll/_findQuery
      var normalizedPayload = {
        dags: [],
        counterGroups: [],
        counters: []
      };
      rawPayload.dags.forEach(function(dag){
        var n = this._normalizeSingleDagPayload(dag);
        normalizedPayload.dags.push(n.dag);
        [].push.apply(normalizedPayload.counterGroups, n.counterGroups);
        [].push.apply(normalizedPayload.counters, n.counters);
      }, this);
      
      // delete so that we dont hang on to the json data.
      delete rawPayload.dags;

      return normalizedPayload;
    } else {
      return this._normalizeSingleDagPayload(rawPayload.dag);
    }
  },

  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToDagMap);
  },
});

var containerIdRegex = /.*(container_.*?)\/.*/;
var nodeIdRegex = /([^\/]*)\//;
var timelineJsonToTaskAttemptMap = {
  id: 'entity',
  startTime: 'otherinfo.startTime',
  endTime: 'otherinfo.endTime',
  status: 'otherinfo.status',
  diagnostics: 'otherinfo.diagnostics',
  counterGroups: 'counterGroups',
  vertexID: 'primaryfilters.TEZ_VERTEX_ID.0',
  dagID: 'primaryfilters.TEZ_DAG_ID.0',
  containerId: { custom: function (source) {
    var inProgressLogsURL = Em.get(source, 'otherinfo.inProgressLogsURL');
    var match = containerIdRegex.exec(inProgressLogsURL);
    return match[1];
  }},
  nodeId: {
    custom: function(source) {
      var inProgressLogsURL = Em.get(source, 'otherinfo.inProgressLogsURL');
      var match = nodeIdRegex.exec(inProgressLogsURL);
      return match[1];
    }
  }
};


App.TaskAttemptSerializer = App.TimelineSerializer.extend({
  _normalizeSingleTaskAttemptPayload: function(taskAttempt) {
    var normalizedCounterGroupData = this.normalizeCounterGroupsHelper('taskAttempt', 
      taskAttempt.entity, taskAttempt);
    taskAttempt.counterGroups = normalizedCounterGroupData.counterGroupsIDs;
    delete taskAttempt.otherinfo.counters;

    return {taskAttempt: taskAttempt, counterGroups: normalizedCounterGroupData.counterGroups,
      counters: normalizedCounterGroupData.counters
    };
  },

  normalizePayload: function(rawPayload){

    if (!!rawPayload.taskAttempts) {
      var normalizedPayload = {
        taskAttempts: [],
        counterGroups: [],
        counters: []
      };
      rawPayload.taskAttempts.forEach(function(taskAttempt){
        var n = this._normalizeSingleTaskAttemptPayload(taskAttempt);
        normalizedPayload.taskAttempts.push(n.taskAttempt); 
        [].push.apply(normalizedPayload.counterGroups, n.counterGroups);
        [].push.apply(normalizedPayload.counters, n.counters);
      }, this);
      
      // delete so that we dont hang on to the json data.
      delete rawPayload.taskAttempts;
      return normalizedPayload;
    } else {
      return this._normalizeSingleTaskAttemptPayload(rawPayload.taskAttempt);
    }
  },

  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToTaskAttemptMap);
  },
});

var timelineJsonToTaskMap = {
  id: 'entity',
  dagID: 'primaryfilters.TEZ_DAG_ID.0',
  startTime: 'otherinfo.startTime',
  vertexID: 'primaryfilters.TEZ_VERTEX_ID.0',
  endTime: 'otherinfo.endTime',
  status: 'otherinfo.status',
  diagnostics: 'otherinfo.diagnostics',
  counterGroups: 'counterGroups',
  vertexID: 'primaryfilters.TEZ_VERTEX_ID.0',
  dagID: 'primaryfilters.TEZ_DAG_ID.0',
  numAttempts: 'relatedentities'
};

App.TaskSerializer = App.TimelineSerializer.extend({
  _normalizeSingleTaskPayload: function(task) {
    var normalizedCounterGroupData = this.normalizeCounterGroupsHelper('task', task.entity, 
      task);
    task.counterGroups = normalizedCounterGroupData.counterGroupsIDs;

    delete task.otherinfo.counters;

    return {
      task: task,
      counterGroups: normalizedCounterGroupData.counterGroups,
      counters: normalizedCounterGroupData.counters
    };
  },

  normalizePayload: function(rawPayload) {
    if (!!rawPayload.tasks) {
      var normalizedPayload = {
        tasks: [],
        counterGroups: [],
        counters: []
      };
      rawPayload.tasks.forEach(function(task){
        var n = this._normalizeSingleTaskPayload(task);
        normalizedPayload.tasks.push(n.task);
        [].push.apply(normalizedPayload.counterGroups, n.counterGroups);
        [].push.apply(normalizedPayload.counters, n.counters);
      }, this);
      
      // delete so that we dont hang on to the json data.
      delete rawPayload.tasks;

      return normalizedPayload;
    } else {
      return this._normalizeSingleTaskPayload(rawPayload.task);
    }
  },

  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToTaskMap);
  },
});

var timelineJsonToVertexMap = {
  id: 'entity',
  name: 'otherinfo.vertexName',
  dagID: 'primaryfilters.TEZ_DAG_ID.0',
  counterGroups: 'counterGroups',

  startTime: 'otherinfo.startTime',
  endTime: 'otherinfo.endTime',

  status: 'otherinfo.status',
  diagnostics: 'otherinfo.diagnostics',

  failedTasks: 'otherinfo.numFailedTasks',
  sucessfulTasks: 'otherinfo.numSucceededTasks',
  numTasks: 'otherinfo.numTasks',
  killedTasks: 'otherinfo.numKilledTasks',
};

App.VertexSerializer = App.TimelineSerializer.extend({
  _normalizeSingleVertexPayload: function(vertex) {
    var normalizedCounterGroupData = this.normalizeCounterGroupsHelper('vertex', vertex.entity, 
      vertex);
    vertex.counterGroups = normalizedCounterGroupData.counterGroupsIDs;

    delete vertex.otherinfo.counters;

    return {
      vertex: vertex,
      counterGroups: normalizedCounterGroupData.counterGroups,
      counters: normalizedCounterGroupData.counters
    };
  },

  normalizePayload: function(rawPayload) {
    if (!!rawPayload.vertices) {
      var normalizedPayload = {
        vertices: [],
        counterGroups: [],
        counters: []
      };
      rawPayload.vertices.forEach(function(vertex){
        var n = this._normalizeSingleVertexPayload(vertex);
        normalizedPayload.vertices.push(n.vertex);
        [].push.apply(normalizedPayload.counterGroups, n.counterGroups);
        [].push.apply(normalizedPayload.counters, n.counters);
      }, this);
      
      // delete so that we dont hang on to the json data.
      delete rawPayload.vertices;

      return normalizedPayload;
    } else {
      return this._normalizeSingleVertexPayload(rawPayload.vertex);
    }
  },

  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToVertexMap);
  },
});

var timelineJsonToAppDetailMap = {
  id: 'appId',
  attemptId: 'currentAppAttemptId',

  name: 'name',
  queue: 'queue',
  user: 'user',
  type: 'type',

  startedTime: 'startedTime',
  elapsedTime: 'elapsedTime',
  finishedTime: 'finishedTime',
  submittedTime: 'submittedTime',

  appState: 'appState',

  finalAppStatus: 'finalAppStatus',
  diagnostics: 'otherinfo.diagnostics',
};

App.AppDetailSerializer = App.TimelineSerializer.extend({
  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToAppDetailMap);
  },
});

var timelineJsonToTezAppMap = {
  id: 'entity',

  appId: 'appId',

  entityType: 'entitytype',

  startedTime: 'startedTime',
  domain: 'domain',

  dags: 'relatedentities.TEZ_DAG_ID',
  configs: 'configs'
};

App.TezAppSerializer = App.TimelineSerializer.extend({
  _normalizeSinglePayload: function(rawPayload){
    var configs = rawPayload.otherinfo.config,
    appId = rawPayload.entity.substr(4),
    kVData = [],
    id;

    rawPayload.appId = appId;
    rawPayload.configs = [];

    for(var key in configs) {
      id = appId + key;
      rawPayload.configs.push(id);
      kVData.push({
        id: id,
        key: key,
        value: configs[key]
      });
    }

    return {
      tezApp: rawPayload,
      kVData: kVData
    };
  },
  normalizePayload: function(rawPayload) {
    if (!!rawPayload.tezApps) {
      var normalizedPayload = {
        tezApps: [],
        kVData: []
      },
      push = Array.prototype.push;
      rawPayload.tezApps.forEach(function(app){
        var n = this._normalizeSinglePayload(app);
        normalizedPayload.tezApps.push(n.tezApp);
        push.apply(normalizedPayload.kVData,n.kVData);
      });
      return normalizedPayload;
    }
    else {
      return this._normalizeSinglePayload(rawPayload.tezApp)
    }
  },
  normalize: function(type, hash, prop) {
    return Em.JsonMapper.map(hash, timelineJsonToTezAppMap);
  },
});
