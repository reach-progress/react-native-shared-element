//
//  RNSharedElementNodeManager.h
//  react-native-shared-element
//

#ifndef RNSharedElementNodeManager_h
#define RNSharedElementNodeManager_h

#import "RNSharedElementNode.h"

@interface RNSharedElementNodeManager : NSObject

- (instancetype)init;
- (RNSharedElementNode*) acquire:(NSNumber*) reactTag view:(UIView*)view isParent:(BOOL)isParent;
- (RNSharedElementNode*) acquireSnapshot:(NSString*)key;
- (BOOL)captureSnapshot:(NSString*)key node:(RNSharedElementNode*)node;
- (void)clearSnapshotsWithPrefix:(NSString*)prefix;
- (long) release:(RNSharedElementNode*) node;

@end

#endif
