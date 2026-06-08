package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    @Column(length = 100)
    private String type;

    @Column(length = 100)
    private String category;

    /** Managed budget category (replaces the free-string {@link #category} for budgeting). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category categoryRef;

    /** Creditor/debtor name from the bank — used for rule matching and recurring detection. */
    @Column(length = 255)
    private String counterparty;

    /**
     * Clean, human-readable merchant name derived from {@link #counterparty}/{@link #description}
     * by {@code MerchantNormalizer}. Always populated by the categorizer; drives nice display
     * names everywhere and is the stable identity used for recurring-payment detection.
     */
    @Column(name = "merchant_label", length = 255)
    private String merchantLabel;

    /** Matched {@code MerchantBrand} id from the offline knowledge base (nullable). */
    @Column(name = "merchant_brand_id")
    private Long merchantBrandId;

    /** Provider entry reference; deduplicates synced transactions. Null for manual ones. */
    @Column(name = "external_id", length = 255)
    private String externalId;

    /** Links this transaction to a detected {@code RecurringSeries} (nullable). */
    @Column(name = "recurring_series_id")
    private Long recurringSeriesId;

    @Column(name = "native_currency", nullable = false, length = 10)
    @Builder.Default
    private String nativeCurrency = "EUR";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", length = 20)
    private TransactionType txType;

    @Column(name = "ticker", length = 30)
    private String ticker;

    @Column(name = "quantity", precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price_per_unit", precision = 20, scale = 8)
    private BigDecimal pricePerUnit;
}
