'use strict';

/**
 * Copyright 2016 Signal K and Fabian Tollenaar <fabian@signalk.org>.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//var debug = require('debug')('signalk-parser-nmea0183/MWV');
var utils = require('@signalk/nmea0183-utilities');

function convertToWindAngle(angle) {
  var numAngle = utils.float(angle) % 360;
  if (numAngle > 180 && numAngle <= 360) {
    return numAngle - 360;
  }
  return numAngle;
}

module.exports = function (parser, input) {
  try {
    var id = input.id,
        sentence = input.sentence,
        parts = input.parts,
        tags = input.tags;


    if (!parts[4] || parts[4].toUpperCase() !== 'A') {
      return Promise.resolve(null);
    }

    var wsu = parts[3].toUpperCase();

    if (wsu === 'K') {
      wsu = 'kph';
    } else if (wsu === 'N') {
      wsu = 'knots';
    } else {
      wsu = 'ms';
    }

    var angle = convertToWindAngle(parts[0]);
    var speed = utils.transform(parts[2], wsu, 'ms');
    var valueType = parts[1].toUpperCase() == 'R' ? 'Apparent' : 'True';
    var angleType = parts[1].toUpperCase() == 'R' ? 'Apparent' : 'TrueWater';

    var delta = {
      updates: [{
        source: tags.source,
        timestamp: tags.timestamp,
        values: [{
          path: 'environment.wind.speed' + valueType,
          value: speed
        }, {
          path: 'environment.wind.angle' + angleType,
          value: utils.transform(angle, 'deg', 'rad')
        }]
      }]
    };

    return Promise.resolve({ delta: delta });
  } catch (e) {
    debug('Try/catch failed: ' + e.message);
    return Promise.reject(e);
  }
};