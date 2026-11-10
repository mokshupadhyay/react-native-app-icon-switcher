#import "FlexAppIconManager.h"

#import <UIKit/UIKit.h>

NSNotificationName const FlexAppIconDidChangeNotification = @"FlexAppIconDidChangeNotification";
NSString *const FlexAppIconPayloadKey = @"flexAppIcon";

static NSString *const PendingFlagKey = @"com.flexappicon.pending.has";
static NSString *const PendingIconKey = @"com.flexappicon.pending.icon";
static NSString *const ScheduleKey = @"com.flexappicon.schedule";

/// Lets the activation transition finish; an alert presented mid-transition can fail.
static const NSTimeInterval ActivationDelay = 0.6;
static const NSInteger MaxAttempts = 4;

static void FlexAppIconOnMain(dispatch_block_t block)
{
  if ([NSThread isMainThread]) {
    block();
  } else {
    dispatch_async(dispatch_get_main_queue(), block);
  }
}

static BOOL FlexAppIconIsPrimaryName(NSString *_Nullable name)
{
  if (name == nil || ![name isKindOfClass:[NSString class]]) return YES;
  NSString *trimmed = [name stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
  return trimmed.length == 0 || [trimmed caseInsensitiveCompare:@"default"] == NSOrderedSame;
}

/// Errors iOS reports while it cannot show the icon alert yet, notably
/// "Resource temporarily unavailable" (POSIX 35) on iOS 26.
static BOOL FlexAppIconIsTransient(NSError *error)
{
  if ([error.domain isEqualToString:NSPOSIXErrorDomain]) {
    return error.code == EAGAIN || error.code == EIO || error.code == EBUSY;
  }
  return [error.domain isEqualToString:NSCocoaErrorDomain] && error.code == NSUserCancelledError;
}

@implementation FlexAppIconManager {
  NSUInteger _evaluationGeneration;
  NSUInteger _timerGeneration;
  BOOL _applying;
}

+ (void)load
{
  // Observe activation from launch, before React Native loads the module, so a
  // change stored while the app was in the background is applied on next open.
  [self sharedManager];
}

+ (instancetype)sharedManager
{
  static FlexAppIconManager *shared;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    shared = [FlexAppIconManager new];
  });
  return shared;
}

- (instancetype)init
{
  if ((self = [super init])) {
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    [center addObserver:self
               selector:@selector(appDidBecomeActive)
                   name:UIApplicationDidBecomeActiveNotification
                 object:nil];
    if (@available(iOS 13.0, *)) {
      [center addObserver:self
                 selector:@selector(appDidBecomeActive)
                     name:UISceneDidActivateNotification
                   object:nil];
    }
  }
  return self;
}

#pragma mark - Public class API

+ (void)setIconName:(NSString *)name
{
  [[self sharedManager] setIcon:name completion:nil];
}

+ (BOOL)handleNotificationPayload:(NSDictionary *)userInfo
{
  NSDictionary *source = userInfo;
  if (source[FlexAppIconPayloadKey] == nil && [userInfo[@"data"] isKindOfClass:[NSDictionary class]]) {
    source = userInfo[@"data"];
  }
  id raw = source[FlexAppIconPayloadKey];
  if (raw == nil || (raw != [NSNull null] && ![raw isKindOfClass:[NSString class]])) return NO;
  NSString *name = FlexAppIconIsPrimaryName(raw == [NSNull null] ? nil : raw) ? nil : raw;

  id rawAt = source[[FlexAppIconPayloadKey stringByAppendingString:@"At"]];
  double at = 0;
  if ([rawAt isKindOfClass:[NSNumber class]] || [rawAt isKindOfClass:[NSString class]]) {
    at = [rawAt doubleValue];
  }

  FlexAppIconManager *manager = [self sharedManager];
  if (at > [NSDate date].timeIntervalSince1970 * 1000) {
    FlexAppIconOnMain(^{
      NSMutableArray<NSDictionary *> *entries = [NSMutableArray new];
      for (NSDictionary *entry in [manager storedSchedule]) {
        if ([entry[@"at"] doubleValue] != at) [entries addObject:entry];
      }
      [entries addObject:[FlexAppIconManager entryWithIcon:name at:at]];
      [manager setSchedule:entries];
    });
  } else {
    [manager setIcon:name completion:nil];
  }
  return YES;
}

+ (NSString *)currentIconName
{
  return [UIApplication sharedApplication].alternateIconName;
}

#pragma mark - Requests

