/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * @providesModule dismissKeyboard
 *
 * This function dismisses the currently-open keyboard, if any
 */
'use strict';

var TextInputState = require('../Components/TextInput/TextInputState');

function dismissKeyboard() {
  TextInputState.blurTextInput(TextInputState.currentlyFocusedField());
}

module.exports = dismissKeyboard;
