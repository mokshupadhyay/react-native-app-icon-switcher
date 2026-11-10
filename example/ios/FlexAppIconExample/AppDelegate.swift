import UIKit
import UserNotifications
import react_native_app_icon_switcher

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    // Silent pushes need no permission prompt, only registration.
    application.registerForRemoteNotifications()

    // Scripted runs: `-flexPayload '{flexAppIcon=Sunset;}'` feeds a push payload
    // before the app is active, the way a push that launches the app would.
    // Launch arguments starting with `{` arrive parsed as a property list.
    if let payload = UserDefaults.standard.dictionary(forKey: "flexPayload") {
      let handled = FlexAppIconManager.handleNotificationPayload(payload)
      NSLog("[FlexAppIconExample] launch payload handled=%d", handled)
    }
    return true
  }

  // Silent push (`content-available: 1`), even while the app is in the background.
  // The icon is stored and applied the next time the app becomes active.
  func application(
    _ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable: Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
  ) {
    let handled = FlexAppIconManager.handleNotificationPayload(userInfo)
    NSLog("[FlexAppIconExample] silent push handled=%d", handled)
    completionHandler(handled ? .newData : .noData)
  }

  // Visible push delivered while the app is in the foreground.
  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    _ = FlexAppIconManager.handleNotificationPayload(notification.request.content.userInfo)
    completionHandler([.banner, .list])
  }

  // The user tapped a visible push.
  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    _ = FlexAppIconManager.handleNotificationPayload(response.notification.request.content.userInfo)
    completionHandler()
  }
}