- (void)setIcon:(NSString *)requested completion:(FlexAppIconCompletion)completion
{
  FlexAppIconOnMain(^{
    FlexAppIconCompletion done = completion ?: ^(NSDictionary *result, NSString *code, NSString *message) {
      if (code != nil) NSLog(@"[FlexAppIcon] %@: %@", code, message);
    };
    UIApplication *app = [UIApplication sharedApplication];
    if (!app.supportsAlternateIcons) {
      done(nil, @"E_NOT_SUPPORTED", @"This device or build does not support alternate app icons.");
      return;
    }

    NSString *name = [self canonicalName:requested];
    if (name == nil && !FlexAppIconIsPrimaryName(requested) && ![self isPrimaryIconName:requested]) {
      done(nil,
           @"E_UNKNOWN_ICON",
           [NSString stringWithFormat:@"No icon named \"%@\". Available: %@",
                                      requested,
                                      [[self alternateIconNames] componentsJoinedByString:@", "]]);
      return;
    }

    if ([self icon:app.alternateIconName isEqualTo:name]) {
      [self clearPending];
      done([FlexAppIconManager resultWithStatus:@"unchanged" icon:name], nil, nil);
      return;
    }

    [self storePending:name];
    // A change already in flight picks this one up when it finishes.
    if (app.applicationState != UIApplicationStateActive || self->_applying) {
      done([FlexAppIconManager resultWithStatus:@"pending" icon:name], nil, nil);
      return;
    }
    [self applyPendingAttempt:0 completion:done];
  });
}

- (NSDictionary *)state
{
  [self promoteDueSchedule];
  UIApplication *app = [UIApplication sharedApplication];
  BOOL supported = app.supportsAlternateIcons;
  NSArray<NSString *> *icons = [self alternateIconNames];
  NSMutableArray<NSString *> *issues = [NSMutableArray new];
  if (supported && icons.count == 0) {
    [issues addObject:@"No alternate icons in the built Info.plist. List the icon sets in the "
                       "ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES build setting, or add "
                       "CFBundleIcons > CFBundleAlternateIcons."];
  }
  NSString *pending = [self pendingIcon];
  NSString *current = app.alternateIconName;
  // iOS switches the icon when its alert appears but completes only once the
  // alert is dismissed; by then the change is no longer waiting.
  BOOL hasPending = [self hasPending] && ![self icon:current isEqualTo:[self canonicalName:pending]];
  return @{
    @"supported" : @(supported),
    @"current" : current ?: (id)[NSNull null],
    @"icons" : icons,
    @"hasPending" : @(hasPending),
    @"pending" : pending ?: (id)[NSNull null],
    @"schedule" : [self storedSchedule],
    @"issues" : issues,
  };
}

- (void)cancelPending
{
  FlexAppIconOnMain(^{
    [self clearPending];
  });
}

- (void)setSchedule:(NSArray<NSDictionary *> *)entries
{
  NSMutableArray<NSDictionary *> *valid = [NSMutableArray new];
  for (NSDictionary *entry in entries) {
    if (![entry isKindOfClass:[NSDictionary class]] || ![entry[@"at"] isKindOfClass:[NSNumber class]]) continue;
    id icon = entry[@"icon"];
    NSString *name = [icon isKindOfClass:[NSString class]] && !FlexAppIconIsPrimaryName(icon) ? icon : nil;
    [valid addObject:[FlexAppIconManager entryWithIcon:name at:[entry[@"at"] doubleValue]]];
  }
  [valid sortUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
    return [a[@"at"] compare:b[@"at"]];
  }];
  FlexAppIconOnMain(^{
    [self storeSchedule:valid];
    [self scheduleEvaluationAfter:0];
  });
}

#pragma mark - Applying

- (void)appDidBecomeActive
{
  [self scheduleEvaluationAfter:ActivationDelay];
}

- (void)scheduleEvaluationAfter:(NSTimeInterval)delay
{
  NSUInteger generation = ++_evaluationGeneration;
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    if (generation == self->_evaluationGeneration) [self evaluate];
  });
}

- (void)evaluate
{
  [self promoteDueSchedule];
  [self armTimer];
  if (![self hasPending] || _applying) return;
  if ([UIApplication sharedApplication].applicationState != UIApplicationStateActive) return;
  [self applyPendingAttempt:0 completion:nil];
}

