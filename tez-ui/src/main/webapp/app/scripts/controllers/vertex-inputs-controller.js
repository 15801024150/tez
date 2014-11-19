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

App.VertexInputsController = Em.ObjectController.extend(App.PaginatedContentMixin, {
  needs: 'vertex',

  loadEntities: function() {
    var content = this.get('inputs').content;
    this.set('entities', content);
    this.set('inputsAvailable', content.length > 0);
    this.set('loading', false);
  },

  actions : {
    filterUpdated: function(filterID, value) {
      // any validations required goes here.
      if (!!value) {
        this.set(filterID, value);
      } else {
        this.set(filterID, null);
      }
      this.loadData();
    }
  },

  columns: function() {
    var nameCol = App.ExTable.ColumnDefinition.create({
      headerCellName: 'Name',
      contentPath: 'inputName',
    });

    var classCol = App.ExTable.ColumnDefinition.create({
      headerCellName: 'Class',
      contentPath: 'inputClass',
    });

    var initializerCol = App.ExTable.ColumnDefinition.create({
      headerCellName: 'Initializer',
      contentPath: 'inputInitializer',
    });

    var configCol = App.ExTable.ColumnDefinition.create({
      headerCellName: 'Configurations',
      tableCellViewClass: Em.Table.TableCell.extend({
        template: Em.Handlebars.compile(
          "{{#if view.cellContent.count}}\
            {{#link-to 'vertexInput.configs' view.cellContent.id class='ember-table-content'}}View Configurations{{/link-to}}\
          {{else}}\
            <span class='ember-table-content'>Not Available</span>\
          {{/if}}")
      }),
      getCellContent: function(row) {
        return {
          count: row.get('configs.content.length'),
          id: row.get('id')
        };
      }
    });

    return [nameCol, classCol, initializerCol, configCol];
  }.property(),
});