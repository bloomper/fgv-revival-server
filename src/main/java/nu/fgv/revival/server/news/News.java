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
package nu.fgv.revival.server.news;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * A piece of news. It is published from {@code visibleFrom} up to and including {@code visibleTo}; without a
 * {@code visibleFrom} it is a draft, without a {@code visibleTo} it stays.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "news")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class News extends AbstractAuditable {

    public static final int SUBJECT_MAX_LENGTH = 255;
    public static final int TEXT_MAX_LENGTH = 10000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject", nullable = false, length = SUBJECT_MAX_LENGTH)
    private String subject;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "visible_from")
    private @Nullable LocalDate visibleFrom;

    @Column(name = "visible_to")
    private @Nullable LocalDate visibleTo;

    public boolean isPublishedOn(final LocalDate today) {
        return visibleFrom != null && !visibleFrom.isAfter(today) && (visibleTo == null || !visibleTo.isBefore(today));
    }
}
