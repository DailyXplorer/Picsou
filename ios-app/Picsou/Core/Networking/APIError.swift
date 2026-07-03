import Foundation

enum APIError: Error, LocalizedError, Equatable {
    case notConfigured
    case invalidURL
    case network(String)
    case unauthorized
    case http(status: Int, body: String?)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "No server configured."
        case .invalidURL: return "That doesn't look like a valid URL."
        case .network(let message): return "Network error: \(message)"
        case .unauthorized: return "Your session has expired. Please sign in again."
        case .http(let status, _): return "The server returned an error (HTTP \(status))."
        case .decoding: return "The server sent an unexpected response."
        }
    }
}
