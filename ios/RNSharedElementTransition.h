//
//  RNSharedElementTransition.h
//  react-native-shared-element
//

#ifndef RNSharedElementTransition_h
#define RNSharedElementTransition_h

#import <React/RCTView.h>
#import <UIKit/UIKit.h>
#import "RNSharedElementNodeManager.h"
#import "RNSharedElementDelegate.h"

@interface RNSharedElementTransition : UIView <RNSharedElementDelegate>

// Transition progress, 0..1 in the legacy JS-driven path.
@property (nonatomic, assign) CGFloat nodePosition;
@property (nonatomic, assign) RNSharedElementAnimation animation;
@property (nonatomic, assign) RNSharedElementResize resize;
@property (nonatomic, assign) RNSharedElementAlign align;

// Fabric interop path: drive nodePosition natively via CADisplayLink.
// Timing values are in milliseconds to match JS props.
@property (nonatomic, assign) BOOL nativeDriver;
@property (nonatomic, assign) CGFloat nativeDuration;
@property (nonatomic, assign) CGFloat nativeDelay;
@property (nonatomic, assign) CGFloat nativeFrom;
@property (nonatomic, assign) CGFloat nativeTo;
@property (nonatomic, strong) RNSharedElementNode* startNode;
@property (nonatomic, strong) RNSharedElementNode* startAncestor;
@property (nonatomic, copy) RCTDirectEventBlock onMeasureNode;
@property (nonatomic, strong) RNSharedElementNode* endNode;
@property (nonatomic, strong) RNSharedElementNode* endAncestor;

- (instancetype)initWithNodeManager:(RNSharedElementNodeManager*)nodeManager;

@end

#endif
