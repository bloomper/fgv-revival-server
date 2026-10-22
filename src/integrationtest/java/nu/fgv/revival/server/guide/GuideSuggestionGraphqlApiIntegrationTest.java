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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suggestions over GraphQL: who may create, decide, withdraw and see them, accepting into the draft with and without a
 * conflict, reopening on discard, and the audit trail (FR-031–FR-035, FR-015, FR-039, SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideSuggestionGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String FIELDS = "id kind status version mine author decidedBy decisionReason reopenedAt revival { title } madeOn { label } publishedIn { label } item { text { sv } } proposedText { sv }";
    private static final String CREATE = "mutation($input: GuideSuggestionCreateInput!) { guideSuggestionCreate(input: $input) { " + FIELDS + " } }";
    private static final String ACCEPT = "mutation($input: GuideSuggestionAcceptInput!) { guideSuggestionAccept(input: $input) { " + FIELDS + " } }";
    private static final String REJECT = "mutation($input: GuideSuggestionRejectInput!) { guideSuggestionReject(input: $input) { " + FIELDS + " } }";
    private static final String WITHDRAW = "mutation($id: ID!, $version: Int!) { guideSuggestionWithdraw(id: $id, version: $version) { " + FIELDS + " } }";
    private static final String LIST = "query($revivalId: ID) { guideSuggestions(revivalId: $revivalId) { " + FIELDS + " } }";

    private long version;
    private long revival;
    private String tipKey;

    @BeforeEach
    void publishedGuideAndARevival() {
        version = publishedGuide();
        revival = createRevival(ALI_BABA, 2028);
        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
        tipKey = jdbcClient.sql("SELECT `key` FROM guide_item WHERE version_id = ? AND type = 'TIP' ORDER BY id LIMIT 1").param(version).query(String.class).single();
    }

    private Map<String, Object> change(final String sv) {
        final Map<String, Object> input = new HashMap<>(Map.of("revivalId", revival, "versionId", version, "kind", "CHANGE", "itemKey", tipKey, "reason", "Inaktuellt"));

        input.put("proposedTextSv", sv);
        return Map.of("input", input);
    }

    private Map<String, Object> add() {
        return Map.of("input", Map.of("revivalId", revival, "versionId", version, "kind", "ADD", "phase", "E", "proposedType", "REQUIREMENT",
                "proposedTextSv", "Förrådet ska inventeras innan första rephelgen."));
    }

    private long create(final String user, final Map<String, Object> variables) {
        return Long.parseLong(execute(user, CREATE, variables).path("guideSuggestionCreate.id").entity(String.class).get());
    }

    private long suggestionVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM guide_suggestion WHERE id = ?").param(id).query(Long.class).single();
    }

    private long draftId() {
        return jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'DRAFT'").query(Long.class).single();
    }

    @Test
    void membersSuggestAndOthersCannot() {
        execute(STEERING_GROUP, CREATE, change("Nytt tips."))
                .path("guideSuggestionCreate.status").entity(String.class).isEqualTo("OPEN")
                .path("guideSuggestionCreate.mine").entity(Boolean.class).isEqualTo(true)
                .path("guideSuggestionCreate.author").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("guideSuggestionCreate.madeOn.label").entity(String.class).isEqualTo("v1")
                .path("guideSuggestionCreate.revival.title").entity(String.class).isEqualTo("Ali Baba 2028");
        execute(DIRECTEUR, CREATE, add()).path("guideSuggestionCreate.status").entity(String.class).isEqualTo("OPEN");

        expectError(execute(OBSERVER, CREATE, add()), "FORBIDDEN");
        expectError(execute(SUPER_ADMIN, CREATE, add()), "FORBIDDEN");
        expectError(execute(PLAIN_USER, CREATE, add()), "NOT_FOUND");

        setStatus(revival, "SETTLING");
        expectError(execute(STEERING_GROUP, CREATE, add()), "NOT_FOUND");
    }

    @Test
    void acceptingAppliesTheChangeToANewDraftAndPublishingNamesTheVersion() {
        final long id = create(STEERING_GROUP, change("Kontrollera sprittillstånd i god tid."));

        execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id), "note", "Bra")))
                .path("guideSuggestionAccept.status").entity(String.class).isEqualTo("ACCEPTED")
                .path("guideSuggestionAccept.decidedBy").entity(String.class).isEqualTo("Sara Admin")
                .path("guideSuggestionAccept.publishedIn").valueIsNull();

        final long draft = draftId();

        assertThat(jdbcClient.sql("SELECT text_sv FROM guide_item WHERE version_id = ? AND `key` = ?").param(draft).param(tipKey).query(String.class).single())
                .isEqualTo("Kontrollera sprittillstånd i god tid.");
        assertThat(jdbcClient.sql("SELECT text_sv FROM guide_item WHERE version_id = ? AND `key` = ?").param(version).param(tipKey).query(String.class).single())
                .isNotEqualTo("Kontrollera sprittillstånd i god tid.");

        publishGuide(draft, "v2");
        execute(STEERING_GROUP, LIST, Map.of("revivalId", revival))
                .path("guideSuggestions[0].publishedIn.label").entity(String.class).isEqualTo("v2");
    }

    @Test
    void anAddLandsInTheChapter() {
        final long id = create(DIRECTEUR, add());

        execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))).errors().verify();

        assertThat(jdbcClient.sql("""
                SELECT c.phase FROM guide_item i JOIN guide_chapter c ON c.id = i.chapter_id
                WHERE i.version_id = ? AND i.text_sv = 'Förrådet ska inventeras innan första rephelgen.'""")
                .param(draftId()).query(String.class).single()).isEqualTo("E");
    }

    @Test
    void anItemChangedInTheDraftIsAConflictUntilResolved() {
        final long id = create(STEERING_GROUP, change("Förslagets text."));

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final long draftItem = jdbcClient.sql("SELECT id FROM guide_item WHERE version_id = ? AND `key` = ?").param(draftId()).param(tipKey).query(Long.class).single();
        final long itemVersion = jdbcClient.sql("SELECT version FROM guide_item WHERE id = ?").param(draftItem).query(Long.class).single();

        execute(SUPER_ADMIN, "mutation($input: GuideItemUpdateInput!) { guideItemUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", draftItem, "version", itemVersion, "type", "TIP", "text", Map.of("sv", "Redan ändrat i utkastet."))))
                .errors().verify();

        expectError(execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))), "CONFLICT");

        execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id),
                "resolution", Map.of("type", "TIP", "text", Map.of("sv", "Sammanslaget.")))))
                .path("guideSuggestionAccept.status").entity(String.class).isEqualTo("ACCEPTED");
        assertThat(jdbcClient.sql("SELECT text_sv FROM guide_item WHERE id = ?").param(draftItem).query(String.class).single()).isEqualTo("Sammanslaget.");
    }

    @Test
    void rejectingKeepsTheReasonForTheAuthor() {
        final long id = create(STEERING_GROUP, change("Något."));

        execute(SUPER_ADMIN, REJECT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id), "reason", "Står redan i kapitel D.")))
                .path("guideSuggestionReject.status").entity(String.class).isEqualTo("REJECTED");
        execute(STEERING_GROUP, LIST, Map.of("revivalId", revival))
                .path("guideSuggestions[0].decisionReason").entity(String.class).isEqualTo("Står redan i kapitel D.");
        expectError(execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))), "BAD_REQUEST");
    }

    @Test
    void onlySuperAdminsDecideAndOnlyTheAuthorWithdraws() {
        final long id = create(STEERING_GROUP, change("Något."));

        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            expectError(execute(user, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))), "FORBIDDEN");
            expectError(execute(user, REJECT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))), "FORBIDDEN");
        }
        expectError(execute(DIRECTEUR, WITHDRAW, Map.of("id", id, "version", suggestionVersion(id))), "FORBIDDEN");
        expectError(execute(SUPER_ADMIN, WITHDRAW, Map.of("id", id, "version", suggestionVersion(id))), "FORBIDDEN");
        execute(STEERING_GROUP, WITHDRAW, Map.of("id", id, "version", suggestionVersion(id)))
                .path("guideSuggestionWithdraw.status").entity(String.class).isEqualTo("WITHDRAWN");
    }

    @Test
    void discardingTheDraftReopensAcceptedSuggestions() {
        final long id = create(STEERING_GROUP, change("Något."));

        execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", id, "version", suggestionVersion(id)))).errors().verify();

        final long draft = draftId();

        graphQl(SUPER_ADMIN).document("{ guideDraft { acceptedSuggestionCount } }").execute()
                .path("guideDraft.acceptedSuggestionCount").entity(Integer.class).isEqualTo(1);
        graphQl(SUPER_ADMIN).document("mutation($id: ID!, $version: Int!) { guideDraftDiscard(id: $id, version: $version) }")
                .variable("id", draft).variable("version", guideVersion(draft)).execute().errors().verify();

        execute(STEERING_GROUP, LIST, Map.of("revivalId", revival))
                .path("guideSuggestions[0].status").entity(String.class).isEqualTo("OPEN")
                .path("guideSuggestions[0].reopenedAt").hasValue()
                .path("guideSuggestions[0].decidedBy").valueIsNull();
    }

    @Test
    void visibilityFollowsTheRevival() {
        create(STEERING_GROUP, change("Något."));

        for (final String user : List.of(SUPER_ADMIN, OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            execute(user, LIST, Map.of()).path("guideSuggestions").entityList(Object.class).hasSize(1);
        }
        execute(PLAIN_USER, LIST, Map.of()).path("guideSuggestions").entityList(Object.class).hasSize(0);

        setStatus(revival, "SETTLING");
        execute(STEERING_GROUP, LIST, Map.of()).path("guideSuggestions").entityList(Object.class).hasSize(0);
        execute(SUPER_ADMIN, LIST, Map.of()).path("guideSuggestions[0].author").entity(String.class).satisfies(name -> assertThat(name).doesNotContain("@"));
    }

    @Test
    void everyStepIsAudited() {
        final long first = create(STEERING_GROUP, change("Ett."));
        final long second = create(STEERING_GROUP, change("Två."));
        final long third = create(STEERING_GROUP, change("Tre."));

        execute(SUPER_ADMIN, ACCEPT, Map.of("input", Map.of("id", first, "version", suggestionVersion(first)))).errors().verify();
        execute(SUPER_ADMIN, REJECT, Map.of("input", Map.of("id", second, "version", suggestionVersion(second)))).errors().verify();
        execute(STEERING_GROUP, WITHDRAW, Map.of("id", third, "version", suggestionVersion(third))).errors().verify();
        graphQl(SUPER_ADMIN).document("mutation($id: ID!, $version: Int!) { guideDraftDiscard(id: $id, version: $version) }")
                .variable("id", draftId()).variable("version", guideVersion(draftId())).execute().errors().verify();

        for (final String operation : List.of("guideSuggestionCreate", "guideSuggestionAccept", "guideSuggestionReject", "guideSuggestionWithdraw", "guideDraftDiscard")) {
            assertThat(jdbcClient.sql("""
                    SELECT COUNT(*) FROM guide_suggestion_audit a JOIN revinfo r ON r.id = a.rev
                    WHERE r.operation = ? AND r.modified_by NOT LIKE '%@%'""").param(operation).query(Long.class).single())
                    .as(operation)
                    .isPositive();
        }
    }
}
