package com.picsou.service.budget;

import com.picsou.dto.CategoryRequest;
import com.picsou.dto.CategoryResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Managed spending/income/transfer categories, scoped per family member.
 *
 * <p>The default set is seeded <em>lazily</em>: the first time a member reads their
 * categories ({@link #findAll}), if they have none we create the defaults. This covers
 * members that existed before 1.1.0 and members created afterwards without touching the
 * member-creation code paths ({@code FamilyService}/{@code SetupService}) or needing a
 * SQL backfill that would drift from this list.
 *
 * <p>Deletion is intentionally absent: a category may already be referenced by
 * transactions, rules, budgets and recurring series, so removal is modelled as
 * {@link #archive} (sets {@code archived = true}) — the category stops being offered for
 * new assignments but historical data keeps its label.
 */
@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public CategoryService(
        CategoryRepository categoryRepository,
        TransactionRepository transactionRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    /**
     * The default categories every member starts with. Colours are distinct per item so
     * charts read well out of the box; icons are lucide-react names the frontend maps.
     * Order here is the seeded {@code sortOrder}.
     */
    private record DefaultCategory(String slug, String name, CategoryKind kind, String color, String icon) {}

    private static final List<DefaultCategory> DEFAULTS = List.of(
        // ── Expenses ──────────────────────────────────────────────────────────
        new DefaultCategory("courses", "Courses", CategoryKind.EXPENSE, "#22c55e", "shopping-cart"),
        new DefaultCategory("restaurants", "Restaurants", CategoryKind.EXPENSE, "#f97316", "utensils"),
        new DefaultCategory("transport", "Transport", CategoryKind.EXPENSE, "#3b82f6", "car"),
        new DefaultCategory("logement", "Logement", CategoryKind.EXPENSE, "#8b5cf6", "house"),
        new DefaultCategory("factures", "Factures & énergie", CategoryKind.EXPENSE, "#eab308", "zap"),
        new DefaultCategory("sante", "Santé", CategoryKind.EXPENSE, "#ef4444", "heart-pulse"),
        new DefaultCategory("loisirs", "Loisirs", CategoryKind.EXPENSE, "#ec4899", "gamepad-2"),
        new DefaultCategory("shopping", "Shopping", CategoryKind.EXPENSE, "#14b8a6", "shopping-bag"),
        new DefaultCategory("abonnements", "Abonnements", CategoryKind.EXPENSE, "#6366f1", "repeat"),
        new DefaultCategory("voyages", "Voyages", CategoryKind.EXPENSE, "#06b6d4", "plane"),
        new DefaultCategory("divers", "Divers", CategoryKind.EXPENSE, "#94a3b8", "ellipsis"),
        // ── Income ────────────────────────────────────────────────────────────
        new DefaultCategory("salaire", "Salaire", CategoryKind.INCOME, "#16a34a", "wallet"),
        new DefaultCategory("autres-revenus", "Autres revenus", CategoryKind.INCOME, "#65a30d", "hand-coins"),
        new DefaultCategory("remboursements", "Remboursements", CategoryKind.INCOME, "#0ea5e9", "undo-2"),
        // ── Transfers (excluded from cashflow, feed allocation) ─────────────────
        new DefaultCategory("epargne", "Épargne", CategoryKind.TRANSFER, "#0891b2", "piggy-bank"),
        new DefaultCategory("investissement", "Investissement", CategoryKind.TRANSFER, "#7c3aed", "trending-up"),
        new DefaultCategory("virement-interne", "Virement interne", CategoryKind.TRANSFER, "#64748b", "arrow-left-right")
    );

    /**
     * Read-write on purpose: the first read for a member lazily seeds the defaults via
     * {@link #ensureSeeded}. That seed runs as an <em>internal</em> call, so Spring's proxy
     * is bypassed and the seed inherits THIS transaction — it must therefore be writable,
     * otherwise Postgres rejects the INSERT ("cannot execute INSERT in a read-only
     * transaction"). The class-level {@code readOnly = true} still covers the pure reads.
     */
    @Transactional
    public List<CategoryResponse> findAll(Long memberId) {
        ensureSeeded(memberId);
        return categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId).stream()
            .map(CategoryResponse::from)
            .toList();
    }

    /**
     * Seed the default categories for a member that has none yet. Public so other budget
     * services can guarantee categories exist before they aggregate; {@code REQUIRES_NEW}
     * guarantees a writable transaction even when the caller holds a {@code readOnly} one
     * (a plain {@code REQUIRED} would join — and inherit — the caller's read-only flag).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureSeeded(Long memberId) {
        if (categoryRepository.existsByMemberId(memberId)) {
            return;
        }
        FamilyMember member = familyMemberRepository.getReferenceById(memberId);
        int sortOrder = 0;
        for (DefaultCategory d : DEFAULTS) {
            categoryRepository.save(Category.builder()
                .member(member)
                .slug(d.slug())
                .name(d.name())
                .kind(d.kind())
                .color(d.color())
                .icon(d.icon())
                .isDefault(true)
                .sortOrder(sortOrder++)
                .build());
        }
    }

    @Transactional
    public CategoryResponse create(CategoryRequest req, Long memberId) {
        FamilyMember member = familyMemberRepository.getReferenceById(memberId);
        Category category = Category.builder()
            .member(member)
            .name(req.name())
            .kind(req.kind())
            .color(req.color() != null && !req.color().isBlank() ? req.color() : "#6366f1")
            .icon(req.icon())
            .isDefault(false)
            .sortOrder(req.sortOrder() != null ? req.sortOrder() : nextSortOrder(memberId))
            .build();
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest req, Long memberId) {
        Category category = getOrThrow(id, memberId);
        // Kind drives downstream behaviour (cashflow/envelopes/allocation); changing it on
        // a default is allowed but the rename/recolour is the common case.
        category.setName(req.name());
        category.setKind(req.kind());
        if (req.color() != null && !req.color().isBlank()) {
            category.setColor(req.color());
        }
        category.setIcon(req.icon());
        if (req.sortOrder() != null) {
            category.setSortOrder(req.sortOrder());
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    /** Soft-remove: keep the row (history references it) but stop offering it. */
    @Transactional
    public void archive(Long id, Long memberId) {
        Category category = getOrThrow(id, memberId);
        category.setArchived(true);
        categoryRepository.save(category);
    }

    @Transactional
    public CategoryResponse unarchive(Long id, Long memberId) {
        Category category = getOrThrow(id, memberId);
        category.setArchived(false);
        return CategoryResponse.from(categoryRepository.save(category));
    }

    private int nextSortOrder(Long memberId) {
        return categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId).stream()
            .mapToInt(Category::getSortOrder)
            .max()
            .orElse(-1) + 1;
    }

    Category getOrThrow(Long id, Long memberId) {
        return categoryRepository.findByIdAndMemberId(id, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(id));
    }
}
