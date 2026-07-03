import SwiftUI

/// Placeholder for the read-only dashboard. The full net-worth / allocation / history screen
/// (Swift Charts) lands in the next step.
struct DashboardView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("Dashboard coming up…").foregroundStyle(.secondary)
            Button("Sign out", role: .destructive) { appState.signOut() }
                .font(.footnote)
        }
        .padding()
    }
}
