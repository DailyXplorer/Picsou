import SwiftUI

struct RangeSelector: View {
    let selected: TimeRange
    let onSelect: (TimeRange) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(TimeRange.allCases) { range in
                    Button(range.label) { onSelect(range) }
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(
                            range == selected ? Color.accentColor : Color(.secondarySystemBackground),
                            in: Capsule()
                        )
                        .foregroundStyle(range == selected ? Color.white : Color.primary)
                }
            }
            .padding(.horizontal, 1)
        }
    }
}
