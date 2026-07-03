import SwiftUI

/// Face ID gate shown when tokens exist but the app is locked. Prompts automatically on appear.
struct LockView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "faceid")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
            Text("Picsou is locked").font(.title2.bold())
            Text("Unlock to view your finances.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let error = appState.lastError {
                Text(error).font(.footnote).foregroundStyle(.red)
            }

            Button("Unlock") {
                Task { await appState.unlock() }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.top, 4)

            Button("Sign out", role: .destructive) { appState.signOut() }
                .font(.footnote)

            Spacer()
        }
        .padding(24)
        .task { await appState.unlock() }
    }
}
