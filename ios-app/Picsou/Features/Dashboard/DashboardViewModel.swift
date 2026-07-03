import Foundation
import Observation

@MainActor
@Observable
final class DashboardViewModel {
    enum State {
        case loading
        case loaded(DashboardResponse)
        case failed(String)
    }

    private(set) var state: State = .loading
    private(set) var range: TimeRange = .year

    private let api: APIClient
    private let onAuthExpired: () -> Void

    init(api: APIClient, onAuthExpired: @escaping () -> Void) {
        self.api = api
        self.onAuthExpired = onAuthExpired
    }

    func load() async {
        do {
            let data: DashboardResponse = try await api.get(
                "api/dashboard",
                query: [URLQueryItem(name: "range", value: range.rawValue)]
            )
            state = .loaded(data)
        } catch {
            if (error as? APIError) == .unauthorized {
                onAuthExpired()
                return
            }
            state = .failed((error as? APIError)?.errorDescription ?? "Une erreur est survenue.")
        }
    }

    func select(_ range: TimeRange) {
        guard range != self.range else { return }
        self.range = range
        Task { await load() }
    }
}
