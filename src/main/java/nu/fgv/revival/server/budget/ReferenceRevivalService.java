/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reference figures from earlier revivals: the super admin keeps them; super admins,
 * observers and the directeurs and steering groups of revivals they can see read them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ReferenceRevivalService {

    static final int MIN_YEAR = 1948;
    static final int MAX_YEAR = 2100;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");

    private final ReferenceRevivalRepository repository;
    private final BudgetCategoryService categoryService;
    private final GuideAccess guideAccess;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public ReferenceRevivalService(final ReferenceRevivalRepository repository,
                                   final BudgetCategoryService categoryService,
                                   final GuideAccess guideAccess,
                                   final CallerService callerService,
                                   final AccessPolicy accessPolicy,
                                   final Clock clock) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.guideAccess = guideAccess;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static void validAmount(final @Nullable BigDecimal amount) {
        if (amount != null && (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0)) {
            throw new BadRequestException("error.reference.figure.amount", MAX_AMOUNT.toPlainString());
        }
    }

    /**
     * Newest year first.
     */
    @Transactional(readOnly = true)
    public List<ReferenceRevivalDto> list() {
        readable();

        final Comparator<String> order = categoryOrder();

        return repository.findAllByOrderByYearDescLabelAscIdAsc().stream().map(reference -> ReferenceRevivalDto.of(reference, order)).toList();
    }

    @Transactional(readOnly = true)
    public ReferenceRevivalDto get(final Long id) {
        readable();
        return ReferenceRevivalDto.of(repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ReferenceRevival.class, id)),
                categoryOrder());
    }

    /**
     * Every reference, newest year first, for the figures beside a budget's categories. No caller check: callers have
     * checked that the caller reads the budget, which implies reading the references.
     */
    @Transactional(readOnly = true)
    public List<ReferenceRevivalDto> all() {
        final Comparator<String> order = categoryOrder();

        return repository.findAllByOrderByYearDescLabelAscIdAsc().stream().map(reference -> ReferenceRevivalDto.of(reference, order)).toList();
    }

    @Transactional
    public ReferenceRevivalDto create(final Definition definition) {
        manageable();
        AuditContext.operation("referenceRevivalCreate");

        final ReferenceRevival reference = new ReferenceRevival();

        reference.setAutomatic(false);
        define(reference, definition);
        return ReferenceRevivalDto.of(repository.saveAndFlush(reference), categoryOrder());
    }

    @Transactional
    public ReferenceRevivalDto update(final Long id, final Long version, final Definition definition) {
        manageable();

        final ReferenceRevival reference = changeable(id, version);

        AuditContext.operation("referenceRevivalUpdate");
        define(reference, definition);
        // A change of the figures alone does not touch the reference itself, so its version would not move on
        reference.setLastModifiedAt(clock.instant());
        return ReferenceRevivalDto.of(repository.saveAndFlush(reference), categoryOrder());
    }

    @Transactional
    public void delete(final Long id, final Long version) {
        manageable();

        final ReferenceRevival reference = changeable(id, version);

        AuditContext.operation("referenceRevivalDelete");
        repository.delete(reference);
        repository.flush();
    }

    /**
     * Keeps a revival's figures as an automatic reference, before the revival is deleted or anonymized. No
     * caller check: the deletion has checked. Nothing is kept when the revival has no figures.
     *
     * @param totals the budgeted and actual total per category id
     */
    @Transactional
    public @Nullable ReferenceRevival keep(final String label, final int year, final @Nullable Integer participants,
                                           final Map<String, FigureInput> totals) {
        if (totals.isEmpty()) {
            return null;
        }

        final ReferenceRevival reference = new ReferenceRevival();

        reference.setLabel(label.length() > ReferenceRevival.LABEL_MAX_LENGTH ? label.substring(0, ReferenceRevival.LABEL_MAX_LENGTH) : label);
        reference.setYear(year);
        reference.setParticipants(participants == null ? null : Math.clamp(participants, 0, ReferenceRevival.MAX_PARTICIPANTS));
        reference.setAutomatic(true);
        totals.values().forEach(total -> {
            final ReferenceFigure figure = new ReferenceFigure(reference, total.categoryId());

            figure.setBudgeted(total.budgeted());
            figure.setActual(total.actual());
            reference.getFigures().add(figure);
        });
        return repository.saveAndFlush(reference);
    }

    private Caller readable() {
        final Caller caller = callerService.current();

        accessPolicy.requireReadReferenceFigures(caller, guideAccess.callerRevivals(caller));
        return caller;
    }

    private void manageable() {
        accessPolicy.requireManageReferenceFigures(callerService.current());
    }

    private ReferenceRevival changeable(final Long id, final Long version) {
        final ReferenceRevival reference = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ReferenceRevival.class, id));

        if (!Objects.equals(version, reference.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return reference;
    }

    private Comparator<String> categoryOrder() {
        final Map<String, Integer> positions = categoryService.all().stream()
                .collect(Collectors.toMap(BudgetCategory::getId, BudgetCategory::getPosition));

        return Comparator.comparing((String id) -> positions.getOrDefault(id, Integer.MAX_VALUE)).thenComparing(Function.identity());
    }

    private void define(final ReferenceRevival reference, final Definition definition) {
        final String label = definition.label() == null ? "" : definition.label().strip();

        if (label.isEmpty() || label.length() > ReferenceRevival.LABEL_MAX_LENGTH) {
            throw new BadRequestException("error.reference.label", ReferenceRevival.LABEL_MAX_LENGTH);
        }
        if (definition.year() == null || definition.year() < MIN_YEAR || definition.year() > MAX_YEAR) {
            throw new BadRequestException("error.reference.year", MIN_YEAR, MAX_YEAR);
        }
        if (definition.participants() != null && (definition.participants() < 0 || definition.participants() > ReferenceRevival.MAX_PARTICIPANTS)) {
            throw new BadRequestException("error.reference.participants", ReferenceRevival.MAX_PARTICIPANTS);
        }

        final String comment = definition.comment() == null || definition.comment().isBlank() ? null : definition.comment().strip();

        if (comment != null && comment.length() > ReferenceRevival.COMMENT_MAX_LENGTH) {
            throw new BadRequestException("error.reference.comment.tooLong", ReferenceRevival.COMMENT_MAX_LENGTH);
        }
        reference.setLabel(label);
        reference.setYear(definition.year());
        reference.setParticipants(definition.participants());
        reference.setComment(comment);
        replaceFigures(reference, definition.figures() == null ? List.of() : definition.figures());
    }

    /**
     * Keeps the figures of categories that stay, so that only real changes reach the history and the unique category
     * per reference holds while rows are written.
     */
    private void replaceFigures(final ReferenceRevival reference, final List<FigureInput> figures) {
        final Set<String> seen = new HashSet<>();

        for (final FigureInput figure : figures) {
            if (!seen.add(figure.categoryId())) {
                throw new BadRequestException("error.reference.figure.duplicate");
            }
            if (figure.budgeted() == null && figure.actual() == null) {
                throw new BadRequestException("error.reference.figure.empty");
            }
            validAmount(figure.budgeted());
            validAmount(figure.actual());
        }

        final Set<String> held = reference.getFigures().stream().map(ReferenceFigure::getCategoryId).collect(Collectors.toSet());

        categoryService.requireAssignable(seen, held);

        final Map<String, FigureInput> wanted = new HashMap<>();

        figures.forEach(figure -> wanted.put(figure.categoryId(), figure));
        reference.getFigures().removeIf(figure -> !wanted.containsKey(figure.getCategoryId()));
        for (final ReferenceFigure figure : reference.getFigures()) {
            final FigureInput input = wanted.remove(figure.getCategoryId());

            figure.setBudgeted(input.budgeted());
            figure.setActual(input.actual());
        }
        figures.stream().filter(figure -> wanted.containsKey(figure.categoryId())).forEach(input -> {
            final ReferenceFigure figure = new ReferenceFigure(reference, input.categoryId());

            figure.setBudgeted(input.budgeted());
            figure.setActual(input.actual());
            reference.getFigures().add(figure);
        });
    }

    /**
     * A category's figures; at least one of the two is set.
     */
    public record FigureInput(String categoryId, @Nullable BigDecimal budgeted, @Nullable BigDecimal actual) {
    }

    /**
     * Everything the super admin sets. The figures replace every figure the reference had.
     */
    public record Definition(String label, Integer year, @Nullable Integer participants, @Nullable String comment,
                             List<FigureInput> figures) {
    }
}
