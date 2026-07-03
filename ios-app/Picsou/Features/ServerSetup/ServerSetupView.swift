import SwiftUI

/// First-launch screen: enter the self-hosted instance URL.
struct ServerSetupView: View {
    @Environment(AppState.self) private var appState
    @State private var urlText = ""
    @State private var isChecking = false
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(spacing: 10) {
                Image(systemName: "building.columns.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.tint)
                Text("Connect to Picsou").font(.title2.bold())
                Text("Enter the address of your self-hosted instance.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            VStack(alignment: .leading, spacing: 8) {
                TextField("https://picsou.example.com", text: $urlText)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textContentType(.URL)
                    .submitLabel(.go)
                    .onSubmit(submit)
                    .padding()
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red)
                }
            }

            Button(action: submit) {
                HStack {
                    if isChecking { ProgressView().tint(.white) }
                    Text("Continue")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(isChecking || urlText.trimmingCharacters(in: .whitespaces).isEmpty)

            Spacer()
        }
        .padding(24)
    }

    private func submit() {
        guard !isChecking else { return }
        errorMessage = nil
        isChecking = true
        Task {
            do {
                try await appState.configureServer(urlText)
            } catch {
                errorMessage = (error as? APIError)?.errorDescription ?? "Could not reach that server."
            }
            isChecking = false
        }
    }
}
