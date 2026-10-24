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

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * GraphQL's {@code ReferenceRevival}. Figures are in the order of their categories.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public record ReferenceRevivalDto(Long id, String label, Integer year, @Nullable Integer participants,
                                  @Nullable String comment, boolean automatic,
                                  List<Figure> figures, Long version) {

    static ReferenceRevivalDto of(final ReferenceRevival reference, final Comparator<String> categoryOrder) {
        return new ReferenceRevivalDto(reference.getId(), reference.getLabel(), reference.getYear(), reference.getParticipants(),
                reference.getComment(), reference.isAutomatic(),
                reference.getFigures().stream()
                        .sorted(Comparator.comparing(ReferenceFigure::getCategoryId, categoryOrder))
                        .map(figure -> new Figure(figure.getCategoryId(), figure.getBudgeted(), figure.getActual()))
                        .toList(),
                reference.getVersion() == null ? 0L : reference.getVersion());
    }

    /**
     * GraphQL's {@code ReferenceFigure}; its category is resolved from the id.
     */
    public record Figure(String categoryId, @Nullable BigDecimal budgeted, @Nullable BigDecimal actual) {
    }
}