- (void)applyPendingAttempt:(NSInteger)attempt completion:(FlexAppIconCompletion)completion
{
  UIApplication *app = [UIApplication sharedApplication];
  NSString *stored = [self pendingIcon];
  NSString *name = [self canonicalName:stored];
  if (name == nil && stored != nil && ![self isPrimaryIconName:stored]) {
    // The icon was removed in an app update.
    [self clearPending];
    if (completion) completion(nil, @"E_UNKNOWN_ICON", [NSString stringWithFormat:@"No icon named \"%@\".", stored]);
    return;
  }
  NSString *previous = app.alternateIconName;
  if ([self icon:previous isEqualTo:name]) {
    [self clearPending];
    if (completion) completion([FlexAppIconManager resultWithStatus:@"unchanged" icon:name], nil, nil);
    return;
  }

  _applying = YES;
  [app setAlternateIconName:name
          completionHandler:^(NSError *_Nullable error) {
            FlexAppIconOnMain(^{
              [self finishApplying:name
                          previous:previous
                             error:error
                           attempt:attempt
                        completion:completion];
            });
          }];
}

- (void)finishApplying:(NSString *)name
              previous:(NSString *)previous
                 error:(NSError *)error
               attempt:(NSInteger)attempt
            completion:(FlexAppIconCompletion)completion
{
  _applying = NO;
  if (error == nil) {
    // A newer request may have replaced the pending icon while this one ran.
    if ([self icon:[self pendingIcon] isEqualTo:name]) [self clearPending];
    [[NSNotificationCenter defaultCenter] postNotificationName:FlexAppIconDidChangeNotification
                                                        object:self
                                                      userInfo:@{
                                                        @"icon" : name ?: (id)[NSNull null],
                                                        @"previous" : previous ?: (id)[NSNull null],
                                                      }];
    if (completion) completion([FlexAppIconManager resultWithStatus:@"applied" icon:name], nil, nil);
    if ([self hasPending]) [self scheduleEvaluationAfter:0];
    return;
  }

  UIApplication *app = [UIApplication sharedApplication];
  if (FlexAppIconIsTransient(error) || app.applicationState != UIApplicationStateActive) {
    if (attempt + 1 < MaxAttempts && app.applicationState == UIApplicationStateActive) {
      NSTimeInterval delay = 0.5 * (attempt + 1);
      dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (![self hasPending] || app.applicationState != UIApplicationStateActive) {
          if (completion) completion([FlexAppIconManager resultWithStatus:@"pending" icon:name], nil, nil);
          return;
        }
        [self applyPendingAttempt:attempt + 1 completion:completion];
      });
      return;
    }
    // Kept pending: retried the next time the app becomes active.
    if (completion) completion([FlexAppIconManager resultWithStatus:@"pending" icon:name], nil, nil);
    return;
  }

  [self clearPending];
  if (completion) completion(nil, @"E_FAILED", error.localizedDescription ?: @"The system refused the icon change.");
}

#pragma mark - Schedule

- (void)promoteDueSchedule
{
  NSArray<NSDictionary *> *entries = [self storedSchedule];
  if (entries.count == 0) return;
  double now = [NSDate date].timeIntervalSince1970 * 1000;
  NSMutableArray<NSDictionary *> *remaining = [NSMutableArray new];
  NSDictionary *due = nil;
  for (NSDictionary *entry in entries) {
    if ([entry[@"at"] doubleValue] <= now) {
      due = entry;
    } else {
      [remaining addObject:entry];
    }
  }
  if (due == nil) return;
  [self storeSchedule:remaining];
  id icon = due[@"icon"];
  [self storePending:[icon isKindOfClass:[NSString class]] ? icon : nil];
}

/// While the app is active, wake up for the next schedule entry.
- (void)armTimer
{
  NSUInteger generation = ++_timerGeneration;
  NSDictionary *next = [self storedSchedule].firstObject;
  if (next == nil) return;
  double delay = ([next[@"at"] doubleValue] - [NSDate date].timeIntervalSince1970 * 1000) / 1000;
  // Long waits are re-armed on the next activation instead of keeping a timer alive.
  if (delay > 24 * 60 * 60) return;
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(MAX(delay, 0) * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    if (generation == self->_timerGeneration) [self evaluate];
  });
}

- (NSArray<NSDictionary *> *)storedSchedule
{
  NSArray *raw = [[NSUserDefaults standardUserDefaults] arrayForKey:ScheduleKey];
  NSMutableArray<NSDictionary *> *entries = [NSMutableArray new];
  for (id entry in raw) {
    if ([entry isKindOfClass:[NSDictionary class]] && [entry[@"at"] isKindOfClass:[NSNumber class]]) {
      [entries addObject:[FlexAppIconManager entryWithIcon:entry[@"icon"] at:[entry[@"at"] doubleValue]]];
    }
  }
  return entries;
}

