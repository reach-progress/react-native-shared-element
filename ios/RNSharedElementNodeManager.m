//
//  RCTMagicMoveCloneDataManager.m
//  react-native-shared-element
//

#import <UIKit/UIKit.h>
#import "RNSharedElementNodeManager.h"

@implementation RNSharedElementNodeManager
{
  NSMutableDictionary* _items;
  NSMutableDictionary<NSString*, RNSharedElementSnapshot*>* _snapshots;
}

- (instancetype)init
{
  _items = [[NSMutableDictionary alloc]init];
  _snapshots = [[NSMutableDictionary alloc]init];
  return self;
}

- (RNSharedElementNode*)acquireSnapshot:(NSString*)key
{
  if (key.length == 0) return nil;
  @synchronized(_snapshots)
  {
    RNSharedElementSnapshot* snapshot = [_snapshots objectForKey:key];
    return snapshot == nil ? nil : [[RNSharedElementNode alloc]initWithSnapshot:snapshot];
  }
}

- (BOOL)hasSnapshot:(NSString*)key
{
  if (key.length == 0) return NO;
  @synchronized(_snapshots)
  {
    return [_snapshots objectForKey:key] != nil;
  }
}

- (BOOL)captureSnapshot:(NSString*)key node:(RNSharedElementNode*)node
{
  if (key.length == 0 || node == nil) return NO;
  RNSharedElementSnapshot* snapshot = [node captureSnapshot];
  @synchronized(_snapshots)
  {
    if (snapshot == nil) {
      [_snapshots removeObjectForKey:key];
      return NO;
    }
    [_snapshots setObject:snapshot forKey:key];
    return YES;
  }
}

- (void)clearSnapshotsWithPrefix:(NSString*)prefix
{
  if (prefix.length == 0) return;
  @synchronized(_snapshots)
  {
    NSArray<NSString*>* keys = [_snapshots.allKeys copy];
    for (NSString* key in keys) {
      if ([key hasPrefix:prefix]) [_snapshots removeObjectForKey:key];
    }
  }
}

- (RNSharedElementNode*) acquire:(NSNumber*) reactTag view:(UIView*)view isParent:(BOOL)isParent
{
  @synchronized(_items)
  {
    RNSharedElementNode* node = [_items objectForKey:reactTag];
    if (node != nil) {
      node.refCount = node.refCount + 1;
      return node;
    }
    node = [[RNSharedElementNode alloc]init:reactTag view:view isParent:isParent];
    [_items setObject:node forKey:reactTag];
    return node;
  }
}

- (long) release:(RNSharedElementNode*) node
{
  if (node == nil) return 0;
  RNSharedElementNode* hideNode = nil;
  long refCount = 0;
  @synchronized(_items)
  {
    node.refCount = node.refCount - 1;
    refCount = node.refCount;
    if (node.refCount == 0) {
      if (node.isSnapshot) {
        node.hideRefCount = 0;
        hideNode = node.hideNode;
        node.hideNode = nil;
      }
      RNSharedElementNode* dictItem = node.reactTag == nil ? nil : [_items objectForKey:node.reactTag];
      if (dictItem == node) {
        [_items removeObjectForKey:node.reactTag];
      }
    }
  }
  if (hideNode != nil) [self release:hideNode];
  return refCount;
}

@end
