import SwiftUI
import UserNotifications
import AhpClientShared

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let storeKitBridge = StoreKitBridgeImpl()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        IosAppContainer.companion.shared.billingDriver.bridge = storeKitBridge
        storeKitBridge.startObservingTransactions()
        return true
    }

    // Show interactive banners even while the app is in the foreground if requested.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    // Route interactive notification actions (Approve / Deny / Reply) into IosAppContainer.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let chatUri = userInfo[IosNotificationCategories.shared.KEY_CHAT_URI] as? String ?? ""
        let itemId = userInfo[IosNotificationCategories.shared.KEY_ITEM_ID] as? String ?? ""
        let optionId = userInfo[IosNotificationCategories.shared.KEY_APPROVE_OPTION_ID] as? String
        let actionId = response.actionIdentifier
        let container = IosAppContainer.companion.shared

        switch actionId {
        case IosNotificationCategories.shared.ACTION_APPROVE:
            guard !chatUri.isEmpty, !itemId.isEmpty else {
                completionHandler()
                return
            }
            container.decideFromNotification(
                chatUri: chatUri,
                toolCallId: itemId,
                approve: true,
                optionId: optionId
            ) { _ in
                completionHandler()
            }

        case IosNotificationCategories.shared.ACTION_DENY:
            guard !chatUri.isEmpty, !itemId.isEmpty else {
                completionHandler()
                return
            }
            container.decideFromNotification(
                chatUri: chatUri,
                toolCallId: itemId,
                approve: false,
                optionId: nil
            ) { _ in
                completionHandler()
            }

        case IosNotificationCategories.shared.ACTION_REPLY:
            guard
                let textResponse = response as? UNTextInputNotificationResponse,
                !chatUri.isEmpty,
                !itemId.isEmpty
            else {
                completionHandler()
                return
            }
            let replyText = textResponse.userText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !replyText.isEmpty else {
                completionHandler()
                return
            }
            container.answerFromNotification(
                chatUri: chatUri,
                requestId: itemId,
                text: replyText
            ) { _ in
                completionHandler()
            }

        default:
            completionHandler()
        }
    }
}
