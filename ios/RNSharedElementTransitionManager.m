//
//  RNSharedElementTransitionManager.m
//  react-native-shared-element
//

#import <React/RCTBridge.h>
#import <React/RCTUIManager.h>
#import "RNSharedElementTransitionManager.h"
#import "RNSharedElementTransition.h"
#import "RNSharedElementNodeManager.h"
#import "RNSharedElementTypes.h"

static NSString* const RNSharedElementLocalBuildTimestamp = @"2026-07-20T02:20:25Z";

@class RNSharedElementTransitionWaitProbe;

typedef void (^RNSharedElementTransitionWaitProbeComplete)(
  RNSharedElementTransitionWaitProbe* probe
);

@interface RNSharedElementTransitionWaitProbe : NSObject <RNSharedElementDelegate>

- (instancetype)initWithNodeManager:(RNSharedElementNodeManager*)nodeManager
                          startNode:(RNSharedElementNode*)startNode
                            endNode:(RNSharedElementNode*)endNode
                            resolve:(RCTPromiseResolveBlock)resolve
                         onComplete:(RNSharedElementTransitionWaitProbeComplete)onComplete;
- (void)startWithTimeoutMs:(NSInteger)timeoutMs;

@end

@implementation RNSharedElementTransitionWaitProbe
{
  RNSharedElementNodeManager* _nodeManager;
  RNSharedElementNode* _startNode;
  RNSharedElementNode* _endNode;
  RNSharedElementStyle* _startStyle;
  RNSharedElementStyle* _endStyle;
  RNSharedElementContent* _startContent;
  RNSharedElementContent* _endContent;
  RCTPromiseResolveBlock _resolve;
  RNSharedElementTransitionWaitProbeComplete _onComplete;
  CFTimeInterval _startedAt;
  BOOL _finished;
}

- (instancetype)initWithNodeManager:(RNSharedElementNodeManager*)nodeManager
                          startNode:(RNSharedElementNode*)startNode
                            endNode:(RNSharedElementNode*)endNode
                            resolve:(RCTPromiseResolveBlock)resolve
                         onComplete:(RNSharedElementTransitionWaitProbeComplete)onComplete
{
  if ((self = [super init])) {
    _nodeManager = nodeManager;
    _startNode = startNode;
    _endNode = endNode;
    _resolve = [resolve copy];
    _onComplete = [onComplete copy];
    _startedAt = CACurrentMediaTime();
    _finished = NO;
  }
  return self;
}

- (BOOL)hasAnyNode
{
  return (_startNode != nil) || (_endNode != nil);
}

- (BOOL)isRenderableForNode:(RNSharedElementNode*)node
                      style:(RNSharedElementStyle*)style
                    content:(RNSharedElementContent*)content
{
  if (node == nil) return YES;
  return (style != nil) && (content != nil);
}

- (NSDictionary*)buildResult:(NSString*)reason
{
  const BOOL hasStartNode = (_startNode != nil);
  const BOOL hasEndNode = (_endNode != nil);
  const BOOL startStyleReady = (_startStyle != nil);
  const BOOL startContentReady = (_startContent != nil);
  const BOOL endStyleReady = (_endStyle != nil);
  const BOOL endContentReady = (_endContent != nil);
  const BOOL ready =
    [self hasAnyNode]
      && [self isRenderableForNode:_startNode style:_startStyle content:_startContent]
      && [self isRenderableForNode:_endNode style:_endStyle content:_endContent];
  const NSInteger elapsedMs = (NSInteger)((CACurrentMediaTime() - _startedAt) * 1000.0);

  return @{
    @"ready": @(ready),
    @"reason": reason ?: @"unknown",
    @"elapsedMs": @(elapsedMs),
    @"hasStartNode": @(hasStartNode),
    @"hasEndNode": @(hasEndNode),
    @"startStyleReady": @(startStyleReady),
    @"startContentReady": @(startContentReady),
    @"endStyleReady": @(endStyleReady),
    @"endContentReady": @(endContentReady),
  };
}

- (void)finishWithReason:(NSString*)reason
{
  if (_finished) return;
  _finished = YES;

  if (_startNode != nil) [_startNode cancelRequests:self];
  if (_endNode != nil) [_endNode cancelRequests:self];

  NSDictionary* result = [self buildResult:reason];
  if (_resolve != nil) _resolve(result);

  if (_startNode != nil) [_nodeManager release:_startNode];
  if (_endNode != nil) [_nodeManager release:_endNode];
  _startNode = nil;
  _endNode = nil;

  if (_onComplete != nil) _onComplete(self);
}

