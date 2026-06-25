//
//  RNSharedElementTransition.m
//  react-native-shared-element
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <CoreImage/CoreImage.h>
#import <QuartzCore/QuartzCore.h>
#import <math.h>
#import <React/RCTDefines.h>
#import <React/UIView+React.h>
#import "RNSharedElementTransition.h"
#import "RNSharedElementTransitionItem.h"

#define ITEM_START_ANCESTOR 0
#define ITEM_END_ANCESTOR 1
#define ITEM_START 2
#define ITEM_END 3

#ifndef RNSE_DEBUG_LAYOUT
#ifdef DEBUG
#define RNSE_DEBUG_LAYOUT 1
#else
#define RNSE_DEBUG_LAYOUT 0
#endif
#endif

#if RNSE_DEBUG_LAYOUT
#define DebugLog(...) NSLog(__VA_ARGS__)
#else
#define DebugLog(...) (void)0
#endif

static NSInteger RNSharedElementTransitionNextDebugId = 1;

static NSString* RNSharedElementBoolString(BOOL value)
{
  return value ? @"YES" : @"NO";
}

static NSString* RNSharedElementRectString(CGRect rect)
{
  return CGRectIsNull(rect) ? @"null" : NSStringFromCGRect(rect);
}

static NSString* RNSharedElementStyleString(RNSharedElementStyle* style)
{
  if (style == nil) return @"nil";
  return [NSString stringWithFormat:@"layout=%@ size=%@ opacity=%.3f transform=%@ view=%@",
          RNSharedElementRectString(style.layout),
          NSStringFromCGSize(style.size),
          style.opacity,
          [RNSharedElementStyle stringFromTransform:style.transform],
          NSStringFromClass(style.view.class)];
}

static BOOL RNSharedElementShouldLogActualFrames(NSString* debugName)
{
  if (debugName == nil) return YES;
  NSString* lowerName = debugName.lowercaseString;
  return [lowerName containsString:@"action"] ||
         [lowerName containsString:@"card"] ||
         [lowerName containsString:@"banner"] ||
         [lowerName containsString:@"image"] ||
         [lowerName containsString:@"wrapper"];
}

static NSString* RNSharedElementWindowFrame(UIView* view)
{
  if (view == nil || view.window == nil) return @"nil";
  return RNSharedElementRectString([view.window convertRect:view.bounds fromView:view]);
}

static NSString* RNSharedElementPresentationWindowFrame(UIView* view)
{
  if (view == nil || view.window == nil || view.superview == nil) return @"nil";
  CALayer* presentationLayer = view.layer.presentationLayer;
  if (presentationLayer == nil) return @"nil";
  return RNSharedElementRectString([view.window convertRect:presentationLayer.frame fromView:view.superview]);
}

static NSString* RNSharedElementViewStateString(NSString* label, UIView* view)
{
  if (view == nil) {
    return [NSString stringWithFormat:@"%@=nil", label];
  }
  CALayer* presentationLayer = view.layer.presentationLayer;
  NSString* presentationFrame = presentationLayer ? RNSharedElementRectString(presentationLayer.frame) : @"nil";
  NSString* presentationPosition = presentationLayer ? NSStringFromCGPoint(presentationLayer.position) : @"nil";
  return [NSString stringWithFormat:@"%@={class=%@ ptr=%p tag=%@ hidden=%@ alpha=%.3f layerOpacity=%.3f clips=%@ masks=%@ frame=%@ bounds=%@ center=%@ windowFrame=%@ presentationFrame=%@ presentationWindowFrame=%@ layerPosition=%@ presentationPosition=%@ transform=%@}",
          label,
          NSStringFromClass(view.class),
          view,
          view.reactTag,
          RNSharedElementBoolString(view.hidden),
          view.alpha,
          view.layer.opacity,
          RNSharedElementBoolString(view.clipsToBounds),
          RNSharedElementBoolString(view.layer.masksToBounds),
          RNSharedElementRectString(view.frame),
          RNSharedElementRectString(view.bounds),
          NSStringFromCGPoint(view.center),
          RNSharedElementWindowFrame(view),
          presentationFrame,
          RNSharedElementPresentationWindowFrame(view),
          NSStringFromCGPoint(view.layer.position),
          presentationPosition,
          NSStringFromCGAffineTransform(view.transform)];
}

static NSString* RNSharedElementLayerStateString(NSString* label, CALayer* layer)
{
  if (layer == nil) {
    return [NSString stringWithFormat:@"%@=nil", label];
  }
  CALayer* presentationLayer = layer.presentationLayer;
  NSString* presentationFrame = presentationLayer ? RNSharedElementRectString(presentationLayer.frame) : @"nil";
  NSString* presentationPosition = presentationLayer ? NSStringFromCGPoint(presentationLayer.position) : @"nil";
  return [NSString stringWithFormat:@"%@={frame=%@ bounds=%@ position=%@ opacity=%.3f presentationFrame=%@ presentationPosition=%@}",
          label,
          RNSharedElementRectString(layer.frame),
          RNSharedElementRectString(layer.bounds),
          NSStringFromCGPoint(layer.position),
          layer.opacity,
          presentationFrame,
          presentationPosition];
}

static NSString* RNSharedElementNodeString(RNSharedElementNode* node)
{
  if (node == nil) return @"nil";
  return [NSString stringWithFormat:@"name=%@ tag=%@ isParent=%@",
          node.debugName ?: @"<unnamed-node>",
          node.reactTag,
          RNSharedElementBoolString(node.isParent)];
}

static NSString* RNSharedElementTransitionName(NSString* debugName)
{
  return debugName ?: @"<unnamed-transition>";
}

// Native CADisplayLink animation used for Fabric interop when JS-driven
// Animated values do not update JS-side state. Fixed duration; eased to
// feel closer to UIKit transitions.
//
// NOTE: This path intentionally avoids per-frame logging for perf.
// Cubic ease-out to slow as the transition approaches the end.
static CGFloat RNSharedElementEaseOutCubic(CGFloat t)
{
  const CGFloat clamped = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
  const CGFloat inv = 1.0f - clamped;
  return 1.0f - (inv * inv * inv);
}

@class RNSharedElementTransition;

@interface RNSharedElementNativeAnimationGroup : NSObject
@property (nonatomic, copy) NSString* groupId;
@property (nonatomic, assign) NSInteger expectedCount;
@property (nonatomic, strong) NSHashTable<RNSharedElementTransition*>* transitions;
@property (nonatomic, assign) BOOL started;
@property (nonatomic, assign) BOOL timeoutScheduled;
@property (nonatomic, assign) CFTimeInterval startTime;
@end

@implementation RNSharedElementNativeAnimationGroup

- (instancetype)initWithGroupId:(NSString*)groupId expectedCount:(NSInteger)expectedCount
{
  if ((self = [super init])) {
    _groupId = [groupId copy];
    _expectedCount = expectedCount;
    _transitions = [NSHashTable weakObjectsHashTable];
    _started = NO;
    _timeoutScheduled = NO;
    _startTime = 0;
  }
  return self;
}

@end

static NSMutableDictionary<NSString*, RNSharedElementNativeAnimationGroup*>* RNSharedElementNativeAnimationGroups;

static NSMutableDictionary<NSString*, RNSharedElementNativeAnimationGroup*>* RNSharedElementGetNativeAnimationGroups(void)
{
  if (RNSharedElementNativeAnimationGroups == nil) {
    RNSharedElementNativeAnimationGroups = [NSMutableDictionary new];
  }
  return RNSharedElementNativeAnimationGroups;
}

@interface RNSharedElementTransition ()
- (void)beginNativeAnimationAtTime:(CFTimeInterval)startTime reason:(NSString*)reason;
@end

