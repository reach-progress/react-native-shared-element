//
//  RNSharedElementSnapshot.h
//  react-native-shared-element
//

#ifndef RNSharedElementSnapshot_h
#define RNSharedElementSnapshot_h

#import <Foundation/Foundation.h>
#import "RNSharedElementContent.h"
#import "RNSharedElementStyle.h"

@interface RNSharedElementSnapshot : NSObject

@property (nonatomic, strong, readonly) RNSharedElementStyle* style;
@property (nonatomic, strong, readonly) RNSharedElementContent* content;

- (instancetype)initWithStyle:(RNSharedElementStyle*)style
                       content:(RNSharedElementContent*)content;

@end

#endif
