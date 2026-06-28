package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.DashboardResponse.DistributionItem;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final AccountRepository accountRepository;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final PriceService priceService;
    private final AccountHoldingRepository holdingRepository;
    private final HistoryService historyService;
    private final DebtRepository debtRepository;
    private final LoanAmortizationService loanAmortizationService;

    public DashboardService(
        AccountRepository accountRepository,
        GoalService goalService,
        GoalRepository goalRepository,
        PriceService priceService,
        AccountHoldingRepository holdingRepository,
        HistoryService historyService,
        DebtRepository debtRepository,
        LoanAmortizationService loanAmortizationService
    ) {
        this.accountRepository = accountRepository;
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.priceService = priceService;
        this.holdingRepository = holdingRepository;
        this.historyService = historyService;
        this.debtRepository = debtRepository;
        this.loanAmortizationService = loanAmortizationService;
    }

    public DashboardResponse getDashboard(Long memberId, String range) {
        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId);

        // Pre-load all holdings and group by account
        Map<Long, List<AccountHolding>> holdingsByAccount = new HashMap<>();
        for (Account account : accounts) {
            holdingsByAccount.put(account.getId(), holdingRepository.findByAccount_Id(account.getId()));
        }

        // Calculate live total and invested from holdings + cash
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;

        for (Account account : accounts) {
            // Pocket sub-accounts: balance already counted in the parent wallet.
            if (account.getParentAccountId() != null) continue;

            List<AccountHolding> holdings = holdingsByAccount.get(account.getId());

            BigDecimal accountValue;
            BigDecimal accountInvested;

            if (holdings.isEmpty()) {
                accountValue = priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
                accountInvested = accountValue;
            } else {
                BigDecimal liveValue = BigDecimal.ZERO;
                BigDecimal investedValue = BigDecimal.ZERO;
                for (AccountHolding h : holdings) {
                    BigDecimal qty = h.getQuantity();
                    BigDecimal avgBuy = h.getAverageBuyIn() != null ? h.getAverageBuyIn() : BigDecimal.ZERO;

                    BigDecimal livePrice = h.getTicker() != null ? priceService.getPriceEur(h.getTicker()) : null;
                    if (livePrice == null) {
                        livePrice = h.getCurrentPrice() != null ? h.getCurrentPrice() : BigDecimal.ZERO;
                    }

                    liveValue = liveValue.add(qty.multiply(livePrice));
                    investedValue = investedValue.add(qty.multiply(avgBuy));
                }
                log.info("getDashboard: account={} holdings={} liveValue={} investedValue={}",
                    account.getId(), holdings.size(), liveValue, investedValue);
                accountValue = liveValue;
                accountInvested = investedValue;
            }

            if (account.getType() == AccountType.LOAN) {
                totalLiabilities = totalLiabilities.add(accountValue);
            } else {
                totalAssets = totalAssets.add(accountValue);
                totalInvested = totalInvested.add(accountInvested);
            }
        }

        BigDecimal totalNetWorth = totalAssets.subtract(totalLiabilities);

        log.info("getDashboard: totalAssets={}, totalLiabilities={}, totalNetWorth={}, totalInvested={}, pnl={}",
            totalAssets, totalLiabilities, totalNetWorth, totalInvested, totalNetWorth.subtract(totalInvested));

        // Build history using shared HistoryService
        List<Long> allAccountIds = accounts.stream().map(Account::getId).toList();
        int months = switch (range != null ? range : "1Y") {
            case "7D", "1M" -> 1;
            case "3M" -> 3;
            case "YTD" -> LocalDate.now().getMonthValue();
            case "ALL" -> 1200;
            default -> 12;
        };
        List<NetWorthPoint> updatedHistory = historyService.buildHistory(allAccountIds, months, memberId);

        List<DistributionItem> distribution = buildDistribution(accounts, totalNetWorth, holdingsByAccount, false);
        List<DistributionItem> rawLiabilities = buildDistribution(accounts, totalNetWorth, holdingsByAccount, true);

        // Enrich liabilities with loan parameters in one query
        List<Long> liabilityIds = rawLiabilities.stream().map(DistributionItem::accountId).toList();
        Map<Long, Debt> debtByAccountId = debtRepository.findByAccountIdIn(liabilityIds).stream()
            .collect(Collectors.toMap(d -> d.getAccount().getId(), d -> d));

        BigDecimal totalMonthlyPayment = null;
        List<DashboardResponse.LiabilityEntry> liabilities = new ArrayList<>();
        for (DistributionItem item : rawLiabilities) {
            Debt debt = debtByAccountId.get(item.accountId());
            BigDecimal monthlyPayment = null;
            Double percentPaid = null;
            if (debt != null) {
                monthlyPayment = loanAmortizationService.resolveMonthlyPayment(debt);
                BigDecimal borrowed = debt.getBorrowedAmount();
                if (borrowed != null && borrowed.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal remaining = item.balanceEur().abs();
                    BigDecimal repaid = borrowed.subtract(remaining);
                    percentPaid = repaid.divide(borrowed, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
                    percentPaid = Math.max(0.0, Math.min(100.0, percentPaid));
                }
                if (monthlyPayment != null) {
                    totalMonthlyPayment = (totalMonthlyPayment == null ? BigDecimal.ZERO : totalMonthlyPayment)
                        .add(monthlyPayment);
                }
            }
            liabilities.add(new DashboardResponse.LiabilityEntry(
                item.accountId(), item.name(), item.color(), item.balanceEur(),
                item.percentage(), item.accountType(), item.hasHoldings(),
                monthlyPayment, percentPaid
            ));
        }

        List<GoalProgressResponse> goals = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(goalService::toProgressResponse)
            .toList();

        return new DashboardResponse(totalNetWorth, totalLiabilities, totalMonthlyPayment,
            updatedHistory, distribution, liabilities, goals);
    }

    private List<DistributionItem> buildDistribution(List<Account> accounts, BigDecimal totalNetWorth,
                                                       Map<Long, List<AccountHolding>> holdingsByAccount,
                                                       boolean liabilitiesOnly) {
        List<DistributionItem> items = new ArrayList<>();

        for (Account account : accounts) {
            // Pocket sub-accounts are internal transfers from their parent wallet — their balance
            // is already included in the wallet, so including them here would double-count.
            if (account.getParentAccountId() != null) continue;
            boolean isLoan = account.getType() == AccountType.LOAN;
            if (liabilitiesOnly != isLoan) continue;

            List<AccountHolding> holdings = holdingsByAccount.getOrDefault(account.getId(), List.of());
            BigDecimal balanceEur;
            if (holdings.isEmpty()) {
                balanceEur = priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
            } else {
                balanceEur = BigDecimal.ZERO;
                for (AccountHolding h : holdings) {
                    BigDecimal qty = h.getQuantity();
                    BigDecimal livePrice = h.getTicker() != null ? priceService.getPriceEur(h.getTicker()) : null;
                    if (livePrice == null) {
                        livePrice = h.getCurrentPrice() != null ? h.getCurrentPrice() : BigDecimal.ZERO;
                    }
                    balanceEur = balanceEur.add(qty.multiply(livePrice));
                }
            }

            double percentage = totalNetWorth.compareTo(BigDecimal.ZERO) > 0
                ? balanceEur.divide(totalNetWorth, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue()
                : 0.0;

            items.add(new DistributionItem(
                account.getId(),
                account.getName(),
                account.getColor(),
                balanceEur,
                Math.round(percentage * 100.0) / 100.0,
                account.getType().name(),
                !holdings.isEmpty()
            ));
        }

        return items;
    }
}
