#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Posted on the main thread after the system applied an icon. `userInfo`
/// holds `icon` and `previous`, each an `NSString` or `NSNull` for the primary icon.
FOUNDATION_EXPORT NSNotificationName const FlexAppIconDidChangeNotification;

/// Push payload key holding the icon name; `"default"` or `""` selects the primary icon.
FOUNDATION_EXPORT NSString *const FlexAppIconPayloadKey;

typedef void (^FlexAppIconCompletion)(NSDictionary *_Nullable result,
                                      NSString *_Nullable errorCode,
                                      NSString *_Nullable errorMessage);

/// Switches the alternate app icon. iOS only allows this while the app is
/// active and always shows a system alert, so a change requested while the
/// app is inactive or in the background (for example from a silent push) is
/// stored and applied the next time the app becomes active.
///
/// Every method may be called from any thread; work runs on the main thread.
@interface FlexAppIconManager : NSObject

+ (instancetype)sharedManager;

/// Switch to `name`, or to the primary icon with `nil`.
+ (void)setIconName:(nullable NSString *)name;

/// Handle a push payload: `flexAppIcon` names the icon and an optional
/// `flexAppIconAt` (Unix milliseconds) in the future schedules it instead.
/// The keys may sit at the top level or inside a `data` dictionary.
/// Returns whether the payload contained an icon change.
+ (BOOL)handleNotificationPayload:(NSDictionary *)userInfo;

/// The icon the system shows now; `nil` for the primary icon. Main thread only.
+ (nullable NSString *)currentIconName;

- (void)setIcon:(nullable NSString *)name completion:(nullable FlexAppIconCompletion)completion;
- (NSDictionary *)state;
- (void)cancelPending;
- (void)setSchedule:(NSArray<NSDictionary *> *)entries;

@end

NS_ASSUME_NONNULL_END