@implementation RNSharedElementTransition
{
  NSArray* _items;
  UIView* _outerStyleView;
  UIView* _innerClipView;
  UIImageView* _primaryImageView;
  UIImageView* _secondaryImageView;
  CALayer* _maskLayer;
  BOOL _reactFrameSet;
  BOOL _initialLayoutPassCompleted;
  int _initialVisibleAncestorIndex;
  // CADisplayLink-driven animation state (Fabric interop path).
  CADisplayLink* _displayLink;
  CFTimeInterval _nativeStartTime;
  CGFloat _nativeFrom;
  CGFloat _nativeTo;
  CGFloat _nativeDuration;
  CGFloat _nativeDelay;
  BOOL _nativeAnimating;
  BOOL _nativeAnimationPending;
  BOOL _nativeAnimationStartScheduled;
  NSString* _nativeRegisteredGroup;
  NSInteger _debugId;
  NSInteger _debugLastGeometryBucket;
  NSInteger _debugGeometryLogCount;
}

- (void)removeFromNativeAnimationGroup
{
  if (_nativeRegisteredGroup == nil) return;
  NSMutableDictionary<NSString*, RNSharedElementNativeAnimationGroup*>* groups = RNSharedElementGetNativeAnimationGroups();
  RNSharedElementNativeAnimationGroup* group = [groups objectForKey:_nativeRegisteredGroup];
  [group.transitions removeObject:self];
  if (group != nil && group.transitions.allObjects.count == 0) {
    [groups removeObjectForKey:_nativeRegisteredGroup];
  }
  _nativeRegisteredGroup = nil;
}

+ (void)startNativeAnimationGroup:(RNSharedElementNativeAnimationGroup*)group reason:(NSString*)reason
{
  if (group == nil || group.started) return;

  group.started = YES;
  CFTimeInterval maxDelaySeconds = 0;
  NSArray<RNSharedElementTransition*>* transitions = group.transitions.allObjects;
  for (RNSharedElementTransition* transition in transitions) {
    maxDelaySeconds = MAX(maxDelaySeconds, transition->_nativeDelay / 1000.0);
  }
  group.startTime = CACurrentMediaTime() + maxDelaySeconds;

  DebugLog(@"[RNSE:group %@] native anim group start reason=%@ count=%lu expected=%ld startTime=%.6f delay=%.3f",
           group.groupId,
           reason,
           (unsigned long)transitions.count,
           (long)group.expectedCount,
           group.startTime,
           maxDelaySeconds * 1000.0);

  for (RNSharedElementTransition* transition in transitions) {
    [transition beginNativeAnimationAtTime:group.startTime reason:reason];
  }
}

- (BOOL)queueNativeAnimationInGroup:(NSString*)reason
{
  if (_nativeGroup.length == 0 || _nativeGroupSize <= 1) {
    return NO;
  }

  NSMutableDictionary<NSString*, RNSharedElementNativeAnimationGroup*>* groups = RNSharedElementGetNativeAnimationGroups();
  RNSharedElementNativeAnimationGroup* group = [groups objectForKey:_nativeGroup];
  if (group == nil) {
    group = [[RNSharedElementNativeAnimationGroup alloc] initWithGroupId:_nativeGroup expectedCount:_nativeGroupSize];
    [groups setObject:group forKey:_nativeGroup];
  } else {
    group.expectedCount = MAX(group.expectedCount, _nativeGroupSize);
  }

  if (group.started) {
    [self beginNativeAnimationAtTime:group.startTime reason:[NSString stringWithFormat:@"group:%@", reason]];
    return YES;
  }

  if (![_nativeRegisteredGroup isEqualToString:_nativeGroup]) {
    [self removeFromNativeAnimationGroup];
    [group.transitions addObject:self];
    _nativeRegisteredGroup = [_nativeGroup copy];
  }

  const NSUInteger readyCount = group.transitions.allObjects.count;
  DebugLog(@"[RNSE:%ld %@] native anim group wait reason=%@ group=%@ ready=%lu expected=%ld",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           reason,
           _nativeGroup,
           (unsigned long)readyCount,
           (long)group.expectedCount);

  if (readyCount >= group.expectedCount) {
    [RNSharedElementTransition startNativeAnimationGroup:group reason:[NSString stringWithFormat:@"group-ready:%@", reason]];
    return YES;
  }

  if (!group.timeoutScheduled) {
    group.timeoutScheduled = YES;
    __weak RNSharedElementNativeAnimationGroup* weakGroup = group;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(80 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
      RNSharedElementNativeAnimationGroup* timeoutGroup = weakGroup;
      if (timeoutGroup == nil || timeoutGroup.started) return;
      [RNSharedElementTransition startNativeAnimationGroup:timeoutGroup reason:@"group-timeout"];
    });
  }

  return YES;
}

- (void)startNativeAnimationNowIfReady:(NSString*)reason
{
  // Drive nodePosition natively once layout/content is ready.
  if (!_nativeDriver) return;
  if (!_nativeAnimationPending) return;
  if (!_initialLayoutPassCompleted) {
    DebugLog(@"RNSharedElementTransition: native anim pending (init) %@", reason);
    return;
  }
  // A zero duration is treated as no-op to avoid a zero-length loop.
  if (_nativeDuration <= 0) {
    DebugLog(@"RNSharedElementTransition: native anim skipped (duration=0) %@", reason);
    _nativeAnimationPending = NO;
    return;
  }
  if (_nativeAnimating) return;

  if ([self queueNativeAnimationInGroup:reason]) {
    return;
  }

  const CFTimeInterval now = CACurrentMediaTime();
  const CFTimeInterval delaySeconds = _nativeDelay / 1000.0;
  // Delay is in ms from JS, convert to seconds for CoreAnimation clock.
  [self beginNativeAnimationAtTime:(now + delaySeconds) reason:reason];
}

- (void)startNativeAnimationIfReady:(NSString*)reason
{
  if (_nativeAnimationStartScheduled || _nativeAnimating) return;
  _nativeAnimationStartScheduled = YES;
  NSString* scheduledReason = [reason copy];
  dispatch_async(dispatch_get_main_queue(), ^{
    self->_nativeAnimationStartScheduled = NO;
    [self startNativeAnimationNowIfReady:scheduledReason];
  });
}

- (void)beginNativeAnimationAtTime:(CFTimeInterval)startTime reason:(NSString*)reason
{
  if (!_nativeDriver) return;
  if (_nativeAnimating) return;

  _nativeAnimating = YES;
  _nativeAnimationPending = NO;
  _nativeStartTime = startTime;

  if (!isfinite(_nativeFrom)) _nativeFrom = _nodePosition;
  if (!isfinite(_nativeTo)) _nativeTo = 1.0f;

  DebugLog(@"[RNSE:%ld %@] native anim start reason=%@ group=%@ from=%.3f to=%.3f duration=%.1f delay=%.1f startTime=%.6f initialVisibleAncestor=%d",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           reason,
           _nativeGroup ?: @"<none>",
           _nativeFrom,
           _nativeTo,
           _nativeDuration,
           _nativeDelay,
           _nativeStartTime,
           _initialVisibleAncestorIndex);

  if (_displayLink == nil) {
    // Use the main run loop so updates align with UIKit rendering.
    _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(onDisplayLink:)];
    [_displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
  }
}

- (void)stopNativeAnimation:(NSString*)reason
{
  // Cleanly tear down the display link to avoid leaks or stray updates.
  if (!_nativeAnimating) return;
  _nativeAnimating = NO;
  if (_displayLink != nil) {
    [_displayLink invalidate];
    _displayLink = nil;
  }
  DebugLog(@"RNSharedElementTransition: native anim stop (%@)", reason);
}

- (void)onDisplayLink:(CADisplayLink*)displayLink
{
  if (!_nativeAnimating) return;
  const CFTimeInterval now = CACurrentMediaTime();
  if (now < _nativeStartTime) return;

  const CFTimeInterval elapsed = now - _nativeStartTime;
  const CFTimeInterval durationSeconds = _nativeDuration / 1000.0;
  // Fixed-duration easing: ease-out to mimic UIKit-ish deceleration.
  const CGFloat t = durationSeconds > 0 ? MIN(1.0, (CGFloat)(elapsed / durationSeconds)) : 1.0f;
  const CGFloat eased = RNSharedElementEaseOutCubic(t);
  const CGFloat value = _nativeFrom + ((_nativeTo - _nativeFrom) * eased);

  if (_nodePosition != value) {
    _nodePosition = value;
    [self updateStyle];
    [self updateNodeVisibility];
  }

  if (t >= 1.0f) {
    [self stopNativeAnimation:@"complete"];
  }
}

