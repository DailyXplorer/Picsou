import Foundation

/// EUR currency formatting (fr_FR grouping, e.g. "12 345 €").
enum Money {
    static let eur0 = currencyFormatter(fractionDigits: 0)
    static let eur2 = currencyFormatter(fractionDigits: 2)

    private static func currencyFormatter(fractionDigits: Int) -> NumberFormatter {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = "EUR"
        f.locale = Locale(identifier: "fr_FR")
        f.minimumFractionDigits = fractionDigits
        f.maximumFractionDigits = fractionDigits
        return f
    }

    static func format(_ value: Decimal, fractionDigits: Int = 0) -> String {
        let formatter = fractionDigits >= 2 ? eur2 : eur0
        return formatter.string(from: NSDecimalNumber(decimal: value)) ?? "—"
    }

    /// Signed variant for deltas (leading "+" on non-negative values).
    static func formatSigned(_ value: Decimal) -> String {
        let base = format(value)
        return value >= 0 ? "+\(base)" : base
    }
}

/// Whole-number percent from a 0–100 value.
enum Percent {
    static func format(_ value: Double) -> String {
        "\(Int(value.rounded()))%"
    }
}

/// Parses the backend's `LocalDate` strings ("yyyy-MM-dd").
enum DateParsing {
    static let localDate: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(identifier: "UTC")
        df.dateFormat = "yyyy-MM-dd"
        return df
    }()
}

extension Decimal {
    var doubleValue: Double { NSDecimalNumber(decimal: self).doubleValue }
}
