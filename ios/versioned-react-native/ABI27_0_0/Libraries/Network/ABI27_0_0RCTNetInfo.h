/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import <SystemConfiguration/SystemConfiguration.h>

#import <ReactABI27_0_0/ABI27_0_0RCTEventEmitter.h>

@interface ABI27_0_0RCTNetInfo : ABI27_0_0RCTEventEmitter

- (instancetype)initWithHost:(NSString *)host;

@end