/// Property lists cannot hold `NSNull`, so the primary icon is stored without an `icon` key.
- (void)storeSchedule:(NSArray<NSDictionary *> *)entries
{
  NSMutableArray<NSDictionary *> *stored = [NSMutableArray new];
  for (NSDictionary *entry in entries) {
    id icon = entry[@"icon"];
    [stored addObject:[icon isKindOfClass:[NSString class]] ? @{@"icon" : icon, @"at" : entry[@"at"]}
                                                            : @{@"at" : entry[@"at"]}];
  }
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  if (stored.count == 0) {
    [defaults removeObjectForKey:ScheduleKey];
  } else {
    [defaults setObject:stored forKey:ScheduleKey];
  }
}

/// The bridged form; `NSNull` marks the primary icon.
+ (NSDictionary *)entryWithIcon:(id)icon at:(double)at
{
  return @{@"icon" : [icon isKindOfClass:[NSString class]] ? icon : (id)[NSNull null], @"at" : @(at)};
}

#pragma mark - Pending storage

- (BOOL)hasPending
{
  return [[NSUserDefaults standardUserDefaults] boolForKey:PendingFlagKey];
}

- (nullable NSString *)pendingIcon
{
  return [[NSUserDefaults standardUserDefaults] stringForKey:PendingIconKey];
}

- (void)storePending:(nullable NSString *)name
{
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults setBool:YES forKey:PendingFlagKey];
  if (name == nil) {
    [defaults removeObjectForKey:PendingIconKey];
  } else {
    [defaults setObject:name forKey:PendingIconKey];
  }
}

- (void)clearPending
{
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults removeObjectForKey:PendingFlagKey];
  [defaults removeObjectForKey:PendingIconKey];
}

#pragma mark - Icon names

/// Alternate icon names from the built Info.plist. Asset catalog builds list
/// them there too when `ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES` is set.
- (NSArray<NSString *> *)alternateIconNames
{
  NSMutableSet<NSString *> *names = [NSMutableSet new];
  NSDictionary *info = [NSBundle mainBundle].infoDictionary;
  for (NSString *key in @[ @"CFBundleIcons", @"CFBundleIcons~ipad" ]) {
    NSDictionary *icons = info[key];
    if (![icons isKindOfClass:[NSDictionary class]]) continue;
    NSDictionary *alternates = icons[@"CFBundleAlternateIcons"];
    if ([alternates isKindOfClass:[NSDictionary class]]) [names addObjectsFromArray:alternates.allKeys];
  }
  return [names.allObjects sortedArrayUsingSelector:@selector(compare:)];
}

- (BOOL)isPrimaryIconName:(nullable NSString *)name
{
  if (![name isKindOfClass:[NSString class]]) return NO;
  NSDictionary *icons = [NSBundle mainBundle].infoDictionary[@"CFBundleIcons"];
  id primary = [icons isKindOfClass:[NSDictionary class]] ? icons[@"CFBundlePrimaryIcon"] : nil;
  NSString *primaryName = [primary isKindOfClass:[NSDictionary class]] ? primary[@"CFBundleIconName"] : primary;
  return [primaryName isKindOfClass:[NSString class]] && [primaryName isEqualToString:name];
}

/// The configured spelling of `name`, or `nil` for the primary icon or an unknown name.
- (nullable NSString *)canonicalName:(nullable NSString *)name
{
  if (FlexAppIconIsPrimaryName(name) || [self isPrimaryIconName:name]) return nil;
  NSString *trimmed = [name stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
  NSArray<NSString *> *names = [self alternateIconNames];
  if ([names containsObject:trimmed]) return trimmed;
  for (NSString *candidate in names) {
    if ([candidate caseInsensitiveCompare:trimmed] == NSOrderedSame) return candidate;
  }
  return nil;
}

- (BOOL)icon:(nullable NSString *)a isEqualTo:(nullable NSString *)b
{
  return (a == nil && b == nil) || [a isEqualToString:b];
}

+ (NSDictionary *)resultWithStatus:(NSString *)status icon:(nullable NSString *)icon
{
  return @{@"status" : status, @"icon" : icon ?: (id)[NSNull null]};
}

@end
