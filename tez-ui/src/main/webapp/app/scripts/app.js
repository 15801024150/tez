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

var App = window.App = Em.Application.createWithMixins(Bootstrap, {
  // Basic logging, e.g. "Transitioned into 'post'"
  LOG_TRANSITIONS: true,

  // Extremely detailed logging, highlighting every internal
  // step made while transitioning into a route, including
  // `beforeModel`, `model`, and `afterModel` hooks, and
  // information about redirects and aborted transitions
  LOG_TRANSITIONS_INTERNAL: true,

  env: {
    standalone: window.parent === window,
    timelineBaseUrl: "http://localhost:8188",
    RMWebUrl: "http://localhost:8088"
  }
});

App.Helpers = Em.Namespace.create();
App.Mappers = Em.Namespace.create();

Ember.Application.initializer({
  name: "initApp",

  initialize: function(container, application) {
    application.ApplicationAdapter = App.TimelineRESTAdapter.extend({
      host: App.env.timelineBaseUrl
    });
    application.ApplicationSerializer = App.TimelineSerializer.extend();

    application.AppDetailAdapter = DS.RESTAdapter.extend({
      namespace: 'ws/v1/applicationhistory',
      host: App.env.timelineBaseUrl,
      pathForType: function() {
        return "apps";
      },
    });

  }
});

/* Order and include */
/* TODO: cleanup */
require('scripts/translations');
require('scripts/mixins/*');
require('scripts/helpers/*');
require('scripts/router');
require('scripts/store');
require('scripts/views/**/*');
require('scripts/models/**/*');
require('scripts/mappers/server_data_mapper.js');
require('scripts/mappers/**/*');
require('scripts/controllers/**/*');
require('scripts/components/*');
require('scripts/adapters/*');
