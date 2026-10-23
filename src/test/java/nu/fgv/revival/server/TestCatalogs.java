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
package nu.fgv.revival.server;

import nu.fgv.revival.server.guide.GuideItemKind;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideItemTypeRepository;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guide item types the database starts with (migration V3_8), for tests without one.
 *
 * @author Anders Jacobsson
 */
public final class TestCatalogs {

    private TestCatalogs() {
    }

    public static List<GuideItemType> seededItemTypes() {
        return List.of(
                itemType(GuideItemKind.TIP, 0, "Praktiska tips", "Tip"),
                itemType(GuideItemKind.REQUIREMENT, 1, "Krav", "Requirement"),
                itemType(GuideItemKind.QUESTION, 2, "Att ta reda på", "To find out"),
                itemType(GuideItemKind.TEXT, 3, "Text", "Text"));
    }

    public static GuideItemTypeRepository itemTypeRepository() {
        final GuideItemTypeRepository repository = mock(GuideItemTypeRepository.class);

        when(repository.findAllByOrderByPositionAscIdAsc()).thenReturn(seededItemTypes());
        return repository;
    }

    private static GuideItemType itemType(final GuideItemKind kind, final int position, final String sv, final String en) {
        final GuideItemType type = new GuideItemType();

        type.setId(kind.name());
        type.setKind(kind);
        type.setPosition(position);
        type.setNameSv(sv);
        type.setNameEn(en);
        type.setVersion(0L);
        return type;
    }
}