- (void)maybeResolveReady
{
  if (_finished) return;
  if (![self hasAnyNode]) {
    [self finishWithReason:@"no-nodes"];
    return;
  }
  if ([self isRenderableForNode:_startNode style:_startStyle content:_startContent]
      && [self isRenderableForNode:_endNode style:_endStyle content:_endContent]) {
    [self finishWithReason:@"ready"];
  }
}

- (void)startWithTimeoutMs:(NSInteger)timeoutMs
{
  const NSInteger effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : 140;
  __weak RNSharedElementTransitionWaitProbe* weakSelf = self;
  dispatch_after(
    dispatch_time(DISPATCH_TIME_NOW, (int64_t)effectiveTimeoutMs * NSEC_PER_MSEC),
    dispatch_get_main_queue(),
    ^{
      [weakSelf finishWithReason:@"timeout"];
    }
  );

  if (_startNode != nil) {
    [_startNode requestStyle:self];
    [_startNode requestContent:self];
  }
  if (_endNode != nil) {
    [_endNode requestStyle:self];
    [_endNode requestContent:self];
  }

  [self maybeResolveReady];
}

- (void)didLoadStyle:(RNSharedElementStyle*)style node:(id)node
{
  if (_finished) return;
  if (node == _startNode) {
    _startStyle = style;
  } else if (node == _endNode) {
    _endStyle = style;
  }
  [self maybeResolveReady];
}

- (void)didLoadContent:(RNSharedElementContent*)content node:(id)node
{
  if (_finished) return;
  if (node == _startNode) {
    _startContent = content;
  } else if (node == _endNode) {
    _endContent = content;
  }
  [self maybeResolveReady];
}

@end

@implementation RNSharedElementTransitionManager
{
  RNSharedElementNodeManager* _nodeManager;
  NSMutableSet<RNSharedElementTransitionWaitProbe*>* _waitProbes;
}

RCT_EXPORT_MODULE(RNSharedElementTransition);

- (instancetype) init
{
  if ((self = [super init])) {
    _nodeManager = [[RNSharedElementNodeManager alloc]init];
    _waitProbes = [[NSMutableSet alloc]init];
    NSLog(@"[RNSE] local native build %@", RNSharedElementLocalBuildTimestamp);
  }
  return self;
}

- (UIView *)view
{
  return [[RNSharedElementTransition alloc] initWithNodeManager:_nodeManager];
}

- (dispatch_queue_t)methodQueue {
  return dispatch_get_main_queue();
}

- (RNSharedElementNode*)nodeFromJson:(NSDictionary*)json
            snapshotRequiredMissing:(BOOL*)snapshotRequiredMissing
{
  if (snapshotRequiredMissing != nil) *snapshotRequiredMissing = NO;
  if (json == nil) {
    return nil;
  }
  NSString* snapshotKey = [json valueForKey:@"snapshotKey"];
  NSString* snapshotMode = [json valueForKey:@"snapshotMode"];
  if ([snapshotKey isKindOfClass:[NSString class]] && snapshotKey.length > 0) {
    RNSharedElementNode* snapshotNode = [_nodeManager acquireSnapshot:snapshotKey];
    if (snapshotNode != nil) {
      NSLog(@"[RNSE] snapshot cache hit mode=%@ key=%@", snapshotMode, snapshotKey);
      return snapshotNode;
    }
    NSLog(@"[RNSE] snapshot cache miss mode=%@ key=%@", snapshotMode, snapshotKey);

    if ([snapshotMode isEqualToString:@"prefer"]) {
      NSNumber* nodeHandle = [json valueForKey:@"nodeHandle"];
      NSNumber* isParent = [json valueForKey:@"isParent"];
      if ([nodeHandle isKindOfClass:[NSNumber class]]) {
        UIView* sourceView = [self.bridge.uiManager viewForReactTag:nodeHandle];
        RNSharedElementNode* liveNode =
          [_nodeManager acquire:nodeHandle view:sourceView isParent:[isParent boolValue]];
        BOOL captured = [_nodeManager captureSnapshot:snapshotKey node:liveNode];
        [_nodeManager release:liveNode];
        if (captured) {
          NSLog(@"[RNSE] snapshot captured synchronously key=%@", snapshotKey);
          return [_nodeManager acquireSnapshot:snapshotKey];
        }
      }

      NSLog(@"[RNSE] snapshot unavailable; using fade key=%@", snapshotKey);
      if (snapshotRequiredMissing != nil) *snapshotRequiredMissing = YES;
      return nil;
    }
    if ([snapshotMode isEqualToString:@"require"]) {
      NSLog(@"[RNSE] required snapshot unavailable key=%@", snapshotKey);
      if (snapshotRequiredMissing != nil) *snapshotRequiredMissing = YES;
      return nil;
    }
  }

  NSNumber* nodeHandle = [json valueForKey:@"nodeHandle"];
  NSNumber* isParent = [json valueForKey:@"isParent"];
  if ([nodeHandle isKindOfClass:[NSNumber class]]) {
    UIView *sourceView = [self.bridge.uiManager viewForReactTag:nodeHandle];
    RNSharedElementNode* node =
      [_nodeManager acquire:nodeHandle view:sourceView isParent:[isParent boolValue]];
    return node;
  }
  return nil;
}

