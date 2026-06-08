package com.picsou.service;

import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ManualTransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingComputeService holdingComputeService;
    private final FinaryPersistenceHelper finaryPersistenceHelper;
    private final CategoryRepository categoryRepository;
    private final CategorizationService categorizationService;

    private static final Set<AccountType> INVESTMENT_TYPES =
        Set.of(AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CRYPTO);

    @Transactional
    public TransactionResponse addTransaction(Long accountId, Long memberId, TransactionRequest req) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = Transaction.builder()
            .account(account)
            .date(req.date())
            .description(req.description())
            .amount(req.amount())
            .txType(req.txType())
            .ticker(req.ticker())
            .quantity(req.quantity())
            .pricePerUnit(req.pricePerUnit())
            .isManual(true)
            .nativeCurrency(req.currency() != null ? req.currency() : "EUR")
            .build();

        // Explicit category wins; otherwise run the zero-config pipeline (clean label + brand
        // link are stamped either way, and a rule or known brand may auto-assign the category).
        if (req.categoryId() != null) {
            tx.setCategoryRef(resolveCategory(req.categoryId(), memberId));
            categorizationService.enrich(tx);
        } else {
            categorizationService.autoCategorize(tx, memberId);
        }

        transactionRepository.save(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            recomputeCashBalance(account);
            finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
        }

        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransactionResponse updateTransaction(Long accountId, Long txId, Long memberId, TransactionRequest req) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = transactionRepository.findByIdAndAccountId(txId, account.getId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(txId));

        if (!tx.isManual()) {
            throw new IllegalArgumentException("Cannot edit a synced transaction");
        }

        tx.setDate(req.date());
        tx.setDescription(req.description());
        tx.setAmount(req.amount());
        if (req.txType() != null) tx.setTxType(req.txType());
        if (req.ticker() != null) tx.setTicker(req.ticker());
        if (req.quantity() != null) tx.setQuantity(req.quantity());
        if (req.pricePerUnit() != null) tx.setPricePerUnit(req.pricePerUnit());
        if (req.currency() != null) tx.setNativeCurrency(req.currency());
        if (req.categoryId() != null) tx.setCategoryRef(resolveCategory(req.categoryId(), memberId));
        transactionRepository.save(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            recomputeCashBalance(account);
            finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
        }

        return TransactionResponse.from(tx);
    }

    @Transactional
    public void deleteTransaction(Long accountId, Long txId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));

        Transaction tx = transactionRepository.findByIdAndAccountId(txId, account.getId())
            .orElseThrow(() -> ResourceNotFoundException.transaction(txId));

        if (!tx.isManual()) {
            throw new IllegalArgumentException("Cannot delete a synced transaction");
        }

        transactionRepository.delete(tx);

        if (INVESTMENT_TYPES.contains(account.getType())) {
            holdingComputeService.recomputeHoldings(account);
        } else {
            recomputeCashBalance(account);
            finaryPersistenceHelper.reconstructSnapshotsFromDb(account);
        }
    }

    private Category resolveCategory(Long categoryId, Long memberId) {
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    private void recomputeCashBalance(Account account) {
        BigDecimal sum = transactionRepository.sumAmountByAccountId(account.getId());
        account.setCurrentBalance(sum);
        accountRepository.save(account);
    }
}