- (void)startTransitionIfNeeded:(NSString*)reason
{
  // In Fabric interop, layoutSubviews can arrive with zero-sized bounds.
  // Use superview bounds as a fallback to decide when to bootstrap.
  if (_reactFrameSet) return;
  CGRect selfBounds = self.bounds;
  CGRect superBounds = self.superview ? self.superview.bounds : CGRectZero;
  if (CGRectIsEmpty(selfBounds) && CGRectIsEmpty(superBounds)) {
    DebugLog(@"RNSharedElementTransition: skip bootstrap (%@), bounds=%@ super=%@",
             reason,
             NSStringFromCGRect(selfBounds),
             NSStringFromCGRect(superBounds));
    return;
  }
  _reactFrameSet = YES;
  DebugLog(@"RNSharedElementTransition: bootstrap (%@), bounds=%@ super=%@",
           reason,
           NSStringFromCGRect(selfBounds),
           NSStringFromCGRect(superBounds));
  // Defer to next run loop so React layout/content requests are ready.
  dispatch_async(dispatch_get_main_queue(), ^{
    for (RNSharedElementTransitionItem* item in self->_items) {
      if (item.needsLayout) {
        item.needsLayout = NO;
        [item.node requestStyle:self];
      }
      if (item.needsContent) {
        item.needsContent = NO;
        [item.node requestContent:self];
      }
    }
    self->_initialLayoutPassCompleted = YES;
    [self updateStyle];
    [self updateNodeVisibility];
    [self startNativeAnimationIfReady:@"bootstrap"];
  });
}

- (instancetype)initWithNodeManager:(RNSharedElementNodeManager*)nodeManager
{
  if ((self = [super init])) {
    _debugId = RNSharedElementTransitionNextDebugId++;
    _debugLastGeometryBucket = -1;
    _debugGeometryLogCount = 0;
    _items = @[
      [[RNSharedElementTransitionItem alloc]initWithNodeManager:nodeManager name:@"startAncestor" isAncestor:YES],
      [[RNSharedElementTransitionItem alloc]initWithNodeManager:nodeManager name:@"endAncestor" isAncestor:YES],
      [[RNSharedElementTransitionItem alloc]initWithNodeManager:nodeManager name:@"startNode" isAncestor:NO],
      [[RNSharedElementTransitionItem alloc]initWithNodeManager:nodeManager name:@"endNode" isAncestor:NO]
    ];
    _nodePosition = 0.0f;
    _animation = RNSharedElementAnimationMove;
    _resize = RNSharedElementResizeStretch;
    _align = RNSharedElementAlignCenterCenter;
    _reactFrameSet = NO;
    _initialLayoutPassCompleted = NO;
    _initialVisibleAncestorIndex = -1;
    _nativeDriver = NO;
    _nativeDuration = 0.0f;
    _nativeDelay = 0.0f;
    _nativeFrom = NAN;
    _nativeTo = NAN;
    _nativeAnimating = NO;
    _nativeAnimationPending = NO;
    _nativeAnimationStartScheduled = NO;
    _nativeRegisteredGroup = nil;
    _nativeGroupSize = 0;
    self.userInteractionEnabled = NO;
    
    _outerStyleView = [[UIImageView alloc]init];
    _outerStyleView.userInteractionEnabled = NO;
    _outerStyleView.frame = self.bounds;
    [self addSubview:_outerStyleView];
    
    _innerClipView = [[UIImageView alloc]init];
    _innerClipView.userInteractionEnabled = NO;
    _innerClipView.frame = self.bounds;
    _innerClipView.layer.masksToBounds = YES;
    [_outerStyleView addSubview:_innerClipView];
    
    _primaryImageView = [self createImageView];
    _secondaryImageView = [self createImageView];

    _maskLayer = [[CALayer alloc] init];
    _maskLayer.backgroundColor = [UIColor whiteColor].CGColor;
    self.layer.mask = _maskLayer;
  }
  
  return self;
}

- (void)removeFromSuperview
{
  [super removeFromSuperview];
  // Ensure display link stops if the transition view is removed.
  [self stopNativeAnimation:@"removeFromSuperview"];
  [self removeFromNativeAnimationGroup];
  
  for (RNSharedElementTransitionItem* item in _items) {
    if (item.node != nil) [item.node cancelRequests:self];
  }
}

- (void)layoutSubviews
{
  [super layoutSubviews];
  // Bootstrap in layoutSubviews to match Fabric interop render timing.
  [self startTransitionIfNeeded:@"layoutSubviews"];
}

- (void)dealloc
{
  // Defensive cleanup to avoid display link retaining this view.
  [self stopNativeAnimation:@"dealloc"];
  [self removeFromNativeAnimationGroup];
  for (RNSharedElementTransitionItem* item in _items) {
    item.node = nil;
  }
}

- (UIImageView*) createImageView
{
  UIImageView* imageView = [[UIImageView alloc]init];
  imageView.contentMode = UIViewContentModeScaleToFill;
  imageView.userInteractionEnabled = NO;
  imageView.frame = self.bounds;
  return imageView;
}

- (RNSharedElementTransitionItem*) findItemForNode:(RNSharedElementNode*) node
{
  for (RNSharedElementTransitionItem* item in _items) {
    if (item.node == node) {
      return item;
    }
  }
  return nil;
}