- (RNSharedElementNode*)nodeFromJson:(NSDictionary*)json
{
  return [self nodeFromJson:json snapshotRequiredMissing:nil];
}

- (void)onWaitProbeComplete:(RNSharedElementTransitionWaitProbe*)probe
{
  [_waitProbes removeObject:probe];
}

// Standard transition props.
RCT_EXPORT_VIEW_PROPERTY(nodePosition, CGFloat);
RCT_EXPORT_VIEW_PROPERTY(animation, NSInteger);
RCT_EXPORT_VIEW_PROPERTY(resize, NSInteger);
RCT_EXPORT_VIEW_PROPERTY(imageResolution, NSInteger);
RCT_EXPORT_VIEW_PROPERTY(align, NSInteger);
// Native-timer props used in Fabric interop mode.
RCT_CUSTOM_VIEW_PROPERTY(nativeDriver, BOOL, RNSharedElementTransition)
{
  BOOL value = [RCTConvert BOOL:json];
  view.nativeDriver = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeDuration, CGFloat, RNSharedElementTransition)
{
  CGFloat value = [RCTConvert CGFloat:json];
  view.nativeDuration = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeDelay, CGFloat, RNSharedElementTransition)
{
  CGFloat value = [RCTConvert CGFloat:json];
  view.nativeDelay = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeFrom, CGFloat, RNSharedElementTransition)
{
  CGFloat value = [RCTConvert CGFloat:json];
  view.nativeFrom = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeTo, CGFloat, RNSharedElementTransition)
{
  CGFloat value = [RCTConvert CGFloat:json];
  view.nativeTo = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeGroup, NSString, RNSharedElementTransition)
{
  NSString* value = [RCTConvert NSString:json];
  view.nativeGroup = value;
}
RCT_CUSTOM_VIEW_PROPERTY(nativeGroupSize, NSInteger, RNSharedElementTransition)
{
  NSInteger value = [RCTConvert NSInteger:json];
  view.nativeGroupSize = value;
}
RCT_CUSTOM_VIEW_PROPERTY(startNode, NSObject, RNSharedElementTransition)
{
  NSDictionary* nodeJson = [json valueForKey:@"node"];
  NSDictionary* ancestorJson = [json valueForKey:@"ancestor"];
  BOOL snapshotRequiredMissing = NO;
  RNSharedElementNode* node = [self nodeFromJson:nodeJson snapshotRequiredMissing:&snapshotRequiredMissing];
  RNSharedElementNode* ancestor = (snapshotRequiredMissing || node.isSnapshot)
    ? nil
    : [self nodeFromJson:ancestorJson];
  view.startSnapshotMissing = snapshotRequiredMissing;
  view.startNode = node;
  view.startAncestor = ancestor;
}
RCT_CUSTOM_VIEW_PROPERTY(endNode, NSObject, RNSharedElementTransition)
{
  NSDictionary* nodeJson = [json valueForKey:@"node"];
  NSDictionary* ancestorJson = [json valueForKey:@"ancestor"];
  BOOL snapshotRequiredMissing = NO;
  RNSharedElementNode* node = [self nodeFromJson:nodeJson snapshotRequiredMissing:&snapshotRequiredMissing];
  RNSharedElementNode* ancestor = (snapshotRequiredMissing || node.isSnapshot)
    ? nil
    : [self nodeFromJson:ancestorJson];
  view.endSnapshotMissing = snapshotRequiredMissing;
  view.endNode = node;
  view.endAncestor = ancestor;
}
RCT_EXPORT_VIEW_PROPERTY(onMeasureNode, RCTDirectEventBlock);

