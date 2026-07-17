//
//  RNSharedElementSnapshot.m
//  react-native-shared-element
//

#import "RNSharedElementSnapshot.h"

@implementation RNSharedElementSnapshot

- (instancetype)initWithStyle:(RNSharedElementStyle*)style
                       content:(RNSharedElementContent*)content
{
  if ((self = [super init])) {
    _style = style;
    _content = content;
  }
  return self;
}

@end
