#import <React/RCTEventEmitter.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNFlexAppIconSpec/RNFlexAppIconSpec.h>

@interface FlexAppIcon : RCTEventEmitter <NativeFlexAppIconSpec>
#else
#import <React/RCTBridgeModule.h>

@interface FlexAppIcon : RCTEventEmitter <RCTBridgeModule>
#endif

@end