- (void)setStartNode:(RNSharedElementNode *)startNode
{
  ((RNSharedElementTransitionItem*)[_items objectAtIndex:ITEM_START]).node = startNode;
  DebugLog(@"[RNSE:%ld %@] setStartNode %@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           RNSharedElementNodeString(startNode));
  // Native animation can only start once both nodes/ancestors resolve.
  _nativeAnimationPending = _nativeDriver;
  [self startNativeAnimationIfReady:@"startNode"];
}

- (void)setEndNode:(RNSharedElementNode *)endNode
{
  ((RNSharedElementTransitionItem*)[_items objectAtIndex:ITEM_END]).node = endNode;
  DebugLog(@"[RNSE:%ld %@] setEndNode %@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           RNSharedElementNodeString(endNode));
  // Native animation can only start once both nodes/ancestors resolve.
  _nativeAnimationPending = _nativeDriver;
  [self startNativeAnimationIfReady:@"endNode"];
}

- (void)setStartAncestor:(RNSharedElementNode *)startNodeAncestor
{
  ((RNSharedElementTransitionItem*)[_items objectAtIndex:ITEM_START_ANCESTOR]).node = startNodeAncestor;
  DebugLog(@"[RNSE:%ld %@] setStartAncestor %@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           RNSharedElementNodeString(startNodeAncestor));
  // Ancestor resolution can happen later than nodes in Fabric interop.
  _nativeAnimationPending = _nativeDriver;
  [self startNativeAnimationIfReady:@"startAncestor"];
}

- (void)setEndAncestor:(RNSharedElementNode *)endNodeAncestor
{
  ((RNSharedElementTransitionItem*)[_items objectAtIndex:ITEM_END_ANCESTOR]).node = endNodeAncestor;
  DebugLog(@"[RNSE:%ld %@] setEndAncestor %@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           RNSharedElementNodeString(endNodeAncestor));
  // Ancestor resolution can happen later than nodes in Fabric interop.
  _nativeAnimationPending = _nativeDriver;
  [self startNativeAnimationIfReady:@"endAncestor"];
}

- (void)setNodePosition:(CGFloat)nodePosition
{
  if (_nodePosition != nodePosition) {
    // Ignore JS updates while native driver is active to avoid contention.
    if (_nativeAnimating && _nativeDriver) {
      return;
    }
    // If a non-native update arrives, stop the CADisplayLink to avoid conflict.
    if (_nativeAnimating) {
      [self stopNativeAnimation:@"nodePosition set"];
    }
    _nodePosition = nodePosition;
    [self updateStyle];
  }
}

- (void) setAnimation:(RNSharedElementAnimation)animation
{
  if (_animation != animation) {
    _animation = animation;
    [self updateStyle];
  }
}

- (void) setResize:(RNSharedElementResize)resize
{
  if (_resize != resize) {
    _resize = resize;
    [self updateStyle];
  }
}

- (void) setAlign:(RNSharedElementAlign)align
{
  if (_align != align) {
    _align = align;
    [self updateStyle];
  }
}

- (void)setNativeDriver:(BOOL)nativeDriver
{
  if (_nativeDriver != nativeDriver) {
    _nativeDriver = nativeDriver;
    // When enabled, queue a native animation once props+layout are ready.
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeDriver"];
  }
}

- (void)setNativeDuration:(CGFloat)nativeDuration
{
  if (_nativeDuration != nativeDuration) {
    _nativeDuration = nativeDuration;
    // Duration changes should restart the pending native animation.
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeDuration"];
  }
}

- (void)setNativeDelay:(CGFloat)nativeDelay
{
  if (_nativeDelay != nativeDelay) {
    _nativeDelay = nativeDelay;
    // Delay changes should restart the pending native animation.
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeDelay"];
  }
}

- (void)setNativeFrom:(CGFloat)nativeFrom
{
  if (_nativeFrom != nativeFrom) {
    _nativeFrom = nativeFrom;
    // From/to changes should restart the pending native animation.
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeFrom"];
  }
}

- (void)setNativeTo:(CGFloat)nativeTo
{
  if (_nativeTo != nativeTo) {
    _nativeTo = nativeTo;
    // From/to changes should restart the pending native animation.
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeTo"];
  }
}

- (void)setNativeGroup:(NSString*)nativeGroup
{
  if ((_nativeGroup == nativeGroup) || [_nativeGroup isEqualToString:nativeGroup]) {
    return;
  }
  [self removeFromNativeAnimationGroup];
  _nativeGroup = [nativeGroup copy];
  _nativeAnimationPending = _nativeDriver;
  [self startNativeAnimationIfReady:@"nativeGroup"];
}

- (void)setNativeGroupSize:(NSInteger)nativeGroupSize
{
  if (_nativeGroupSize != nativeGroupSize) {
    _nativeGroupSize = nativeGroupSize;
    _nativeAnimationPending = _nativeDriver;
    [self startNativeAnimationIfReady:@"nativeGroupSize"];
  }
}

- (void)updateNodeVisibility
{
  for (RNSharedElementTransitionItem* item in _items) {
    BOOL previousHidden = item.hidden;
    BOOL hidden = _initialLayoutPassCompleted && item.style != nil && item.content != nil;
    if (hidden && (_animation == RNSharedElementAnimationFadeIn) && [item.name isEqualToString:@"startNode"]) hidden = NO;
    if (hidden && (_animation == RNSharedElementAnimationFadeOut) && [item.name isEqualToString:@"endNode"]) hidden = NO;
    item.hidden = hidden;
    if (previousHidden != hidden) {
      DebugLog(@"[RNSE:%ld %@] visibility item=%@ node=%@ hidden=%@ hasStyle=%@ hasContent=%@ animation=%ld pos=%.3f",
               (long)_debugId,
               RNSharedElementTransitionName(_debugName),
               item.name,
               RNSharedElementNodeString(item.node),
               RNSharedElementBoolString(hidden),
               RNSharedElementBoolString(item.style != nil),
               RNSharedElementBoolString(item.content != nil),
               (long)_animation,
               _nodePosition);
    }
  }
}

- (void) didSetProps:(NSArray<NSString *> *)changedProps
{
  [self startTransitionIfNeeded:@"didSetProps"];
  for (RNSharedElementTransitionItem* item in _items) {
    if (_initialLayoutPassCompleted && item.needsLayout) {
      item.needsLayout = NO;
      [item.node requestStyle:self];
    }
  }
  [self updateNodeVisibility];
}

- (void)updateViewWithImage:(UIImageView*)view image:(UIImage *)image
{
  if (!image) {
    view.image = nil;
    return;
  }
  
  // Apply trilinear filtering to smooth out mis-sized images
  view.layer.minificationFilter = kCAFilterTrilinear;
  view.layer.magnificationFilter = kCAFilterTrilinear;
  
  // NSLog(@"updateWithImage: %@", NSStringFromCGRect(self.frame));
  view.image = image;
}

- (void) didLoadContent:(RNSharedElementContent*)content node:(id)node
{
  // NSLog(@"didLoadContent: %@", content);
  RNSharedElementTransitionItem* item = [self findItemForNode:node];
  if (item == nil) return;
  item.content = content;
  DebugLog(@"[RNSE:%ld %@] didLoadContent item=%@ node=%@ type=%@ hasData=%@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           item.name,
           RNSharedElementNodeString(node),
           content ? content.typeName : @"nil",
           RNSharedElementBoolString(content && content.data));
  if ((content.type == RNSharedElementContentTypeSnapshotImage) || (content.type == RNSharedElementContentTypeRawImage)) {
    UIImage* image = (UIImage*) content.data;
    if (_animation == RNSharedElementAnimationMove) {
      if (_primaryImageView.image == nil) {
        [self updateViewWithImage:_primaryImageView image:image];
      } else if ((image.size.width * image.size.height) > (_primaryImageView.image.size.width * _primaryImageView.image.size.height)) {
        [self updateViewWithImage:_primaryImageView image:image];
      }
    } else {
      if (item == _items[ITEM_START]) {
        [self updateViewWithImage:_primaryImageView image:image];
      } else {
        [self updateViewWithImage:_secondaryImageView image:image];
      }
    }
  }
  [self updateStyle];
  [self updateNodeVisibility];
}

- (void) didLoadStyle:(RNSharedElementStyle *)style node:(RNSharedElementNode*)node
{
  // NSLog(@"didLoadStyle: %@", NSStringFromCGRect(style.layout));
  RNSharedElementTransitionItem* item = [self findItemForNode:node];
  if (item == nil) return;
  item.style = style;
  DebugLog(@"[RNSE:%ld %@] didLoadStyle item=%@ node=%@ isAncestor=%@ %@",
           (long)_debugId,
           RNSharedElementTransitionName(_debugName),
           item.name,
           RNSharedElementNodeString(node),
           RNSharedElementBoolString(item.isAncestor),
           RNSharedElementStyleString(style));
  if (RNSharedElementShouldLogActualFrames(_debugName) || RNSharedElementShouldLogActualFrames(node.debugName)) {
    DebugLog(@"[RNSE:%ld %@] source item=%@ %@",
             (long)_debugId,
             RNSharedElementTransitionName(_debugName),
             item.name,
             [node debugSourceDescription]);
  }
  [self updateStyle];
  [self updateNodeVisibility];
}

- (CGRect)normalizeLayout:(CGRect)layout
  compensateForTransforms:(BOOL)compensateForTransforms
                 ancestor:(RNSharedElementTransitionItem*)ancestor
            otherAncestor:(RNSharedElementTransitionItem*)otherAncestor

{
  // Compensate for any transforms that have been applied to the scene by the
  // navigator. For instance, a navigator may translate the scene to the right,
  // outside of the screen, in order to show it using a slide animation.
  // In such a case, remove that transform in order to obtain the "real"
  // size and position on the screen.
  if (compensateForTransforms && (ancestor.style != nil)) {
    
    // Calculate "real" size and position of the ancestor (undo its transform)
    RNSharedElementStyle* ancestorStyle = ancestor.style;
    RNSharedElementStyle* otherAncestorStyle = otherAncestor ? otherAncestor.style : nil;
    CATransform3D transform = otherAncestorStyle ? CATransform3DConcat(ancestorStyle.transform, CATransform3DInvert(otherAncestorStyle.transform)) : ancestorStyle.transform;
    CGRect ancestorLayout = ancestorStyle.layout;
    CGRect normalizedAncestorLayout = ancestorLayout;
    if (CATransform3DIsAffine(transform)) {
      CGAffineTransform affineTransform = CATransform3DGetAffineTransform(CATransform3DInvert(transform));
      // Apply the transform on the center
      normalizedAncestorLayout.origin = CGPointMake((ancestorLayout.size.width / -2.0), (ancestorLayout.size.height / -2.0));
      CGPoint diff = CGPointMake(ancestorLayout.origin.x - normalizedAncestorLayout.origin.x, ancestorLayout.origin.y - normalizedAncestorLayout.origin.y);
      normalizedAncestorLayout = CGRectApplyAffineTransform(normalizedAncestorLayout, affineTransform);
      // Undo centering
      normalizedAncestorLayout.origin = CGPointMake(normalizedAncestorLayout.origin.x + diff.x,normalizedAncestorLayout.origin.y + diff.y);
    } else {
      // Fallback, supports only translation
      normalizedAncestorLayout.origin.x -= transform.m41;
      normalizedAncestorLayout.origin.y -= transform.m42;
    }
    
    // Calculate size and position of element within the normalized ancestor
    CGFloat scaleX = normalizedAncestorLayout.size.width / ancestorLayout.size.width;
    CGFloat scaleY = normalizedAncestorLayout.size.height / ancestorLayout.size.height;
    layout = CGRectMake(
      ((layout.origin.x - ancestorLayout.origin.x) * scaleX) + normalizedAncestorLayout.origin.x,
      ((layout.origin.y - ancestorLayout.origin.y) * scaleY) + normalizedAncestorLayout.origin.y,
      layout.size.width * scaleX,
      layout.size.height * scaleY
    );
  }
  
  // Convert to render overlay coordinates
  return [self.superview convertRect:layout fromView:nil];
}

- (CGFloat) getAncestorVisibility:(RNSharedElementStyle*)ancestorStyle
{
  CGRect intersection = CGRectIntersection(self.superview.bounds, [self.superview convertRect:ancestorStyle.layout fromView:nil]);
  if (CGRectIsNull(intersection)) return 0;
  CGFloat superVolume = self.superview.bounds.size.width * self.superview.bounds.size.height;
  CGFloat intersectionVolume = intersection.size.width * intersection.size.height;
  CGFloat ancestorVolume = ancestorStyle.layout.size.width * ancestorStyle.layout.size.height;
  return (intersectionVolume / superVolume) * (intersectionVolume / ancestorVolume);
}

- (CGRect) getInterpolatedLayout:(CGRect)layout1 layout2:(CGRect)layout2 position:(CGFloat) position
{
  return CGRectMake(
                    layout1.origin.x + ((layout2.origin.x - layout1.origin.x) * position),
                    layout1.origin.y + ((layout2.origin.y - layout1.origin.y) * position),
                    layout1.size.width + ((layout2.size.width - layout1.size.width) * position),
                    layout1.size.height + ((layout2.size.height - layout1.size.height) * position)
                    );
}

- (UIEdgeInsets) getClipInsets:(CGRect)layout visibleLayout:(CGRect)visibleLayout
{
  return UIEdgeInsetsMake(
                          visibleLayout.origin.y - layout.origin.y,
                          visibleLayout.origin.x - layout.origin.x,
                          (layout.origin.y + layout.size.height) - (visibleLayout.origin.y + visibleLayout.size.height),
                          (layout.origin.x + layout.size.width) - (visibleLayout.origin.x + visibleLayout.size.width)
                          );
}

- (UIEdgeInsets) getInterpolatedClipInsets:(CGRect)interpolatedLayout startClipInsets:(UIEdgeInsets)startClipInsets startVisibleLayout:(CGRect)startVisibleLayout endClipInsets:(UIEdgeInsets)endClipInsets endVisibleLayout:(CGRect)endVisibleLayout
{
  UIEdgeInsets clipInsets = UIEdgeInsetsZero;
  
  // Top
  if (!endClipInsets.top && startClipInsets.top && startVisibleLayout.origin.y <= endVisibleLayout.origin.y) {
    clipInsets.top = MAX(0.0f, startVisibleLayout.origin.y - interpolatedLayout.origin.y);
  } else if (!startClipInsets.top && endClipInsets.top && endVisibleLayout.origin.y <= startVisibleLayout.origin.y) {
    clipInsets.top = MAX(0.0f, endVisibleLayout.origin.y - interpolatedLayout.origin.y);
  } else {
    clipInsets.top = startClipInsets.top + ((endClipInsets.top - startClipInsets.top) * _nodePosition);
  }
  
  // Bottom
  if (!endClipInsets.bottom && startClipInsets.bottom && (startVisibleLayout.origin.y + startVisibleLayout.size.height) >= (endVisibleLayout.origin.y + endVisibleLayout.size.height)) {
    clipInsets.bottom = MAX(0.0f, (interpolatedLayout.origin.y + interpolatedLayout.size.height) - (startVisibleLayout.origin.y + startVisibleLayout.size.height));
  } else if (!startClipInsets.bottom && endClipInsets.bottom && (endVisibleLayout.origin.y + endVisibleLayout.size.height) >= (startVisibleLayout.origin.y + startVisibleLayout.size.height)) {
    clipInsets.bottom = MAX(0.0f, (interpolatedLayout.origin.y + interpolatedLayout.size.height) - (endVisibleLayout.origin.y + endVisibleLayout.size.height));
  } else {
    clipInsets.bottom = startClipInsets.bottom + ((endClipInsets.bottom - startClipInsets.bottom) * _nodePosition);
  }
  
  // Left
  if (!endClipInsets.left && startClipInsets.left && startVisibleLayout.origin.x <= endVisibleLayout.origin.x) {
    clipInsets.left = MAX(0.0f, startVisibleLayout.origin.x - interpolatedLayout.origin.x);
  } else if (!startClipInsets.left && endClipInsets.left && endVisibleLayout.origin.x <= startVisibleLayout.origin.x) {
    clipInsets.left = MAX(0.0f, endVisibleLayout.origin.x - interpolatedLayout.origin.x);
  } else {
    clipInsets.left = startClipInsets.left + ((endClipInsets.left - startClipInsets.left) * _nodePosition);
  }
  
  // Right
  if (!endClipInsets.right && startClipInsets.right && (startVisibleLayout.origin.x + startVisibleLayout.size.width) >= (endVisibleLayout.origin.x + endVisibleLayout.size.width)) {
    clipInsets.right = MAX(0.0f, (interpolatedLayout.origin.x + interpolatedLayout.size.width) - (startVisibleLayout.origin.x + startVisibleLayout.size.width));
  } else if (!startClipInsets.right && endClipInsets.right && (endVisibleLayout.origin.x + endVisibleLayout.size.width) >= (startVisibleLayout.origin.x + startVisibleLayout.size.width)) {
    clipInsets.right = MAX(0.0f, (interpolatedLayout.origin.x + interpolatedLayout.size.width) - (endVisibleLayout.origin.x + endVisibleLayout.size.width));
  } else {
    clipInsets.right = startClipInsets.right + ((endClipInsets.right - startClipInsets.right) * _nodePosition);
  }
  
  return clipInsets;
}

- (void) applyStyle:(RNSharedElementStyle*)style view:(UIView*)view
{
  CALayer *layer = view.layer;
  
  layer.opacity = style.opacity;
  layer.backgroundColor = style.backgroundColor.CGColor;
  layer.borderWidth = style.borderWidth;
  layer.borderColor = style.borderColor.CGColor;
  layer.shadowOpacity = style.shadowOpacity;
  layer.shadowRadius = style.shadowRadius;
  layer.shadowOffset = style.shadowOffset;
  layer.shadowColor = style.shadowColor.CGColor;
  [style.cornerRadii updateShadowPathForLayer:layer bounds:view.bounds];
  [style.cornerRadii updateClipMaskForLayer:layer bounds:view.bounds];
}

- (void) fireMeasureEvent:(RNSharedElementTransitionItem*) item layout:(CGRect)layout visibleLayout:(CGRect)visibleLayout contentLayout:(CGRect)contentLayout
{
  if (!self.onMeasureNode) return;
  RCTCornerRadii cornerRadii = [item.style.cornerRadii radiiForBounds:_outerStyleView.bounds];
  NSDictionary* eventData = @{
    @"node": item.name,
    @"layout": @{
        @"x": @(layout.origin.x),
        @"y": @(layout.origin.y),
        @"width": @(layout.size.width),
        @"height": @(layout.size.height),
        @"visibleX": @(visibleLayout.origin.x),
        @"visibleY": @(visibleLayout.origin.y),
        @"visibleWidth": @(visibleLayout.size.width),
        @"visibleHeight": @(visibleLayout.size.height),
        @"contentX": @(contentLayout.origin.x),
        @"contentY": @(contentLayout.origin.y),
        @"contentWidth": @(contentLayout.size.width),
        @"contentHeight": @(contentLayout.size.height),
    },
    @"contentType": item.content ? item.content.typeName : @"none",
    @"style": @{
        @"borderTopLeftRadius": @(cornerRadii.topLeftHorizontal),
        @"borderTopRightRadius": @(cornerRadii.topRightHorizontal),
        @"borderBottomLeftRadius": @(cornerRadii.bottomLeftHorizontal),
        @"borderBotomRightRadius": @(cornerRadii.bottomRightHorizontal)
    }
  };
  self.onMeasureNode(eventData);
}

- (void) updateStyle
{
  if (!_initialLayoutPassCompleted) return;
  
  // Local data
  RNSharedElementTransitionItem* startItem = [_items objectAtIndex:ITEM_START];
  RNSharedElementTransitionItem* startAncestor = [_items objectAtIndex:ITEM_START_ANCESTOR];
  RNSharedElementTransitionItem* endItem = [_items objectAtIndex:ITEM_END];
  RNSharedElementTransitionItem* endAncestor = [_items objectAtIndex:ITEM_END_ANCESTOR];
  RNSharedElementStyle* startStyle = startItem.style;
  RNSharedElementStyle* endStyle = endItem.style;
  
  // Determine starting scene that is currently visible to the user
  if (_initialVisibleAncestorIndex < 0) {
    RNSharedElementStyle* startAncenstorStyle = startAncestor.style;
    RNSharedElementStyle* endAncestorStyle = endAncestor.style;
    NSString* visibilityReason = nil;
    CGFloat startAncestorVisibility = -1.0f;
    CGFloat endAncestorVisibility = -1.0f;
    if (startAncenstorStyle && !endAncestorStyle) {
      _initialVisibleAncestorIndex = 0;
      visibilityReason = @"only-start-ancestor-style";
    } else if (!startAncenstorStyle && endAncestorStyle) {
      _initialVisibleAncestorIndex = 1;
      visibilityReason = @"only-end-ancestor-style";
    } else if (startAncenstorStyle && endAncestorStyle){
      startAncestorVisibility = [self getAncestorVisibility:startAncenstorStyle];
      endAncestorVisibility = [self getAncestorVisibility:endAncestorStyle];
      _initialVisibleAncestorIndex = endAncestorVisibility > startAncestorVisibility ? 1 : 0;
      visibilityReason = @"visibility-comparison";
    }
    if (_initialVisibleAncestorIndex >= 0) {
      DebugLog(@"[RNSE:%ld %@] initialVisibleAncestor=%d reason=%@ startVisibility=%.4f endVisibility=%.4f startAncestorNode=%@ endAncestorNode=%@ startAncestor={%@} endAncestor={%@}",
               (long)_debugId,
               RNSharedElementTransitionName(_debugName),
               _initialVisibleAncestorIndex,
               visibilityReason,
               startAncestorVisibility,
               endAncestorVisibility,
               RNSharedElementNodeString(startAncestor.node),
               RNSharedElementNodeString(endAncestor.node),
               RNSharedElementStyleString(startAncenstorStyle),
               RNSharedElementStyleString(endAncestorStyle));
    }
  }
  
  // Get start layout
  BOOL startCompensate = _initialVisibleAncestorIndex == 1;
  CGRect startLayout = startStyle ? [self normalizeLayout:startStyle.layout compensateForTransforms:startCompensate ancestor:startAncestor otherAncestor:endAncestor] : CGRectZero;
  CGRect startVisibleLayout = startStyle ? [self normalizeLayout:[startItem visibleLayoutForAncestor:startAncestor] compensateForTransforms:startCompensate ancestor:startAncestor otherAncestor:endAncestor] : CGRectZero;
  CGRect startContentLayout = startStyle ? [self normalizeLayout:[startItem contentLayoutForContent:startItem.content] compensateForTransforms:startCompensate ancestor:startAncestor otherAncestor:endAncestor] : CGRectZero;
  UIEdgeInsets startClipInsets = [self getClipInsets:startLayout visibleLayout:startVisibleLayout];
  
  // Get end layout
  BOOL endCompensate = _initialVisibleAncestorIndex == 0;
  CGRect endLayout = endStyle ? [self normalizeLayout:endStyle.layout compensateForTransforms:endCompensate ancestor:endAncestor otherAncestor:startAncestor] : CGRectZero;
  CGRect endVisibleLayout = endStyle ? [self normalizeLayout:[endItem visibleLayoutForAncestor:endAncestor] compensateForTransforms:endCompensate ancestor:endAncestor otherAncestor:startAncestor] : CGRectZero;
  CGRect endContentLayout = endStyle ?  [self normalizeLayout:[endItem contentLayoutForContent:(endItem.content ? endItem.content : startItem.content)] compensateForTransforms:endCompensate ancestor:endAncestor otherAncestor:startAncestor] : CGRectZero;
  UIEdgeInsets endClipInsets = [self getClipInsets:endLayout visibleLayout:endVisibleLayout];
  
  // Get interpolated style & layout
  RNSharedElementStyle* interpolatedStyle;
  CGRect interpolatedLayout;
  CGRect interpolatedContentLayout;
  UIEdgeInsets interpolatedClipInsets;
  if (!startStyle && !endStyle) return;
  if (startStyle && endStyle) {
    interpolatedStyle = [RNSharedElementStyle getInterpolatedStyle:startStyle style2:endStyle position:_nodePosition];
    interpolatedLayout = [self getInterpolatedLayout:startLayout layout2:endLayout position:_nodePosition];
    interpolatedClipInsets = [self getInterpolatedClipInsets:interpolatedLayout startClipInsets:startClipInsets startVisibleLayout:startVisibleLayout endClipInsets:endClipInsets endVisibleLayout:endVisibleLayout];
    interpolatedContentLayout = [self getInterpolatedLayout:startContentLayout layout2:endContentLayout position:_nodePosition];
  } else if (startStyle) {
    interpolatedStyle = startStyle;
    interpolatedLayout = startLayout;
    interpolatedClipInsets = startClipInsets;
    interpolatedContentLayout = startContentLayout;
  } else {
    interpolatedStyle = endStyle;
    interpolatedLayout = endLayout;
    interpolatedClipInsets = endClipInsets;
    interpolatedContentLayout = endContentLayout;
  }

  NSInteger geometryBucket = (NSInteger)lrint(_nodePosition * 10.0f);
  BOOL shouldLogGeometry = geometryBucket != _debugLastGeometryBucket || _debugGeometryLogCount < 4;
  if (shouldLogGeometry) {
    _debugLastGeometryBucket = geometryBucket;
    _debugGeometryLogCount++;
    DebugLog(@"[RNSE:%ld %@] geometry pos=%.3f bucket=%ld nativeAnimating=%@ nativePending=%@ initialVisibleAncestor=%d startItem=%@ endItem=%@ startCompensate=%@ endCompensate=%@ parentBounds=%@ startRaw={%@} endRaw={%@} startAncestor={%@} endAncestor={%@} startLayout=%@ endLayout=%@ startVisible=%@ endVisible=%@ startContent=%@ endContent=%@ interpolated=%@ interpolatedContent=%@ clipInsets={top=%.2f left=%.2f bottom=%.2f right=%.2f}",
             (long)_debugId,
             RNSharedElementTransitionName(_debugName),
             _nodePosition,
             (long)geometryBucket,
             RNSharedElementBoolString(_nativeAnimating),
             RNSharedElementBoolString(_nativeAnimationPending),
             _initialVisibleAncestorIndex,
             RNSharedElementNodeString(startItem.node),
             RNSharedElementNodeString(endItem.node),
             RNSharedElementBoolString(startCompensate),
             RNSharedElementBoolString(endCompensate),
             RNSharedElementRectString(self.superview.bounds),
             RNSharedElementStyleString(startStyle),
             RNSharedElementStyleString(endStyle),
             RNSharedElementStyleString(startAncestor.style),
             RNSharedElementStyleString(endAncestor.style),
             RNSharedElementRectString(startLayout),
             RNSharedElementRectString(endLayout),
             RNSharedElementRectString(startVisibleLayout),
             RNSharedElementRectString(endVisibleLayout),
             RNSharedElementRectString(startContentLayout),
             RNSharedElementRectString(endContentLayout),
             RNSharedElementRectString(interpolatedLayout),
             RNSharedElementRectString(interpolatedContentLayout),
             interpolatedClipInsets.top,
             interpolatedClipInsets.left,
             interpolatedClipInsets.bottom,
             interpolatedClipInsets.right);
  }
  
  // Update frame
  CGRect parentBounds = self.superview.bounds;
  [super reactSetFrame:parentBounds];
  
  // Update clipping mask (handles scrollview/parent clipping)
  // This kind of clipping is performed at the top level.
  CGFloat clipLeft = interpolatedClipInsets.left != 0.0f ? interpolatedClipInsets.left + interpolatedLayout.origin.x : 0.0f;
  CGFloat clipTop = interpolatedClipInsets.top != 0.0f ? interpolatedClipInsets.top + interpolatedLayout.origin.y : 0.0f;
  CGFloat clipBottom = interpolatedClipInsets.bottom != 0.0f ? parentBounds.size.height - (interpolatedLayout.origin.y + interpolatedLayout.size.height) + interpolatedClipInsets.bottom : 0.0f;
  CGFloat clipRight = interpolatedClipInsets.right != 0.0f ? parentBounds.size.width - (interpolatedLayout.origin.x + interpolatedLayout.size.width) + interpolatedClipInsets.right : 0.0f;
  CGRect clipFrame = CGRectMake(
                                clipLeft,
                                clipTop,
                                parentBounds.size.width - clipLeft - clipRight,
                                parentBounds.size.height - clipTop - clipBottom);
  _maskLayer.frame = clipFrame;
  
  // Update outer style view. This view has all styles such as border-color,
  // background color, and shadow. Because of the shadow, the view itsself
  // does not mask its bounds, otherwise the shadow isn't visible.
  _outerStyleView.frame = interpolatedLayout;
  [self applyStyle:interpolatedStyle view:_outerStyleView];
  
  // Update inner clip view. This view holds the image/content views
  // inside and clips their content.
  CGRect innerClipFrame = interpolatedLayout;
  innerClipFrame.origin.x = 0;
  innerClipFrame.origin.y = 0;
  _innerClipView.frame = innerClipFrame;
  [interpolatedStyle.cornerRadii updateClipMaskForLayer:_innerClipView.layer bounds:_innerClipView.bounds];
  _innerClipView.layer.masksToBounds = _resize != RNSharedElementResizeNone;
  
  // Update content
  UIView* contentView1 = (startItem.content && startItem.content.type == RNSharedElementContentTypeSnapshotView) ? startItem.content.data : _primaryImageView;
  UIView* contentView2 = nil;
  if (contentView1.superview != _innerClipView) [_innerClipView addSubview:contentView1];
  if (_animation == RNSharedElementAnimationMove) {
    
    // In case of move, we correctly calculate the content-frame
    // and interpolate between the start- and end-state, assuming
    // that the start- and end-content (image) has the same aspect-ratio
    CGRect contentFrame = interpolatedContentLayout;
    contentFrame.origin.x -= interpolatedLayout.origin.x;
    contentFrame.origin.y -= interpolatedLayout.origin.y;
    contentView1.frame = contentFrame;
  }
  else {
    // Update content-view 2
    contentView2 = (endItem.content && endItem.content.type == RNSharedElementContentTypeSnapshotView) ? endItem.content.data : _secondaryImageView;
    if (contentView2.superview != _innerClipView) [_innerClipView addSubview:contentView2];
    
    // In all other cases, animate and interpolate both the start- and
    // end views to look like each other
    CGRect startContentLayout2 = startStyle ? [RNSharedElementContent layoutForRect:endStyle ? endContentLayout : startContentLayout content:startItem.content contentMode:startStyle.contentMode reverse:YES] : CGRectZero;
    CGRect endContentLayout1 = endStyle ? [RNSharedElementContent layoutForRect:startStyle ? startContentLayout : endContentLayout content:endItem.content contentMode:endStyle.contentMode reverse:YES] : CGRectZero;
    
    // Calculate interpolated layout
    CGRect startInterpolatedContentLayout = [self getInterpolatedLayout:startContentLayout layout2:startContentLayout2 position:_nodePosition];
    CGRect endInterpolatedContentLayout = [self getInterpolatedLayout:endContentLayout1 layout2:endContentLayout position:_nodePosition];
    
    // Calculate new size
    switch (_resize) {
      case RNSharedElementResizeAuto:
        // Nothing to do
        break;
      case RNSharedElementResizeStretch:
        // TODO
        break;
      case RNSharedElementResizeClip:
      case RNSharedElementResizeNone:
        startInterpolatedContentLayout.size = startContentLayout.size;
        endInterpolatedContentLayout.size = endContentLayout.size;
        break;
    }
    
    // Calculate new origin
    switch (_align) {
      case RNSharedElementAlignLeftTop:
        startInterpolatedContentLayout.origin.x = 0;
        startInterpolatedContentLayout.origin.y = 0;
        endInterpolatedContentLayout.origin.x = 0;
        endInterpolatedContentLayout.origin.y = 0;
        break;
      case RNSharedElementAlignLeftCenter:
        startInterpolatedContentLayout.origin.x = 0;
        startInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - startInterpolatedContentLayout.size.height) / 2;
        endInterpolatedContentLayout.origin.x = 0;
        endInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - endInterpolatedContentLayout.size.height) / 2;
        break;
      case RNSharedElementAlignLeftBottom:
        startInterpolatedContentLayout.origin.x = 0;
        startInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - startInterpolatedContentLayout.size.height;
        endInterpolatedContentLayout.origin.x = 0;
        endInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - endInterpolatedContentLayout.size.height;
        break;
      case RNSharedElementAlignRightTop:
        startInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - startInterpolatedContentLayout.size.width;
        startInterpolatedContentLayout.origin.y = 0;
        endInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - endInterpolatedContentLayout.size.width;
        endInterpolatedContentLayout.origin.y = 0;
        break;
      case RNSharedElementAlignRightCenter:
        startInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - startInterpolatedContentLayout.size.width;
        startInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - startInterpolatedContentLayout.size.height) / 2;
        endInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - endInterpolatedContentLayout.size.width;
        endInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - endInterpolatedContentLayout.size.height) / 2;
        break;
      case RNSharedElementAlignRightBottom:
        startInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - startInterpolatedContentLayout.size.width;
        startInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - startInterpolatedContentLayout.size.height;
        endInterpolatedContentLayout.origin.x = interpolatedLayout.size.width - endInterpolatedContentLayout.size.width;
        endInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - endInterpolatedContentLayout.size.height;
        break;
      case RNSharedElementAlignCenterTop:
        startInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - startInterpolatedContentLayout.size.width) / 2;
        startInterpolatedContentLayout.origin.y = 0;
        endInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - endInterpolatedContentLayout.size.width) / 2;
        endInterpolatedContentLayout.origin.y = 0;
        break;
      case RNSharedElementAlignAuto:
      case RNSharedElementAlignCenterCenter:
        startInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - startInterpolatedContentLayout.size.width) / 2;
        startInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - startInterpolatedContentLayout.size.height) / 2;
        endInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - endInterpolatedContentLayout.size.width) / 2;
        endInterpolatedContentLayout.origin.y = (interpolatedLayout.size.height - endInterpolatedContentLayout.size.height) / 2;
        break;
      case RNSharedElementAlignCenterBottom:
        startInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - startInterpolatedContentLayout.size.width) / 2;
        startInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - startInterpolatedContentLayout.size.height;
        endInterpolatedContentLayout.origin.x = (interpolatedLayout.size.width - endInterpolatedContentLayout.size.width) / 2;
        endInterpolatedContentLayout.origin.y = interpolatedLayout.size.height - endInterpolatedContentLayout.size.height;
        break;
    }
    
    // Update start node
    contentView1.frame = startInterpolatedContentLayout;
    
    // Update end node
    contentView2.frame = endInterpolatedContentLayout;
    
    // Fade
    if (_animation == RNSharedElementAnimationFadeIn) {
      // Fade-in
      contentView1.layer.opacity = 0.0f;
      contentView2.layer.opacity = MIN(MAX(_nodePosition, 0.0f), 1.0f);
    }
    else if (_animation == RNSharedElementAnimationFadeOut) {
      // Fade-out
      contentView1.layer.opacity = 1.0f - MIN(MAX(_nodePosition, 0.0f), 1.0f);
      contentView2.layer.opacity = 0.0f;
    }
    else {
      // Cross-fade
      contentView1.layer.opacity = 1.0f - MIN(MAX(_nodePosition, 0.0f), 1.0f);
      contentView2.layer.opacity = MIN(MAX(_nodePosition, 0.0f), 1.0f);
    }
  }

  if (shouldLogGeometry) {
    DebugLog(@"[RNSE:%ld %@] draw pos=%.3f bucket=%ld outerFrame=%@ innerFrame=%@ maskFrame=%@ content1Frame=%@ content1Alpha=%.3f content1Class=%@ content2Frame=%@ content2Alpha=%.3f content2Class=%@ primaryImageSize=%@ secondaryImageSize=%@ animation=%ld resize=%ld align=%ld",
             (long)_debugId,
             RNSharedElementTransitionName(_debugName),
             _nodePosition,
             (long)geometryBucket,
             RNSharedElementRectString(_outerStyleView.frame),
             RNSharedElementRectString(_innerClipView.frame),
             RNSharedElementRectString(_maskLayer.frame),
             RNSharedElementRectString(contentView1.frame),
             contentView1.layer.opacity,
             NSStringFromClass(contentView1.class),
             contentView2 ? RNSharedElementRectString(contentView2.frame) : @"nil",
             contentView2 ? contentView2.layer.opacity : 0.0f,
             contentView2 ? NSStringFromClass(contentView2.class) : @"nil",
             NSStringFromCGSize(_primaryImageView.image ? _primaryImageView.image.size : CGSizeZero),
             NSStringFromCGSize(_secondaryImageView.image ? _secondaryImageView.image.size : CGSizeZero),
             (long)_animation,
             (long)_resize,
             (long)_align);
  }

  BOOL shouldLogActualFrames = shouldLogGeometry && RNSharedElementShouldLogActualFrames(_debugName);
  if (shouldLogActualFrames) {
    DebugLog(@"[RNSE:%ld %@] actual pos=%.3f bucket=%ld selfWindow=%@ superWindow=%@ %@ %@ %@ %@ %@ %@",
             (long)_debugId,
             RNSharedElementTransitionName(_debugName),
             _nodePosition,
             (long)geometryBucket,
             RNSharedElementWindowFrame(self),
             RNSharedElementWindowFrame(self.superview),
             RNSharedElementViewStateString(@"self", self),
             RNSharedElementViewStateString(@"outer", _outerStyleView),
             RNSharedElementViewStateString(@"inner", _innerClipView),
             RNSharedElementViewStateString(@"content1", contentView1),
             contentView2 ? RNSharedElementViewStateString(@"content2", contentView2) : @"content2=nil",
             RNSharedElementLayerStateString(@"mask", _maskLayer));

    __weak RNSharedElementTransition* weakSelf = self;
    UIView* loggedContentView1 = contentView1;
    UIView* loggedContentView2 = contentView2;
    CGFloat loggedPosition = _nodePosition;
    NSInteger loggedBucket = geometryBucket;
    dispatch_async(dispatch_get_main_queue(), ^{
      RNSharedElementTransition* strongSelf = weakSelf;
      if (strongSelf == nil) return;
      DebugLog(@"[RNSE:%ld %@] actual-async pos=%.3f currentPos=%.3f bucket=%ld selfWindow=%@ superWindow=%@ %@ %@ %@ %@ %@ %@",
               (long)strongSelf->_debugId,
               RNSharedElementTransitionName(strongSelf->_debugName),
               loggedPosition,
               strongSelf->_nodePosition,
               (long)loggedBucket,
               RNSharedElementWindowFrame(strongSelf),
               RNSharedElementWindowFrame(strongSelf.superview),
               RNSharedElementViewStateString(@"self", strongSelf),
               RNSharedElementViewStateString(@"outer", strongSelf->_outerStyleView),
               RNSharedElementViewStateString(@"inner", strongSelf->_innerClipView),
               RNSharedElementViewStateString(@"content1", loggedContentView1),
               loggedContentView2 ? RNSharedElementViewStateString(@"content2", loggedContentView2) : @"content2=nil",
               RNSharedElementLayerStateString(@"mask", strongSelf->_maskLayer));
    });
  }
  
  // Fire events
  if ((startAncestor.style != nil) && !startAncestor.hasCalledOnMeasure) {
    startAncestor.hasCalledOnMeasure = YES;
    startItem.hasCalledOnMeasure = NO;
    CGRect ancestorLayout = [self.superview convertRect:startAncestor.style.layout fromView:nil];
    [self fireMeasureEvent:startAncestor layout:ancestorLayout visibleLayout:ancestorLayout contentLayout:ancestorLayout];
  }
  if ((startItem.style != nil) && !startItem.hasCalledOnMeasure) {
    startItem.hasCalledOnMeasure = YES;
    [self fireMeasureEvent:startItem layout:startLayout visibleLayout:startVisibleLayout contentLayout:startContentLayout];
  }
  if ((endAncestor.style != nil) && !endAncestor.hasCalledOnMeasure) {
    endAncestor.hasCalledOnMeasure = YES;
    endItem.hasCalledOnMeasure = NO;
    CGRect ancestorLayout = [self.superview convertRect:endAncestor.style.layout fromView:nil];
    [self fireMeasureEvent:endAncestor layout:ancestorLayout visibleLayout:ancestorLayout contentLayout:ancestorLayout];
  }
  if ((endItem.style != nil) && !endItem.hasCalledOnMeasure) {
    endItem.hasCalledOnMeasure = YES;
    [self fireMeasureEvent:endItem layout:endLayout visibleLayout:endVisibleLayout contentLayout:endContentLayout];
  }
}

- (void) reactSetFrame:(CGRect)frame
{
  // Only after the frame bounds have been set by the RN layout-system
  // we schedule a layout-fetch to run after these updates to ensure
  // that Yoga/UIManager has finished the initial layout pass.
  //NSLog(@"reactSetFrame: %@", NSStringFromCGRect(frame));
  [self startTransitionIfNeeded:@"reactSetFrame"];
  
  // When react attempts to change the frame on this view,
  // override that and apply our own measured frame and styles
  [self updateStyle];
  [self updateNodeVisibility];
}

@end
