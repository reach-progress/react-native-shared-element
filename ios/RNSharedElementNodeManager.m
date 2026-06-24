//
//  RCTMagicMoveCloneDataManager.m
//  react-native-shared-element
//

#import <UIKit/UIKit.h>
#import "RNSharedElementNodeManager.h"

#define DebugLog(...) (void)0

@implementation RNSharedElementNodeManager
{
  NSMutableDictionary* _items;
}

- (instancetype)init
{
  _items = [[NSMutableDictionary alloc]init];
  return self;
}

- (RNSharedElementNode*) acquire:(NSNumber*) reactTag view:(UIView*)view isParent:(BOOL)isParent debugName:(NSString*)debugName
{
  @synchronized(_items)
  {
    RNSharedElementNode* node = [_items objectForKey:reactTag];
    if (node != nil) {
      if (debugName.length) node.debugName = debugName;
      node.refCount = node.refCount + 1;
      DebugLog(@"RNSharedElementNodeManager: acquire existing reactTag=%@ refCount=%ld",
               reactTag,
               node.refCount);
      return node;
    }
    node = [[RNSharedElementNode alloc]init:reactTag view:view isParent:isParent debugName:debugName];
    [_items setObject:node forKey:reactTag];
    DebugLog(@"RNSharedElementNodeManager: acquire new reactTag=%@ isParent=%@ view=%@",
             reactTag,
             isParent ? @"YES" : @"NO",
             view ? @"YES" : @"NO");
    return node;
  }
}

- (long) release:(RNSharedElementNode*) node
{
  @synchronized(_items)
  {
    node.refCount = node.refCount - 1;
    if (node.refCount == 0) {
      RNSharedElementNode* dictItem = [_items objectForKey:node.reactTag];
      if (dictItem == node) {
        [_items removeObjectForKey:node.reactTag];
      }
    }
    DebugLog(@"RNSharedElementNodeManager: release reactTag=%@ refCount=%ld",
             node.reactTag,
             node.refCount);
    return node.refCount;
  }
}

@end
