import Foundation

extension JSONDecoder {
    /// Shared decoder for API responses. Backend JSON keys are camelCase (Java record components),
    /// so no key strategy is needed; date-shaped fields are decoded as Strings by the models and
    /// converted where needed, and monetary fields decode straight into `Decimal`.
    static let picsou: JSONDecoder = {
        JSONDecoder()
    }()
}
