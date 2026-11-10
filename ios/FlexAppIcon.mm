#import "FlexAppIcon.h"

#import "FlexAppIconManager.h"

static NSString *const FlexAppIconEventName = @"FlexAppIconChanged";

@implementation FlexAppIcon {
  BOOL _hasListeners;
}

RCT_EXPORT_MODULE(FlexAppIcon)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (dispatch_queue_t)methodQueue
{
  return dispatch_get_main_queue();
}

- (NSArray<NSString *> *)supportedEvents
{
  return @[ FlexAppIconEventName ];
}

- (void)startObserving
{
  _hasListeners = YES;
  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(iconDidChange:)
                                               name:FlexAppIconDidChangeNotification
                                             object:nil];
}

- (void)stopObserving
{
  _hasListeners = NO;
  [[NSNotificationCenter defaultCenter] removeObserver:self name:FlexAppIconDidChangeNotification object:nil];
}

- (void)iconDidChange:(NSNotification *)notification
{
  if (_hasListeners) [self sendEventWithName:FlexAppIconEventName body:notification.userInfo ?: @{}];
}

- (void)invalidate
{
  [[NSNotificationCenter defaultCenter] removeObserver:self];
  _hasListeners = NO;
  [super invalidate];
}

RCT_EXPORT_METHOD(getState : (RCTPromiseResolveBlock)resolve reject : (RCTPromiseRejectBlock)reject)
{
  resolve([[FlexAppIconManager sharedManager] state]);
}

RCT_EXPORT_METHOD(setIcon
                  : (NSString *)name resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject)
{
  [[FlexAppIconManager sharedManager] setIcon:name
                                   completion:^(NSDictionary *result, NSString *code, NSString *message) {
                                     if (code != nil) {
                                       reject(code, message, nil);
                                     } else {
                                       resolve(result);
                                     }
                                   }];
}

RCT_EXPORT_METHOD(cancelPending : (RCTPromiseResolveBlock)resolve reject : (RCTPromiseRejectBlock)reject)
{
  [[FlexAppIconManager sharedManager] cancelPending];
  resolve(nil);
}

RCT_EXPORT_METHOD(setSchedule
                  : (NSArray *)entries resolve
                  : (RCTPromiseResolveBlock)resolve reject
                  : (RCTPromiseRejectBlock)reject)
{
  [[FlexAppIconManager sharedManager] setSchedule:entries];
  resolve(nil);
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeFlexAppIconSpecJSI>(params);
}
#endif

@end
