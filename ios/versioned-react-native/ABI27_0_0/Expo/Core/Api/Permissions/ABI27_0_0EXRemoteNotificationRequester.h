// Copyright 2016-present 650 Industries. All rights reserved.

#import "ABI27_0_0EXPermissions.h"

@interface ABI27_0_0EXRemoteNotificationRequester : NSObject <ABI27_0_0EXPermissionRequester, ABI27_0_0EXPermissionRequesterDelegate>

+ (NSDictionary *)permissions;

@end
