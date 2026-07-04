import Foundation

/// Mirrors the backend `AccountType` enum. Unknown values fall back to `.other` so a new server
/// type never breaks decoding. Labels mirror `frontend/src/lib/utils.ts` (French).
enum AccountType: String {
    case lep = "LEP"
    case pea = "PEA"
    case compteTitres = "COMPTE_TITRES"
    case crypto = "CRYPTO"
    case checking = "CHECKING"
    case savings = "SAVINGS"
    case realEstate = "REAL_ESTATE"
    case loan = "LOAN"
    case other = "OTHER"

    init(raw: String) {
        self = AccountType(rawValue: raw) ?? .other
    }

    var label: String {
        switch self {
        case .lep: return "LEP"
        case .pea: return "PEA"
        case .compteTitres: return "Compte-titres"
        case .crypto: return "Crypto"
        case .checking: return "Compte courant"
        case .savings: return "Épargne"
        case .realEstate: return "Immobilier"
        case .loan: return "Emprunt"
        case .other: return "Autre"
        }
    }

    /// Coarse grouping used by the accounts screen (banking / investment / real estate / other).
    var category: String {
        switch self {
        case .checking, .savings, .lep: return "Banque"
        case .pea, .compteTitres, .crypto: return "Investissement"
        case .realEstate: return "Immobilier"
        case .loan, .other: return "Autre"
        }
    }

    var categoryRank: Int {
        switch category {
        case "Banque": return 0
        case "Investissement": return 1
        case "Immobilier": return 2
        default: return 3
        }
    }
}
