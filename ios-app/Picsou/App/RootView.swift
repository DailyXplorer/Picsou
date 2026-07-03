import SwiftUI

/// Routes to the screen for the current `AppState.phase`.
struct RootView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        switch appState.phase {
        case .unconfigured:
            ServerSetupView()
        case .loggedOut:
            LoginView()
        case .locked:
            LockView()
        case .ready:
            DashboardView()
        }
    }
}