RCT_REMAP_METHOD(configure,
                 config:(NSDictionary *)config
                 resolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject)
{
  NSArray* imageResolvers = [config valueForKey:@"imageResolvers"];
  if (imageResolvers != nil) {
    [RNSharedElementNode setImageResolvers:imageResolvers];
  }
  resolve(@(YES));
}

RCT_REMAP_METHOD(waitForTransitionReady,
                 startItemMap:(NSDictionary *)startItemMap
                 endItemMap:(NSDictionary *)endItemMap
                 timeoutMs:(nonnull NSNumber *)timeoutMs
                 resolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject)
{
  NSDictionary* startNodeJson = [startItemMap isKindOfClass:[NSDictionary class]]
    ? [startItemMap valueForKey:@"node"]
    : nil;
  NSDictionary* endNodeJson = [endItemMap isKindOfClass:[NSDictionary class]]
    ? [endItemMap valueForKey:@"node"]
    : nil;
  BOOL startSnapshotMissing = NO;
  BOOL endSnapshotMissing = NO;
  RNSharedElementNode* startNode = [self nodeFromJson:startNodeJson snapshotRequiredMissing:&startSnapshotMissing];
  RNSharedElementNode* endNode = [self nodeFromJson:endNodeJson snapshotRequiredMissing:&endSnapshotMissing];

  if (startSnapshotMissing || endSnapshotMissing) {
    if (startNode != nil) [_nodeManager release:startNode];
    if (endNode != nil) [_nodeManager release:endNode];
    resolve(@{
      @"ready": @NO,
      @"reason": @"snapshot-missing",
      @"elapsedMs": @0,
      @"hasStartNode": @(startNode != nil),
      @"hasEndNode": @(endNode != nil),
      @"startStyleReady": @NO,
      @"startContentReady": @NO,
      @"endStyleReady": @NO,
      @"endContentReady": @NO,
    });
    return;
  }

  __weak RNSharedElementTransitionManager* weakSelf = self;
  RNSharedElementTransitionWaitProbe* probe =
    [[RNSharedElementTransitionWaitProbe alloc] initWithNodeManager:_nodeManager
                                                           startNode:startNode
                                                             endNode:endNode
                                                             resolve:resolve
                                                          onComplete:^(RNSharedElementTransitionWaitProbe* finishedProbe) {
                                                            [weakSelf onWaitProbeComplete:finishedProbe];
                                                          }];
  [_waitProbes addObject:probe];
  [probe startWithTimeoutMs:[timeoutMs integerValue]];
}

RCT_REMAP_METHOD(captureSnapshots,
                 routeKey:(NSString*)routeKey
                 elements:(NSArray*)elements
                 captureResolver:(RCTPromiseResolveBlock)resolve
                 captureRejecter:(RCTPromiseRejectBlock)reject)
{
  NSString* prefix = [NSString stringWithFormat:@"%@:", routeKey ?: @""];
  [_nodeManager clearSnapshotsWithPrefix:prefix];

  NSInteger captured = 0;
  for (NSDictionary* element in elements) {
    if (![element isKindOfClass:[NSDictionary class]]) continue;
    NSString* key = [element valueForKey:@"key"];
    NSDictionary* nodeJson = [element valueForKey:@"node"];
    RNSharedElementNode* node = [self nodeFromJson:nodeJson];
    if (node == nil) continue;
    if ([_nodeManager captureSnapshot:key node:node]) captured++;
    [_nodeManager release:node];
  }

  NSLog(
    @"[RNSE] snapshot batch route=%@ captured=%ld requested=%ld",
    routeKey,
    (long)captured,
    (long)elements.count
  );

  resolve(@{
    @"captured": @(captured),
    @"requested": @(elements.count),
  });
}

RCT_REMAP_METHOD(clearSnapshots,
                 clearRouteKey:(NSString*)routeKey
                 clearResolver:(RCTPromiseResolveBlock)resolve
                 clearRejecter:(RCTPromiseRejectBlock)reject)
{
  NSString* prefix = [NSString stringWithFormat:@"%@:", routeKey ?: @""];
  [_nodeManager clearSnapshotsWithPrefix:prefix];
  resolve(@YES);
}

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

@end
