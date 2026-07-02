package com.picsou.service;

import com.picsou.dto.RevolutCsvNamingResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for the Revolut CSV naming reconciliation: amount+date match, ambiguous case flagged,
 * and names never auto-applied.
 */
@ExtendWith(MockitoExtension.class)
class RevolutCsvNamingTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock CategorizationService categorizationService;
    @Mock RevolutSessionRepository revolutSessionRepository;

    @InjectMocks RevolutPocketService service;

    private static final Long MEMBER_ID = 5L;

    private FamilyMember member;
    private Account pocketA;
    private Account pocketB;

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder().id(MEMBER_ID).build();
        pocketA = Account.builder()
            .id(10L).member(member).name("Pocket ••aaaaaa").type(AccountType.CHECKING)
            .provider("Revolut").parentAccountId(1L).externalAccountId("uuid-a").build();
        pocketB = Account.builder()
            .id(20L).member(member).name("Pocket ••bbbbbb").type(AccountType.CHECKING)
            .provider("Revolut").parentAccountId(1L).externalAccountId("uuid-b").build();
    }

    @Test
    void csvNaming_amountAndDateMatch_returnsSuggestion() throws IOException {
        when(accountRepository.findAllPocketsByMemberId(MEMBER_ID))
            .thenReturn(List.of(pocketA));
        when(transactionRepository.findByAccountIdOrderByDateAsc(eq(pocketA.getId())))
            .thenReturn(List.of(
                tx(pocketA, "666.00", LocalDate.of(2026, 5, 10))
            ));

        String csv = """
            Date,Description,Reference,Type,Amount,Currency
            2026-05-10,Vacances,,,666.00,EUR
            """;
        RevolutCsvNamingResponse result = service.namePocketsFromCsv(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), MEMBER_ID);

        assertThat(result.suggestions()).hasSize(1);
        RevolutCsvNamingResponse.PocketNameSuggestion s = result.suggestions().get(0);
        assertThat(s.accountId()).isEqualTo(pocketA.getId());
        assertThat(s.suggestedName()).isEqualTo("Vacances");
        assertThat(s.uncertain()).isFalse();
    }

    @Test
    void csvNaming_ambiguousMatch_flaggedAsUncertain() throws IOException {
        // Both pockets received the same amount on the same date → ambiguous.
        when(accountRepository.findAllPocketsByMemberId(MEMBER_ID))
            .thenReturn(List.of(pocketA, pocketB));
        when(transactionRepository.findByAccountIdOrderByDateAsc(eq(pocketA.getId())))
            .thenReturn(List.of(tx(pocketA, "100.00", LocalDate.of(2026, 5, 15))));
        when(transactionRepository.findByAccountIdOrderByDateAsc(eq(pocketB.getId())))
            .thenReturn(List.of(tx(pocketB, "100.00", LocalDate.of(2026, 5, 15))));

        String csv = """
            Date,Description,Reference,Type,Amount,Currency
            2026-05-15,Nourriture chats,,,100.00,EUR
            """;
        RevolutCsvNamingResponse result = service.namePocketsFromCsv(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), MEMBER_ID);

        // Both pockets get a suggestion, but both are uncertain
        assertThat(result.suggestions()).hasSize(2);
        assertThat(result.suggestions()).allMatch(RevolutCsvNamingResponse.PocketNameSuggestion::uncertain);
    }

    @Test
    void csvNaming_noMatch_returnsEmptySuggestions() throws IOException {
        when(accountRepository.findAllPocketsByMemberId(MEMBER_ID))
            .thenReturn(List.of(pocketA));
        when(transactionRepository.findByAccountIdOrderByDateAsc(eq(pocketA.getId())))
            .thenReturn(List.of(tx(pocketA, "50.00", LocalDate.of(2026, 4, 1))));

        String csv = """
            Date,Description,Reference,Type,Amount,Currency
            2026-05-01,Abonnement,,,99.99,EUR
            """;
        RevolutCsvNamingResponse result = service.namePocketsFromCsv(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), MEMBER_ID);

        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void csvNaming_namedPocketsIgnored_notInResponse() throws IOException {
        // pocketA is already named (not a placeholder) — CSV should not produce a suggestion for it.
        pocketA = Account.builder()
            .id(10L).member(member).name("Vacances").type(AccountType.CHECKING)
            .provider("Revolut").parentAccountId(1L).externalAccountId("uuid-a").build();

        when(accountRepository.findAllPocketsByMemberId(MEMBER_ID))
            .thenReturn(List.of(pocketA));
        // No call to transactionRepository for named pockets → mock returns empty by default

        String csv = """
            Date,Description,Reference,Type,Amount,Currency
            2026-05-10,Vacances,,,666.00,EUR
            """;
        RevolutCsvNamingResponse result = service.namePocketsFromCsv(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), MEMBER_ID);

        assertThat(result.suggestions()).isEmpty();
    }

    private static Transaction tx(Account account, String amount, LocalDate date) {
        return Transaction.builder()
            .id(System.nanoTime())
            .account(account)
            .date(date)
            .description("pocket inflow")
            .amount(new BigDecimal(amount))
            .nativeCurrency("EUR")
            .build();
    }
}
