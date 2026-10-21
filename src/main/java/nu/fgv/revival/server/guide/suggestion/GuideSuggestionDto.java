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

package nu.fgv.revival.server.guide.suggestion;

import nu.fgv.revival.server.guide.GuideChanges;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.guide.Phase;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * GraphQL's {@code GuideSuggestion}. {@code authorId} and {@code decidedById} are person ids; GraphQL resolves them to
 * display names, never to email addresses (FR-042).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public record GuideSuggestionDto(Long id,
                                 GuideSuggestionKind kind,
                                 GuideSuggestionStatus status,
                                 Revival revival,
                                 Long authorId,
                                 boolean mine,
                                 GuideVersionSummaryDto madeOn,
                                 Phase phase,
                                 @Nullable String sectionKey,
                                 @Nullable String itemKey,
                                 GuideChanges.@Nullable ItemSnapshot item,
                                 @Nullable GuideItemType proposedType,
                                 @Nullable ProposedText proposedText,
                                 @Nullable String reason,
                                 @Nullable Long decidedById,
                                 @Nullable Instant decidedAt,
                                 @Nullable String decisionReason,
                                 @Nullable GuideVersionSummaryDto publishedIn,
                                 @Nullable Instant reopenedAt,
                                 Instant createdAt,
                                 Long version) {

    /**
     * GraphQL's {@code GuideSuggestionRevival}: only what the suggestion needs, not the revival's details.
     */
    public record Revival(Long id, String workingTitle, Integer year) {
    }

    /**
     * GraphQL's {@code LocalizedText} for a proposal, where either language may be missing.
     */
    public record ProposedText(String sv, @Nullable String en) {
    }
}
