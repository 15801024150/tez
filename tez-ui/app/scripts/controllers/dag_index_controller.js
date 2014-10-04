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

App.DagIndexController = Em.ObjectController.extend({
	controllerName: "DagsController",

	counterTableColumns: function() {
		var groupColumn = Em.Table.ColumnDefinition.create({
      textAlign: 'text-align-left',
      headerCellName: 'Group',
      getCellContent: function(row) {
      	return row.get('counterGroup');
      }
    });

		var nameColumn = Em.Table.ColumnDefinition.create({
      textAlign: 'text-align-left',
      headerCellName: 'Counter Name',
      getCellContent: function(row) {
      	return row.get('name');
      }
    });

		var valueColumn = Em.Table.ColumnDefinition.create({
      textAlign: 'text-align-left',
      headerCellName: 'Counter Name',
      getCellContent: function(row) {
      	return row.get('value');
      }
    });

    return [groupColumn, nameColumn, valueColumn];
	}.property(),

	counterContent: function() {
		var allCounters = [];
		this.get('content').get('counterGroups').forEach(function(cg){
			cg.get('counters').forEach(function(counter){
				allCounters.push({
					counterGroup: cg.get('displayName'),
					name: counter.get('displayName'),
					value: counter.get('value')
				});
			});
		});
		return allCounters;
	}.property(),
});