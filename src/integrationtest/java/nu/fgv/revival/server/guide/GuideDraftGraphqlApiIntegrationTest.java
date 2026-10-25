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
import org.junit.jupiter.api.Test;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Editing the draft, the immutability of published versions, refusals and the audit trail.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideDraftGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String START = "mutation { guideDraftStart { draft { id basedOn { id } chapters { id key } } untranslatedCount } }";
    private static final String INTRO = "mutation($input: GuideIntroductionInput!) { guideIntroductionUpdate(input: $input) { id introduction { sv en } } }";
    private static final String CHAPTER = "mutation($input: GuideChapterInput!) { guideChapterUpdate(input: $input) { id title { sv en } } }";
    private static final String CHAPTER_CREATE = "mutation($input: GuideChapterCreateInput!) { guideChapterCreate(input: $input) { id key title { sv en } items { key } } }";
    private static final String CHAPTER_MOVE = "mutation($input: GuideChapterMoveInput!) { guideChapterMove(input: $input) { id chapters { key } } }";
    private static final String CHAPTER_REMOVE = "mutation($id: ID!, $version: Int!) { guideChapterRemove(id: $id, version: $version) }";
    private static final String SECTION_CREATE = "mutation($input: GuideSectionCreateInput!) { guideSectionCreate(input: $input) { id key title { sv } } }";
    private static final String SECTION_UPDATE = "mutation($input: GuideSectionUpdateInput!) { guideSectionUpdate(input: $input) { id title { sv } } }";
    private static final String SECTION_MOVE = "mutation($input: GuideSectionMoveInput!) { guideSectionMove(input: $input) { id key sections { key } } }";
    private static final String SECTION_REMOVE = "mutation($id: ID!, $version: Int!) { guideSectionRemove(id: $id, version: $version) }";
    private static final String ITEM_CREATE = "mutation($input: GuideItemCreateInput!) { guideItemCreate(input: $input) { id key type { id } text { sv en } } }";
    private static final String ITEM_UPDATE = "mutation($input: GuideItemUpdateInput!) { guideItemUpdate(input: $input) { id key type { id } text { sv en } } }";
    private static final String ITEM_MOVE = "mutation($input: GuideItemMoveInput!) { guideItemMove(input: $input) { id key items { key } sections { key items { key } } } }";
    private static final String ITEM_REMOVE = "mutation($id: ID!, $version: Int!) { guideItemRemove(id: $id, version: $version) }";
    private static final String PUBLISH = "mutation($input: GuideDraftPublishInput!) { guideDraftPublish(input: $input) { id } }";
    private static final String DISCARD = "mutation($id: ID!, $version: Int!) { guideDraftDiscard(id: $id, version: $version) }";

    private long id(final String table, final String where, final Object... params) {
        var sql = jdbcClient.sql("SELECT id FROM " + table + " WHERE " + where + " ORDER BY id LIMIT 1");

        for (final Object param : params) {
            sql = sql.param(param);
        }
        return sql.query(Long.class).single();
    }

    private long rowVersion(final String table, final long id) {
        return jdbcClient.sql("SELECT version FROM " + table + " WHERE id = ?").param(id).query(Long.class).single();
    }

    private String snapshot(final long versionId) {
        return String.join("|",
                jdbcClient.sql("SELECT CONCAT_WS(',', status, label, intro_sv, IFNULL(intro_en,'-'), version) FROM guide_version WHERE id = ?").param(versionId).query(String.class).list().toString(),
                jdbcClient.sql("SELECT CONCAT_WS(',', id, `key`, position, title_sv, IFNULL(title_en,'-'), version) FROM guide_chapter WHERE version_id = ? ORDER BY id").param(versionId).query(String.class).list().toString(),
                jdbcClient.sql("SELECT CONCAT_WS(',', id, chapter_id, `key`, position, title_sv, version) FROM guide_section WHERE version_id = ? ORDER BY id").param(versionId).query(String.class).list().toString(),
                jdbcClient.sql("SELECT CONCAT_WS(',', id, chapter_id, IFNULL(section_id,'-'), `key`, position, type, text_sv, IFNULL(text_en,'-'), version) FROM guide_item WHERE version_id = ? ORDER BY id").param(versionId).query(String.class).list().toString());
    }

    private List<String> chapterKeys(final long versionId) {
        return jdbcClient.sql("SELECT `key` FROM guide_chapter WHERE version_id = ? ORDER BY position").param(versionId).query(String.class).list();
    }

    private GraphQlTester.Response run(final String user, final String document, final Map<String, Object> variables) {
        return execute(user, document, variables);
    }

    @Test
    void aPublishedVersionNeverChanges() {
        final long published = publishedGuide();
        final long chapter = id("guide_chapter", "version_id = ? AND `key` = 'B'", published);
        final long section = id("guide_section", "version_id = ?", published);
        final long item = id("guide_item", "version_id = ? AND section_id IS NOT NULL", published);
        final String before = snapshot(published);
        final Map<String, Object> text = Map.of("sv", "Ändrat", "en", "Changed");

        expectError(run(SUPER_ADMIN, INTRO, Map.of("input", Map.of("versionId", published, "version", guideVersion(published), "introduction", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, CHAPTER, Map.of("input", Map.of("id", chapter, "version", rowVersion("guide_chapter", chapter), "title", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, SECTION_CREATE, Map.of("input", Map.of("chapterId", chapter, "title", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, SECTION_UPDATE, Map.of("input", Map.of("id", section, "version", rowVersion("guide_section", section), "title", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, SECTION_MOVE, Map.of("input", Map.of("id", section, "version", rowVersion("guide_section", section), "chapterId", chapter, "position", 0))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, SECTION_REMOVE, Map.of("id", section, "version", rowVersion("guide_section", section))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapter, "type", "TIP", "text", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_UPDATE, Map.of("input", Map.of("id", item, "version", rowVersion("guide_item", item), "type", "TEXT", "text", text))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_MOVE, Map.of("input", Map.of("id", item, "version", rowVersion("guide_item", item), "chapterId", chapter, "position", 0))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_REMOVE, Map.of("id", item, "version", rowVersion("guide_item", item))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, PUBLISH, Map.of("input", Map.of("id", published, "version", guideVersion(published)))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, DISCARD, Map.of("id", published, "version", guideVersion(published))), "BAD_REQUEST");

        assertThat(snapshot(published)).isEqualTo(before);
    }

    @Test
    void aDraftIsACopyAndEveryEditStaysInIt() {
        final long published = publishedGuide();

        graphQl(SUPER_ADMIN).document(START).execute()
                .path("guideDraftStart.draft.basedOn.id").entity(String.class).isEqualTo(String.valueOf(published))
                .path("guideDraftStart.draft.chapters").entityList(Object.class).hasSize(10);

        final long draft = id("guide_version", "status = 'DRAFT'");
        final String before = snapshot(published);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item WHERE version_id = ?").param(draft).query(Long.class).single())
                .isEqualTo(jdbcClient.sql("SELECT COUNT(*) FROM guide_item WHERE version_id = ?").param(published).query(Long.class).single());
        assertThat(jdbcClient.sql("SELECT `key` FROM guide_item WHERE version_id = ? ORDER BY `key`").param(draft).query(String.class).list())
                .isEqualTo(jdbcClient.sql("SELECT `key` FROM guide_item WHERE version_id = ? ORDER BY `key`").param(published).query(String.class).list());

        final long chapterC = id("guide_chapter", "version_id = ? AND `key` = 'C'", draft);
        final long chapterB = id("guide_chapter", "version_id = ? AND `key` = 'B'", draft);
        final long kalas = id("guide_section", "version_id = ? AND title_sv = 'Kalaslokal'", draft);
        final long tip = id("guide_item", "version_id = ? AND section_id = ? AND type = 'TIP'", draft, kalas);
        final String tipKey = jdbcClient.sql("SELECT `key` FROM guide_item WHERE id = ?").param(tip).query(String.class).single();

        // Add a requirement to C, at the start of its leading items
        run(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapterC, "position", 0, "type", "REQUIREMENT",
                "text", Map.of("sv", "Nytt krav.", "en", "New requirement."))))
                .path("guideItemCreate.type.id").entity(String.class).isEqualTo("REQUIREMENT");
        assertThat(jdbcClient.sql("SELECT text_sv FROM guide_item WHERE chapter_id = ? AND section_id IS NULL ORDER BY position").param(chapterC).query(String.class).list())
                .first().isEqualTo("Nytt krav.");

        // Reword and retype a tip
        run(SUPER_ADMIN, ITEM_UPDATE, Map.of("input", Map.of("id", tip, "version", rowVersion("guide_item", tip), "type", "TEXT",
                "text", Map.of("sv", "Omskrivet.", "en", ""))))
                .path("guideItemUpdate.text.en").valueIsNull()
                .path("guideItemUpdate.key").entity(String.class).isEqualTo(tipKey);

        // Move it to chapter C's leading items, keeping its key
        run(SUPER_ADMIN, ITEM_MOVE, Map.of("input", Map.of("id", tip, "version", rowVersion("guide_item", tip), "chapterId", chapterC, "position", 1)))
                .path("guideItemMove.key").entity(String.class).isEqualTo("C")
                .path("guideItemMove.items[1].key").entity(String.class).isEqualTo(tipKey);

        // Positions stay dense in the section it left
        assertThat(jdbcClient.sql("SELECT position FROM guide_item WHERE section_id = ? ORDER BY position").param(kalas).query(Integer.class).list())
                .isEqualTo(java.util.stream.IntStream.range(0, jdbcClient.sql("SELECT COUNT(*) FROM guide_item WHERE section_id = ?").param(kalas).query(Integer.class).single()).boxed().toList());

        // A section with items cannot be removed; an empty one can, after a new one is added and moved
        expectError(run(SUPER_ADMIN, SECTION_REMOVE, Map.of("id", kalas, "version", rowVersion("guide_section", kalas))), "BAD_REQUEST");
        final String newKey = run(SUPER_ADMIN, SECTION_CREATE, Map.of("input", Map.of("chapterId", chapterB, "title", Map.of("sv", "Nytt avsnitt"))))
                .path("guideSectionCreate.key").entity(String.class).get();
        final long newSection = id("guide_section", "`key` = ? AND version_id = ?", newKey, draft);

        run(SUPER_ADMIN, SECTION_MOVE, Map.of("input", Map.of("id", newSection, "version", rowVersion("guide_section", newSection), "chapterId", chapterC, "position", 0)))
                .path("guideSectionMove.sections[0].key").entity(String.class).isEqualTo(newKey);
        run(SUPER_ADMIN, SECTION_REMOVE, Map.of("id", newSection, "version", rowVersion("guide_section", newSection))).errors().verify();

        // Rename a chapter in English and edit the introduction
        run(SUPER_ADMIN, CHAPTER, Map.of("input", Map.of("id", chapterC, "version", rowVersion("guide_chapter", chapterC),
                "title", Map.of("sv", "Sätt budgeten", "en", "Set the budget"))))
                .path("guideChapterUpdate.title.en").entity(String.class).isEqualTo("Set the budget");
        run(SUPER_ADMIN, INTRO, Map.of("input", Map.of("versionId", draft, "version", guideVersion(draft), "introduction", Map.of("sv", "Ny inledning."))))
                .path("guideIntroductionUpdate.introduction.sv").entity(String.class).isEqualTo("Ny inledning.");

        assertThat(snapshot(published)).isEqualTo(before);

        // And the item removed
        final long removable = id("guide_item", "version_id = ? AND type = 'QUESTION'", draft);

        run(SUPER_ADMIN, ITEM_REMOVE, Map.of("id", removable, "version", rowVersion("guide_item", removable))).errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item WHERE id = ?").param(removable).query(Long.class).single()).isZero();
    }

    @Test
    void chaptersAreAddedMovedAndRemovedInTheDraft() {
        final long published = publishedGuide();

        graphQl(SUPER_ADMIN).document(START).execute();

        final long draft = id("guide_version", "status = 'DRAFT'");
        final String before = snapshot(published);
        final String added = run(SUPER_ADMIN, CHAPTER_CREATE, Map.of("input", Map.of("versionId", draft, "position", 1,
                "title", Map.of("sv", "Sponsorer", "en", "Sponsors"))))
                .path("guideChapterCreate.title.en").entity(String.class).isEqualTo("Sponsors")
                .path("guideChapterCreate.key").entity(String.class).get();

        assertThat(chapterKeys(draft)).containsExactly("A", added, "B", "C", "D", "E", "F", "G", "H", "I", "J");
        run(SUPER_ADMIN, CHAPTER_CREATE, Map.of("input", Map.of("versionId", draft, "title", Map.of("sv", "Efteråt"))))
                .path("guideChapterCreate.items").entityList(Object.class).hasSize(0);
        assertThat(chapterKeys(draft)).hasSize(12).last().isNotIn(GuideTestChapters.KEYS);

        final long chapterJ = id("guide_chapter", "version_id = ? AND `key` = 'J'", draft);

        run(SUPER_ADMIN, CHAPTER_MOVE, Map.of("input", Map.of("id", chapterJ, "version", rowVersion("guide_chapter", chapterJ), "position", 0)))
                .path("guideChapterMove.chapters[0].key").entity(String.class).isEqualTo("J");
        assertThat(jdbcClient.sql("SELECT position FROM guide_chapter WHERE version_id = ? ORDER BY position").param(draft).query(Integer.class).list())
                .isEqualTo(java.util.stream.IntStream.range(0, 12).boxed().toList());

        // Removing a chapter takes its sections and items along
        final long chapterB = id("guide_chapter", "version_id = ? AND `key` = 'B'", draft);

        run(SUPER_ADMIN, CHAPTER_REMOVE, Map.of("id", chapterB, "version", rowVersion("guide_chapter", chapterB))).errors().verify();
        assertThat(chapterKeys(draft)).doesNotContain("B").hasSize(11);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_section WHERE chapter_id = ?").param(chapterB).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item WHERE chapter_id = ?").param(chapterB).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT position FROM guide_chapter WHERE version_id = ? ORDER BY position").param(draft).query(Integer.class).list())
                .isEqualTo(java.util.stream.IntStream.range(0, 11).boxed().toList());

        // The published version is untouched, and a chapter of it cannot be removed
        assertThat(snapshot(published)).isEqualTo(before);
        final long publishedB = id("guide_chapter", "version_id = ? AND `key` = 'B'", published);

        expectError(run(SUPER_ADMIN, CHAPTER_REMOVE, Map.of("id", publishedB, "version", rowVersion("guide_chapter", publishedB))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, CHAPTER_CREATE, Map.of("input", Map.of("versionId", published, "title", Map.of("sv", "x")))), "BAD_REQUEST");
        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            expectError(run(user, CHAPTER_CREATE, Map.of("input", Map.of("versionId", draft, "title", Map.of("sv", "x")))), "FORBIDDEN");
        }
    }

    @Test
    void theFirstDraftHasNoChaptersAndCannotBePublishedWithout() {
        graphQl(SUPER_ADMIN).document(START).execute()
                .path("guideDraftStart.draft.chapters").entityList(Object.class).hasSize(0);

        final long draft = id("guide_version", "status = 'DRAFT'");

        expectError(run(SUPER_ADMIN, PUBLISH, Map.of("input", Map.of("id", draft, "version", guideVersion(draft)))), "BAD_REQUEST");
        run(SUPER_ADMIN, CHAPTER_CREATE, Map.of("input", Map.of("versionId", draft, "title", Map.of("sv", "Först")))).errors().verify();
        run(SUPER_ADMIN, PUBLISH, Map.of("input", Map.of("id", draft, "version", guideVersion(draft)))).errors().verify();
    }

    @Test
    void validationIsApplied() {
        publishedGuide();
        graphQl(SUPER_ADMIN).document(START).execute();

        final long draft = id("guide_version", "status = 'DRAFT'");
        final long chapter = id("guide_chapter", "version_id = ? AND `key` = 'A'", draft);

        expectError(run(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapter, "type", "TIP", "text", Map.of("sv", " ")))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapter, "type", "TIP", "text", Map.of("sv", "x".repeat(5001))))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, CHAPTER, Map.of("input", Map.of("id", chapter, "version", rowVersion("guide_chapter", chapter), "title", Map.of("sv", "")))), "BAD_REQUEST");
        expectError(run(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapter, "position", 99, "type", "TIP", "text", Map.of("sv", "Tips")))), "BAD_REQUEST");
        expectError(graphQl(SUPER_ADMIN).document(START).execute(), "CONFLICT");
    }

    @Test
    void aStaleVersionIsAConflict() {
        publishedGuide();
        graphQl(SUPER_ADMIN).document(START).execute();

        final long draft = id("guide_version", "status = 'DRAFT'");
        final long item = id("guide_item", "version_id = ?", draft);
        final long stale = rowVersion("guide_item", item);

        run(SUPER_ADMIN, ITEM_UPDATE, Map.of("input", Map.of("id", item, "version", stale, "type", "TEXT", "text", Map.of("sv", "Först.")))).errors().verify();
        expectError(run(SUPER_ADMIN, ITEM_UPDATE, Map.of("input", Map.of("id", item, "version", stale, "type", "TEXT", "text", Map.of("sv", "Sist.")))), "CONFLICT");
    }

    @Test
    void everyoneButSuperAdminsIsRefused() {
        publishedGuide();
        graphQl(SUPER_ADMIN).document(START).execute();

        final long draft = id("guide_version", "status = 'DRAFT'");
        final long chapter = id("guide_chapter", "version_id = ? AND `key` = 'A'", draft);
        final long item = id("guide_item", "version_id = ?", draft);

        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP, PLAIN_USER)) {
            expectError(graphQl(user).document(START).execute(), "FORBIDDEN");
            expectError(run(user, ITEM_CREATE, Map.of("input", Map.of("chapterId", chapter, "type", "TIP", "text", Map.of("sv", "x")))), "FORBIDDEN");
            expectError(run(user, ITEM_UPDATE, Map.of("input", Map.of("id", item, "version", rowVersion("guide_item", item), "type", "TIP", "text", Map.of("sv", "x")))), "FORBIDDEN");
            expectError(run(user, ITEM_REMOVE, Map.of("id", item, "version", rowVersion("guide_item", item))), "FORBIDDEN");
            expectError(run(user, CHAPTER, Map.of("input", Map.of("id", chapter, "version", rowVersion("guide_chapter", chapter), "title", Map.of("sv", "x")))), "FORBIDDEN");
            expectError(run(user, INTRO, Map.of("input", Map.of("versionId", draft, "version", guideVersion(draft), "introduction", Map.of("sv", "x")))), "FORBIDDEN");
        }
    }

    @Test
    void editsArePublishingAndDiscardingAreAudited() {
        final long published = publishedGuide();

        graphQl(SUPER_ADMIN).document(START).execute();

        final long draft = id("guide_version", "status = 'DRAFT'");
        final long item = id("guide_item", "version_id = ?", draft);

        run(SUPER_ADMIN, ITEM_UPDATE, Map.of("input", Map.of("id", item, "version", rowVersion("guide_item", item), "type", "TIP", "text", Map.of("sv", "Ny.")))).errors().verify();
        run(SUPER_ADMIN, DISCARD, Map.of("id", draft, "version", guideVersion(draft))).errors().verify();

        for (final String operation : List.of("guideDraftPublish", "guideDraftStart", "guideItemUpdate", "guideDraftDiscard")) {
            assertThat(jdbcClient.sql("SELECT CONCAT(source, ' ', modified_by) FROM revinfo WHERE operation = ?").param(operation).query(String.class).list())
                    .as(operation)
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row).startsWith("WEB ").doesNotContain("@"));
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item_audit a JOIN revinfo r ON r.id = a.rev WHERE r.operation = 'guideItemUpdate' AND a.id = ?")
                .param(item).query(Long.class).single()).isPositive();
        assertThat(published).isPositive();
    }
}
