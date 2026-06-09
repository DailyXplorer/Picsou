# Convention: Testing

## Structure

There is **no** `unit/` or `integration/` directory split. All tests live flat under `src/test/java/com/picsou/`, mirroring the source package structure.

```
src/test/java/com/picsou/
├── service/         # 19 service unit tests (GoalService, AccountService, FamilyService,
│   │                #   MfaService, SecurityInsightService, HoldingCompute, …)
│   └── budget/      # 13 budget tests (Categorization, MerchantNormalizer, MerchantKnowledgeBase,
│                    #   CashflowFlow, RecurringDetection, MerchantLogo, + the Postgres IT)
├── controller/      # MockMvc controller tests
├── config/          # security / config tests
├── adapter/         # external-provider adapter tests
├── validation/      # custom constraint tests
└── export/          # GDPR export tests
```

## Unit tests

**Stack:** Mockito + AssertJ — no Spring context loaded.

```java
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;

    @InjectMocks GoalService goalService;

    @Test
    void progressCalculation_onTrack() {
        // arrange
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        when(accountService.toResponse(account)).thenReturn(/* ... */);

        // act
        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        // assert
        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
    }
}
```

### Patterns

- **`@ExtendWith(MockitoExtension.class)`** with `@Mock` and `@InjectMocks` — no `MockitoAnnotations.openMocks()`.
- **Lombok builders for test data** — `Account.builder()`, `Goal.builder()` etc. No test fixtures or mother objects.
- **AssertJ** for all assertions (`assertThat`, `isEqualByComparingTo`). No JUnit `assertEquals`.
- **No Spring context** in unit tests — pure Mockito mocking.

### Naming

- **Class:** `[Class]Test` (e.g., `GoalServiceTest`).
- **Method:** descriptive, underscore-separated, e.g., `progressCalculation_onTrack`. Not strictly `should_xxx_when_yyy`.
- **One test per behavior** — a test may have multiple assertions on the same logical result, but does not test multiple scenarios.

## Integration tests

The Mockito unit test above is the overwhelming default — no Spring context, no database. Reach for
an integration test **only when the behavior under test is a property of the database itself** that
a mock (or H2) would paper over.

For that case, stand up a **Testcontainers-backed `@SpringBootTest` against a real PostgreSQL 16**,
as `BudgetSeedWriteOnReadPostgresTest` does:

```java
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)   // self-skips when no Docker daemon is present
class BudgetSeedWriteOnReadPostgresTest {

    @Container
    @ServiceConnection                            // auto-wires the datasource — no manual JDBC props
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    // ...
}
```

```bash
mvn test -Dtest=BudgetSeedWriteOnReadPostgresTest   # needs a running Docker daemon
```

Testcontainers is on the test classpath (`spring-boot-testcontainers` + `testcontainers:postgresql`
/ `junit-jupiter`), versions managed by the Spring Boot parent BOM. H2 is *also* still on the
classpath but is effectively unused — there are no `@DataJpaTest`/H2 tests — and deliberately so: H2
**silently tolerates** Postgres-illegal operations, most notably an `INSERT` inside a read-only
transaction, which real Postgres rejects with SQLSTATE `25006`. That is exactly the class of bug an
integration test exists to catch, so prefer a real Postgres container whenever DB fidelity is the
point.

> **Docker API floor.** docker-java (bundled with Testcontainers) defaults to Docker Engine API
> `1.32`, which Docker Engine 25+ rejects. Surefire pins a safe floor via the `api.version` system
> property (`<docker.api.version>`, default `1.40`, override with `-Ddocker.api.version=…`) so the
> container test works under `mvn test` on a modern daemon. With no Docker, the test self-skips and
> the rest of the suite runs untouched.

## Frontend tests

- **Unit tests:** Vitest (`vitest`) with `@testing-library/react` and `jsdom`.
- **E2E tests:** Playwright (`@playwright/test`).
- Run commands:
  ```bash
  bunx vitest run         # unit tests
  bun run test:e2e        # E2E tests
  ```

## Running tests

```bash
# Backend — all tests
mvn test

# Backend — single test class
mvn test -Dtest=GoalServiceTest

# Backend — single test method
mvn test -Dtest=GoalServiceTest#progressCalculation_onTrack
```

## Current coverage

The suite has **353 backend tests across 50 test classes** (service, controller, config, adapter,
validation, export). Service-layer unit tests dominate — 32 of the 50 classes live under `service/`
(13 of them under `service/budget/`). Five of the 353 are the Postgres integration test, which
self-skips when no Docker daemon is present. When adding coverage, prioritize:

1. **Service-layer unit tests** — mock dependencies, test business logic.
2. **DB-fidelity integration tests** — a Testcontainers `@SpringBootTest` *only* when the behavior is
   a Postgres property a mock can't reproduce (see above); H2/`@DataJpaTest` is not used.
3. **Controller integration tests** — MockMvc only when auth or validation flow needs verification.

## Don'ts

- **Never load Spring context in unit tests** — pure Mockito. A Spring context (`@SpringBootTest` on
  Testcontainers) is reserved for the rare DB-fidelity integration test.
- **Never use `MockitoAnnotations.openMocks()`** — use `@ExtendWith(MockitoExtension.class)`.
- **Never use JUnit `assertEquals`** — always AssertJ (`assertThat`).
- **Never use `@Autowired` in tests** unless it's a Testcontainers `@SpringBootTest` integration test.
- **Never create test fixtures or "mother objects"** — use Lombok builders directly.
