/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import <UIKit/UIKit.h>

#import <ReactABI27_0_0/ABI27_0_0RCTSurfaceStage.h>
#import <ReactABI27_0_0/ABI27_0_0RCTSurfaceView.h>

@class ABI27_0_0RCTSurfaceRootView;

NS_ASSUME_NONNULL_BEGIN

@interface ABI27_0_0RCTSurfaceView (Internal)

@property (nonatomic, strong) ABI27_0_0RCTSurfaceRootView *rootView;
@property (nonatomic, assign) ABI27_0_0RCTSurfaceStage stage;

@end

NS_ASSUME_NONNULL_END
