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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A revival's checklists follow its guide version: at creation, at the first publication, on a version change and at
 * startup.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class RevivalGuideSyncIntegrationTest extends AbstractIntegrationTest {

    private static final String DRAFT = "{ guideDraft { draft { id chapters { id key items { id key type { id } version text { sv } } } } } }";

    @Autowired
    private RevivalGuideSync sync;

    private List<String> chapterChecklists(final long revival) {
        return jdbcClient.sql("SELECT chapter_key FROM checklist WHERE revival_id = ? AND kind = 'CHAPTER'").param(revival).query(String.class).list();
    }

    private int count(final long revival, final String where) {
        return jdbcClient.sql("SELECT COUNT(*) FROM checklist_item WHERE revival_id = ? AND " + where).param(revival).query(Integer.class).single();
    }

    @Test
    void aRevivalCreatedBeforeThePublicationGetsItsChecklistsAndRequirementsWithTheFirstVersion() {
        final long revival = createRevival(ALI_BABA, 2028);

        assertThat(chapterChecklists(revival)).isEmpty();
        assertThat(count(revival, "1 = 1")).isZero();

        publishedGuide();

        assertThat(chapterChecklists(revival)).containsExactlyInAnyOrder("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        assertThat(count(revival, "required = 1")).isEqualTo(3);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_entry WHERE revival_id = ? AND origin = 'GUIDE'").param(revival).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void aVersionChangeAddsMarksRemovedAndKeepsMembersItemsLast() {
        final long v1 = publishedGuide();
        final long revival = organisingRevival();
        final long checklistC = jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND chapter_key = 'C'").param(revival).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("checklistId", checklistC, "text", "Egen punkt")));

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final Map<String, Object> draft = graphQl(SUPER_ADMIN).document(DRAFT).execute().path("guideDraft.draft")
                .entity(new ParameterizedTypeReference<Map<String, Object>>() {
                }).get();
        @SuppressWarnings("unchecked") final List<Map<String, Object>> chapters = (List<Map<String, Object>>) draft.get("chapters");
        final Map<String, Object> chapterA = chapters.stream().filter(chapter -> "A".equals(chapter.get("key"))).findFirst().orElseThrow();
        final Map<String, Object> chapterC = chapters.stream().filter(chapter -> "C".equals(chapter.get("key"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") final Map<String, Object> requirementA = ((List<Map<String, Object>>) chapterA.get("items")).stream()
                .filter(item -> "REQUIREMENT".equals(((Map<?, ?>) item.get("type")).get("id"))).findFirst().orElseThrow();

        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { guideItemRemove(id: $id, version: $version) }",
                Map.of("id", requirementA.get("id"), "version", requirementA.get("version")));
        execute(SUPER_ADMIN, "mutation($input: GuideItemCreateInput!) { guideItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("chapterId", chapterC.get("id"), "position", 0, "type", "REQUIREMENT",
                        "text", Map.of("sv", "Budgeten ska godkännas av direktionen."))));

        final long v2 = Long.parseLong(String.valueOf(draft.get("id")));

        publishGuide(v2, "v2");
        execute(DIRECTEUR, "mutation($input: RevivalGuideVersionChangeInput!) { revivalGuideVersionChange(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "fromVersionId", v1, "toVersionId", v2))).errors().verify();

        execute(STEERING_GROUP, "query($id: ID!) { checklist(id: $id) { items { required text { sv } } } }", Map.of("id", checklistC))
                .path("checklist.items[*].text.sv").entityList(String.class)
                .containsExactly("Budgeten ska godkännas av direktionen.", "Budget ska upprättas innan ni drar kostnader.", "Egen punkt");

        final long checklistA = jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND chapter_key = 'A'").param(revival).query(Long.class).single();

        execute(STEERING_GROUP, "query($id: ID!) { checklist(id: $id) { items { state reason text { sv } stateChangedBy { displayName } } } }",
                Map.of("id", checklistA))
                .path("checklist.items[0].text.sv").entity(String.class).isEqualTo(String.valueOf(((Map<?, ?>) requirementA.get("text")).get("sv")))
                .path("checklist.items[0].state").entity(String.class).isEqualTo("NOT_APPLICABLE")
                .path("checklist.items[0].reason").entity(String.class).isEqualTo("Removed from the guide in v2")
                .path("checklist.items[0].stateChangedBy").valueIsNull();
        assertThat(count(revival, "required = 1")).isEqualTo(4);
        assertThat(jdbcClient.sql("""
                SELECT DISTINCT r.operation FROM checklist_item_audit a JOIN revinfo r ON r.id = a.rev
                WHERE a.revival_id = ? AND a.required = 1 AND a.revtype = 0""").param(revival).query(String.class).list())
                .contains("revivalGuideVersionChange");
    }

    @Test
    void aChapterRemovedFromTheGuideTakesItsChecklistAlongWhenTheRevivalMovesOn() {
        final long v1 = publishedGuide();
        final long revival = organisingRevival();
        final long checklistC = jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND chapter_key = 'C'").param(revival).query(Long.class).single();
        final long own = Long.parseLong(execute(STEERING_GROUP, "mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("checklistId", checklistC, "text", "Egen punkt"))).path("checklistItemCreate.id").entity(String.class).get());
        final long logRow = Long.parseLong(execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Följ upp budgeten", "checklistItemId", own)))
                .path("logEntryCreate.id").entity(String.class).get());

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final long v2 = jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'DRAFT'").query(Long.class).single();
        final long chapterC = jdbcClient.sql("SELECT id FROM guide_chapter WHERE version_id = ? AND `key` = 'C'").param(v2).query(Long.class).single();

        execute(SUPER_ADMIN, "query($key: ID!) { guideChapterRemovalImpact(chapterKey: $key) { revivalCount itemCount doneCount } }", Map.of("key", "C"))
                .path("guideChapterRemovalImpact.revivalCount").entity(Integer.class).isEqualTo(1)
                .path("guideChapterRemovalImpact.itemCount").entity(Integer.class).isEqualTo(2);
        expectError(execute(DIRECTEUR, "query($key: ID!) { guideChapterRemovalImpact(chapterKey: $key) { revivalCount } }", Map.of("key", "C")), "FORBIDDEN");

        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { guideChapterRemove(id: $id, version: $version) }",
                Map.of("id", chapterC, "version", jdbcClient.sql("SELECT version FROM guide_chapter WHERE id = ?").param(chapterC).query(Long.class).single()))
                .errors().verify();
        publishGuide(v2, "v2");

        execute(DIRECTEUR, "query($id: ID!, $to: ID!) { revival(id: $id) { guideVersionChangeImpact(toId: $to) { chapterKey chapterTitle { sv } itemCount doneCount linkedLogEntryCount } } }",
                Map.of("id", revival, "to", v2))
                .path("revival.guideVersionChangeImpact[*].chapterKey").entityList(String.class).containsExactly("C")
                .path("revival.guideVersionChangeImpact[0].chapterTitle.sv").entity(String.class).isEqualTo("Sätt budgeten")
                .path("revival.guideVersionChangeImpact[0].itemCount").entity(Integer.class).isEqualTo(2)
                .path("revival.guideVersionChangeImpact[0].linkedLogEntryCount").entity(Integer.class).isEqualTo(1);

        execute(DIRECTEUR, "mutation($input: RevivalGuideVersionChangeInput!) { revivalGuideVersionChange(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "fromVersionId", v1, "toVersionId", v2))).errors().verify();

        assertThat(chapterChecklists(revival)).containsExactlyInAnyOrder("A", "B", "D", "E", "F", "G", "H", "I", "J");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM checklist_item WHERE checklist_id = ?").param(checklistC).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT checklist_item_id FROM log_entry WHERE id = ?").param(logRow).query(Long.class).optional()).isEmpty();
        assertThat(jdbcClient.sql("""
                        SELECT r.operation FROM checklist_audit a JOIN revinfo r ON r.id = a.rev WHERE a.id = ? AND a.revtype = 2""")
                .param(checklistC).query(String.class).list()).containsExactly("revivalGuideVersionChange");
    }

    @Test
    void theStartupRunGivesExistingRevivalsTheirChecklistsOnce() {
        final long version = publishedGuide();

        jdbcClient.sql("""
                        INSERT INTO revival (spex_id, year, status, guide_version_id, created_by, created_at, version)
                        VALUES (?, 2027, 'ORGANISING', ?, 'system', NOW(6), 0)""")
                .param(SNORRE).param(version).update();

        final long revival = jdbcClient.sql("SELECT id FROM revival WHERE spex_id = 3 AND year = 2027").query(Long.class).single();

        sync.run(new DefaultApplicationArguments());
        sync.run(new DefaultApplicationArguments());

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM checklist WHERE revival_id = ?").param(revival).query(Integer.class).single()).isEqualTo(10);
        assertThat(count(revival, "required = 1")).isEqualTo(3);
        assertThat(jdbcClient.sql("SELECT last_number FROM log_entry_counter WHERE revival_id = ?").param(revival).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT DISTINCT r.source FROM checklist_audit a JOIN revinfo r ON r.id = a.rev WHERE a.revival_id = ?""")
                .param(revival).query(String.class).list()).containsExactly("SYSTEM");
    }

    @Test
    void aSuperAdminsActionsSyncAlthoughSuperAdminsMayNotWriteChecklists() {
        publishedGuide();

        final long revival = createRevival(ALI_BABA, 2028);

        assertThat(count(revival, "required = 1")).isEqualTo(3);
        expectError(execute(SUPER_ADMIN, "mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("checklistId", jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND chapter_key = 'A'")
                        .param(revival).query(Long.class).single(), "text", "x"))), "FORBIDDEN");
    }
}
