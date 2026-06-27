package com.picsou.service.budget;

import com.picsou.dto.CategorizationRuleRequest;
import com.picsou.dto.CategorizationRuleResponse;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.BudgetSettings;
import com.picsou.model.CategorizationRule;
import com.picsou.model.Category;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;
import com.picsou.model.Transaction;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picsou.model.AiCategorizationMode;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assigns managed {@link Category} to transactions. Precedence is strict and never inverted:
 * <ol>
 *   <li><b>USER / AUTO rules</b> — {@link #apply} runs the member's rules (highest priority
 *       first) over a transaction's counterparty/description.</li>
 *   <li><b>BRAND</b> — when no rule matches, {@link #autoCategorize} falls back to the offline
 *       {@link MerchantKnowledgeBase}: the matched brand's {@code default_category_slug} resolves
 *       to the member's own category. Zero-config — works before the member tags anything.</li>
 *   <li><b>Manual</b> — {@link #categorize} sets a category by hand and can {@link #learnRule
 *       learn} a COUNTERPARTY rule so the next occurrence is automatic (a USER rule, which wins
 *       over BRAND forever after).</li>
 *   <li><b>Bulk</b> — {@link #recategorizeUncategorized} re-runs the whole pipeline over
 *       everything still uncategorized (e.g. after a new rule, or a KB version bump).</li>
 * </ol>
 *
 * <p>{@link #enrich} always stamps {@code merchant_label} + {@code merchant_brand_id} regardless
 * of whether a category is assigned, so transactions get clean names and a brand link even when
 * they stay uncategorized. The {@code categoryRef != null} guard in {@link #apply} is the single
 * invariant that protects a user's choice from ever being overwritten.
 */
@Service
@Transactional(readOnly = true)
public class CategorizationService {

    private final CategorizationRuleRepository ruleRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final MerchantKnowledgeBase knowledgeBase;
    private final CategoryService categoryService;
    private final BudgetSettingsRepository settingsRepository;

    public CategorizationService(
        CategorizationRuleRepository ruleRepository,
        CategoryRepository categoryRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository,
        MerchantKnowledgeBase knowledgeBase,
        CategoryService categoryService,
        BudgetSettingsRepository settingsRepository
    ) {
        this.ruleRepository = ruleRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.knowledgeBase = knowledgeBase;
        this.categoryService = categoryService;
        this.settingsRepository = settingsRepository;
    }

    // ─── Rule CRUD ────────────────────────────────────────────────────────────

    public List<CategorizationRuleResponse> findAllRules(Long memberId) {
        return ruleRepository.findAllByMemberIdOrderByPriorityDescIdAsc(memberId).stream()
            .map(CategorizationRuleResponse::from)
            .toList();
    }

    @Transactional
    public CategorizationRuleResponse createRule(CategorizationRuleRequest req, Long memberId) {
        Category category = requireCategory(req.categoryId(), memberId);
        CategorizationRule rule = CategorizationRule.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .matchType(req.matchType())
            .pattern(req.pattern().trim())
            .category(category)
            .priority(req.priority() != null ? req.priority() : 0)
            .source(RuleSource.USER)
            .build();
        return CategorizationRuleResponse.from(ruleRepository.save(rule));
    }

    @Transactional
    public CategorizationRuleResponse updateRule(Long id, CategorizationRuleRequest req, Long memberId) {
        CategorizationRule rule = ruleRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.rule(id));
        rule.setMatchType(req.matchType());
        rule.setPattern(req.pattern().trim());
        rule.setCategory(requireCategory(req.categoryId(), memberId));
        if (req.priority() != null) {
            rule.setPriority(req.priority());
        }
        return CategorizationRuleResponse.from(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id, Long memberId) {
        CategorizationRule rule = ruleRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.rule(id));
        ruleRepository.delete(rule);
    }

    // ─── Auto-apply ───────────────────────────────────────────────────────────

    /**
     * Apply the given pre-loaded rules to one transaction, setting its category if a rule
     * matches. Rules must be ordered highest-priority-first (the first match wins). Returns
     * true if a category was assigned. Taking rules as a parameter lets the sync loop load
     * them once and reuse across a whole batch.
     */
    public boolean apply(Transaction tx, List<CategorizationRule> rules) {
        if (tx.getCategoryRef() != null) {
            return false; // never override an existing assignment
        }
        String counterparty = safeLower(tx.getCounterparty());
        String description = safeLower(tx.getDescription());
        for (CategorizationRule rule : rules) {
            if (matches(rule, counterparty, description)) {
                tx.setCategoryRef(rule.getCategory());
                return true;
            }
        }
        return false;
    }

    // ─── Zero-config pipeline: enrich → rules → brand fallback ─────────────────

    /**
     * Pre-loaded inputs for categorizing a batch without re-querying per transaction:
     * the member's rules (priority-desc) and their categories indexed by slug. Built once by
     * {@link #loadContext} so the sync loop reuses it across the whole window.
     */
    public record CategorizationContext(List<CategorizationRule> rules, Map<String, Category> categoriesBySlug) {}

    /**
     * Load the per-member categorization inputs once. Writable because {@link #categoriesBySlug}
     * may lazily seed the member's default categories (a no-op once seeded).
     */
    @Transactional
    public CategorizationContext loadContext(Long memberId) {
        return new CategorizationContext(
            ruleRepository.findAllByMemberIdOrderByPriorityDescIdAsc(memberId),
            categoriesBySlug(memberId));
    }

    /**
     * Full zero-config categorization for one transaction:
     * <ol>
     *   <li>{@link #enrich} — always stamp the clean label + brand link;</li>
     *   <li>respect an existing category (never override a USER/manual choice);</li>
     *   <li>try the member's USER/AUTO rules;</li>
     *   <li>fall back to the matched brand's default category.</li>
     * </ol>
     * Returns true if this call assigned a category.
     */
    public boolean autoCategorize(Transaction tx, CategorizationContext ctx) {
        enrich(tx);
        if (tx.getCategoryRef() != null) {
            return false; // already categorized — leave it (and the enrichment) alone
        }
        if (apply(tx, ctx.rules())) {
            return true;
        }
        return applyBrandFallback(tx, ctx.categoriesBySlug());
    }

    /** Convenience overload that loads the context itself (single-transaction callers). */
    @Transactional
    public boolean autoCategorize(Transaction tx, Long memberId) {
        return autoCategorize(tx, loadContext(memberId));
    }

    /**
     * Stamp {@code merchant_label} (clean name) and {@code merchant_brand_id} (offline KB match)
     * on the transaction. Pure aside from the in-memory KB lookup — no database I/O. Idempotent:
     * re-running recomputes the same values. Leaves both untouched when the raw fields are blank.
     */
    public void enrich(Transaction tx) {
        String label = MerchantNormalizer.normalize(tx.getCounterparty(), tx.getDescription());
        if (label.isEmpty()) {
            return;
        }
        tx.setMerchantLabel(label);
        knowledgeBase.match(MerchantNormalizer.matchKey(label))
            .ifPresent(brand -> tx.setMerchantBrandId(brand.id()));
    }

    /**
     * Assign the category that the transaction's matched brand maps to, if any. Reads the brand's
     * {@code default_category_slug} and resolves it against the member's categories-by-slug. No-op
     * when the transaction matched no brand, the brand is unknown, or the member lacks that slug.
     */
    private boolean applyBrandFallback(Transaction tx, Map<String, Category> categoriesBySlug) {
        Long brandId = tx.getMerchantBrandId();
        if (brandId == null) {
            return false;
        }
        return knowledgeBase.findById(brandId)
            .map(brand -> categoriesBySlug.get(brand.defaultCategorySlug()))
            .map(category -> {
                tx.setCategoryRef(category);
                return true;
            })
            .orElse(false);
    }

    /**
     * The member's non-archived categories indexed by their stable {@code slug} (the KB's join
     * key). Seeds the defaults first so a brand-new member still resolves brand fallbacks. User
     * categories without a slug are skipped; on a slug collision the first (lowest sortOrder) wins.
     */
    public Map<String, Category> categoriesBySlug(Long memberId) {
        categoryService.ensureSeeded(memberId);
        return categoryRepository.findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(memberId).stream()
            .filter(c -> c.getSlug() != null && !c.getSlug().isBlank())
            .collect(Collectors.toMap(Category::getSlug, Function.identity(), (first, dup) -> first));
    }

    private boolean matches(CategorizationRule rule, String counterparty, String description) {
        String pattern = rule.getPattern().toLowerCase(Locale.ROOT);
        if (rule.getMatchType() == RuleMatchType.COUNTERPARTY) {
            return !counterparty.isEmpty() && counterparty.equals(pattern);
        }
        // KEYWORD: substring match against either field
        return counterparty.contains(pattern) || description.contains(pattern);
    }

    // ─── Manual categorization + learning ─────────────────────────────────────

    /**
     * Assign a category to a transaction by hand. When {@code createRule} is set and the
     * transaction has a counterparty, also learn an AUTO COUNTERPARTY rule so future
     * transactions from the same counterparty categorize themselves.
     */
    @Transactional
    public void categorize(Long transactionId, Long categoryId, boolean createRule, Long memberId) {
        Transaction tx = transactionRepository.findByIdAndAccountMemberId(transactionId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.transaction(transactionId));
        Category category = requireCategory(categoryId, memberId);
        tx.setCategoryRef(category);
        transactionRepository.save(tx);

        if (createRule && tx.getCounterparty() != null && !tx.getCounterparty().isBlank()) {
            learnRule(tx.getCounterparty().trim(), categoryId, memberId);
        }
    }

    /**
     * Create an AUTO COUNTERPARTY rule for {@code counterparty → category} if one does not
     * already exist for that exact counterparty. Idempotent so repeated manual
     * categorizations of the same merchant don't pile up duplicate rules.
     */
    @Transactional
    public void learnRule(String counterparty, Long categoryId, Long memberId) {
        boolean exists = ruleRepository
            .findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(memberId, RuleMatchType.COUNTERPARTY, counterparty)
            .isPresent();
        if (exists) {
            return;
        }
        Category category = requireCategory(categoryId, memberId);
        ruleRepository.save(CategorizationRule.builder()
            .member(familyMemberRepository.getReferenceById(memberId))
            .matchType(RuleMatchType.COUNTERPARTY)
            .pattern(counterparty)
            .category(category)
            .priority(0)
            .source(RuleSource.AUTO)
            .build());
    }

    /**
     * Re-run the full pipeline (enrich → rules → brand fallback) over every still-uncategorized
     * transaction; returns the count assigned. No early-out on empty rules: the brand knowledge
     * base alone categorizes a member who has never written a rule — this is what makes the
     * "Recategorize" action useful zero-config and after a KB version bump.
     */
    @Transactional
    public int recategorizeUncategorized(Long memberId) {
        CategorizationContext ctx = loadContext(memberId);
        int assigned = 0;
        for (Transaction tx : transactionRepository.findUncategorizedByMemberId(memberId)) {
            if (autoCategorize(tx, ctx)) {
                assigned++;
            }
        }
        return assigned;
    }

    // ─── AI fallback (optional, opt-in) ────────────────────────────────────────

    /** How many recent categorized transactions to feed the model as few-shot examples. */
    private static final int FEW_SHOT_LIMIT = 8;

    /**
     * Preloaded AI context for one member: the category options to pass the model, the few-shot
     * examples, a slug→id map for applying results, plus the member's mode and threshold.
     * {@code enabled=false} when AI categorization is turned off — callers must check this first.
     */
    public record AiContext(
        List<TransactionCategorizerPort.CategoryOption> options,
        List<TransactionCategorizerPort.Example> examples,
        Map<String, Long> categoryIdBySlug,
        AiCategorizationMode mode,
        int threshold,
        boolean enabled
    ) {}

    /**
     * IDs of every transaction the deterministic pipeline left uncategorized for this member.
     * Read-only; callers pass the list to the async job which loads inputs and runs AI.
     */
    @Transactional(readOnly = true)
    public List<Long> uncategorizedIds(Long memberId) {
        return transactionRepository.findUncategorizedByMemberId(memberId).stream()
            .map(Transaction::getId)
            .toList();
    }

    /**
     * Load the AI context once per job run: categories, few-shot examples, slug→id map, and
     * the member's AI mode/threshold. Returns a disabled context when AI is off — the async
     * job must check {@link AiContext#enabled()} before proceeding.
     */
    @Transactional
    public AiContext loadAiContext(Long memberId) {
        BudgetSettings settings = settingsRepository.findByMemberId(memberId).orElse(null);
        if (settings == null || !settings.isAiCategorizationEnabled()) {
            return new AiContext(List.of(), List.of(), Map.of(),
                AiCategorizationMode.AUTO_HIGH_CONFIDENCE, 0, false);
        }
        Map<String, Category> bySlug = categoriesBySlug(memberId);
        List<TransactionCategorizerPort.CategoryOption> options = bySlug.entrySet().stream()
            .map(e -> new TransactionCategorizerPort.CategoryOption(e.getKey(), e.getValue().getName()))
            .toList();
        Map<String, Long> categoryIdBySlug = bySlug.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getId()));
        List<TransactionCategorizerPort.Example> examples = transactionRepository
            .findRecentCategorizedByMemberId(memberId, PageRequest.of(0, FEW_SHOT_LIMIT)).stream()
            .filter(t -> t.getCategoryRef() != null && t.getCategoryRef().getSlug() != null)
            .map(t -> new TransactionCategorizerPort.Example(t.getMerchantLabel(), t.getCategoryRef().getSlug()))
            .toList();
        return new AiContext(options, examples, categoryIdBySlug,
            settings.getAiMode(), settings.getAiConfidenceThreshold(), true);
    }

    /**
     * Build the {@link TransactionCategorizerPort.CategorizationInput} map for a batch of
     * transaction ids, scoped to the member. Unknown or foreign ids are silently absent.
     */
    @Transactional(readOnly = true)
    public Map<Long, TransactionCategorizerPort.CategorizationInput> inputsFor(
            Collection<Long> ids, Long memberId) {
        return transactionRepository.findAllByIdInAndAccountMemberId(ids, memberId).stream()
            .collect(Collectors.toMap(
                Transaction::getId,
                tx -> new TransactionCategorizerPort.CategorizationInput(
                    tx.getMerchantLabel() != null ? tx.getMerchantLabel() : tx.getDescription(),
                    tx.getDescription(),
                    tx.getAmount())));
    }

    /**
     * Apply the AI categorizer's answers to transactions, using the preloaded {@link AiContext}.
     * For each (txId, suggestion):
     * <ul>
     *   <li>slug not in context → skip (absent from result map);</li>
     *   <li>tx already has a managed category → skip (defensive);</li>
     *   <li>auto (mode/threshold says apply) → set {@code categoryRef}, clear AI fields, map to {@code true};</li>
     *   <li>suggest → set {@code aiSuggestedCategoryId}/{@code aiConfidence}, map to {@code false}.</li>
     * </ul>
     * Returns a map of txId→applied (true) or suggested (false).
     */
    @Transactional
    public Map<Long, Boolean> applyAiResults(
            Map<Long, TransactionCategorizerPort.CategorySuggestion> results,
            AiContext ctx,
            Long memberId) {
        Map<Long, Boolean> out = new HashMap<>();
        for (Map.Entry<Long, TransactionCategorizerPort.CategorySuggestion> entry : results.entrySet()) {
            Long txId = entry.getKey();
            TransactionCategorizerPort.CategorySuggestion suggestion = entry.getValue();
            Long catId = ctx.categoryIdBySlug().get(suggestion.categorySlug());
            if (catId == null) {
                continue; // model returned a slug the member does not have — ignore
            }
            int pct = (int) Math.round(clamp01(suggestion.confidence()) * 100);
            boolean auto = switch (ctx.mode()) {
                case AUTO_ALL -> true;
                case AUTO_HIGH_CONFIDENCE -> pct >= ctx.threshold();
                case SUGGEST -> false;
            };
            Transaction tx = transactionRepository.findByIdAndAccountMemberId(txId, memberId)
                .orElse(null);
            if (tx == null) {
                continue;
            }
            if (tx.getCategoryRef() != null) {
                continue; // defensive: never touch an already-categorized transaction
            }
            if (auto) {
                Category target = categoryRepository.findByIdAndMemberId(catId, memberId)
                    .orElse(null);
                if (target == null) {
                    continue;
                }
                tx.setCategoryRef(target);
                tx.setAiSuggestedCategoryId(null);
                tx.setAiConfidence(null);
                transactionRepository.save(tx);
                out.put(txId, true);
            } else {
                tx.setAiSuggestedCategoryId(catId);
                tx.setAiConfidence(pct);
                transactionRepository.save(tx);
                out.put(txId, false);
            }
        }
        return out;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    // ─── Uncategorized inbox ──────────────────────────────────────────────────

    /** Transactions awaiting a manual category, newest first. */
    public List<TransactionResponse> findUncategorized(Long memberId) {
        return transactionRepository.findUncategorizedByMemberId(memberId).stream()
            .map(TransactionResponse::from)
            .toList();
    }

    public List<CategoryResponse> categoriesFor(Long memberId) {
        return categoryRepository.findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(memberId).stream()
            .map(CategoryResponse::from)
            .toList();
    }

    private Category requireCategory(Long categoryId, Long memberId) {
        return categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
