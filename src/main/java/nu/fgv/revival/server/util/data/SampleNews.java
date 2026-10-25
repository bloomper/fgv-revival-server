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
package nu.fgv.revival.server.util.data;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * News on the start page: two published, one withdrawn and one draft, written by the super admin.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SampleNews {

    private static final List<Piece> NEWS = List.of(
            new Piece("Välkommen till Nyuppsättning",
                    """
                            Här samlar vi allt som behövs för att sätta upp ett gammalt spex igen: guiden, beslutsloggen, checklistorna, budgeten och deltagarna.

                            Hör av dig till FGV:s styrelse om något saknas.""",
                    -120, null),
            new Piece("Ny version av guiden",
                    """
                            Guiden har fått ett nytt kapitel om kalaset och fler tips om lokaler.
                            Läs vad som har ändrats under Guiden → Ändringar.""",
                    -6, 60),
            new Piece("Ansök om nyuppsättning senast 1 mars",
                    "Vill ni sätta upp ett spex nästa läsår? Skicka in ansökan via Nyuppsättningar senast den 1 mars.",
                    -200, -100),
            new Piece("Utkast: sommarens träff för direktörer",
                    "Vi planerar en träff i juni för alla som har varit eller ska bli direktörer. Datum kommer.",
                    null, null));

    private SampleNews() {
    }

    static void insert(final JdbcClient jdbcClient, final String createdBy) {
        final LocalDate today = LocalDate.now();

        for (final Piece piece : NEWS) {
            final LocalDate from = piece.fromDays() == null ? null : today.plusDays(piece.fromDays());
            final LocalDate written = from == null ? today.minusDays(2) : from.minusDays(1);

            jdbcClient
                    .sql("""
                            INSERT INTO news
                                (subject, text, visible_from, visible_to, created_by, created_at, version)
                            VALUES
                                (:subject, :text, :visibleFrom, :visibleTo, :createdBy, :createdAt, 0)
                            """)
                    .param("subject", piece.subject())
                    .param("text", piece.text())
                    .param("visibleFrom", from)
                    .param("visibleTo", piece.toDays() == null ? null : today.plusDays(piece.toDays()))
                    .param("createdBy", createdBy)
                    .param("createdAt", written.atTime(10, 0).atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private record Piece(String subject, String text, @Nullable Integer fromDays, @Nullable Integer toDays) {
    }
}
