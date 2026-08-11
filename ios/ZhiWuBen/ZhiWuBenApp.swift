import SwiftUI

@main
struct ZhiWuBenApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .tint(Color(red: 0.10, green: 0.48, blue: 0.34))
        }
    }
}
