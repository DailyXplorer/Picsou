import SwiftUI

/// Sign-in screen. The button launches the OAuth2 + PKCE flow in a system web-auth sheet, which
/// runs the existing Picsou web login (password + TOTP).
struct LoginView: View {
    @Environment(AppState.self) private var appState
    @State private var isLoggingIn = false
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.tint)
            Text("Welcome back").font(.title.bold())
            Text("Sign in securely with your Picsou account.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if let errorMessage {
                Text(errorMessage).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
            }

            Button(action: signIn) {
                HStack {
                    if isLoggingIn { ProgressView().tint(.white) }
                    Text("Sign in")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(isLoggingIn)

            Button("Use a different server") { appState.resetServer() }
                .font(.footnote)
                .padding(.top, 4)

            Spacer()
        }
        .padding(24)
    }

    private func signIn() {
        errorMessage = nil
        isLoggingIn = true
        Task {
            do {
                try await appState.login()
            } catch {
                // A user-canceled web-auth sheet surfaces as .unauthorized — don't nag about it.
                if (error as? APIError) != .unauthorized {
                    errorMessage = (error as? APIError)?.errorDescription ?? "Sign-in failed. Please try again."
                }
            }
            isLoggingIn = false
        }
    }
}
