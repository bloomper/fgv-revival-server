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
package nu.fgv.revival.server.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;

/**
 * An entry in the reference library, in Swedish with optional English. Which texts apply depends on the kind; the
 * others stay empty. Contacts are roles, never named people.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "library_entry")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class LibraryEntry extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int TEXT_MAX_LENGTH = 4000;
    public static final int URL_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private LibraryEntryKind kind;

    @Column(name = "name_sv", nullable = false, length = NAME_MAX_LENGTH)
    private String nameSv;

    @Column(name = "name_en", length = NAME_MAX_LENGTH)
    private @Nullable String nameEn;

    @Column(name = "description_sv", columnDefinition = "TEXT")
    private @Nullable String descriptionSv;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private @Nullable String descriptionEn;

    @Column(name = "pros_sv", columnDefinition = "TEXT")
    private @Nullable String prosSv;

    @Column(name = "pros_en", columnDefinition = "TEXT")
    private @Nullable String prosEn;

    @Column(name = "cons_sv", columnDefinition = "TEXT")
    private @Nullable String consSv;

    @Column(name = "cons_en", columnDefinition = "TEXT")
    private @Nullable String consEn;

    @Column(name = "booking_sv", columnDefinition = "TEXT")
    private @Nullable String bookingSv;

    @Column(name = "booking_en", columnDefinition = "TEXT")
    private @Nullable String bookingEn;

    @Column(name = "prices_sv", columnDefinition = "TEXT")
    private @Nullable String pricesSv;

    @Column(name = "prices_en", columnDefinition = "TEXT")
    private @Nullable String pricesEn;

    @Column(name = "contact_sv", columnDefinition = "TEXT")
    private @Nullable String contactSv;

    @Column(name = "contact_en", columnDefinition = "TEXT")
    private @Nullable String contactEn;

    @Column(name = "url", length = URL_MAX_LENGTH)
    private @Nullable String url;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }
}
