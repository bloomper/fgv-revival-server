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
package nu.fgv.revival.server.audit;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import nu.fgv.revival.server.audit.AuditedEntityDescriptor.Formatter;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The fields that differ between two Envers snapshots of a record, in the order the entity declares them, written as
 * {@link FieldKind} says. Ids, versions, fields Envers does not audit and those the descriptor hides are left out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AuditDiffer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final EntityManagerFactory entityManagerFactory;
    private final Map<Class<?>, List<Field>> fields = new ConcurrentHashMap<>();

    public AuditDiffer(final EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    private static FieldKind kindOf(final Class<?> type) {
        if (LocalDate.class.equals(type)) {
            return FieldKind.DATE;
        }
        if (LocalTime.class.equals(type)) {
            return FieldKind.TIME;
        }
        if (Instant.class.equals(type)) {
            return FieldKind.INSTANT;
        }
        if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            return FieldKind.BOOLEAN;
        }
        if (type.isEnum()) {
            return FieldKind.ENUM;
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            return FieldKind.NUMBER;
        }
        if (Collection.class.isAssignableFrom(type)) {
            return FieldKind.LIST;
        }
        return FieldKind.TEXT;
    }

    private static @Nullable String plain(final Object value) {
        return switch (value) {
            case final LocalTime time -> TIME.format(time);
            case final BigDecimal decimal -> decimal.stripTrailingZeros().toPlainString();
            case final Enum<?> constant -> constant.name();
            case final Collection<?> collection -> collection.isEmpty() ? null
                    : collection.stream().map(String::valueOf).sorted().collect(Collectors.joining(", "));
            default -> value.toString();
        };
    }

    public List<FieldChangeDto> diff(final AuditedEntityDescriptor descriptor, final @Nullable Object before, final @Nullable Object after,
                                     final Locale locale) {
        final List<FieldChangeDto> changes = new ArrayList<>();

        for (final Field field : fieldsOf(descriptor.entityClass(), descriptor.hidden())) {
            final Formatter formatter = descriptor.formatters().get(field.getName());
            final String oldValue = format(formatter, read(field, before), locale);
            final String newValue = format(formatter, read(field, after), locale);

            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new FieldChangeDto(field.getName(), formatter == null ? kindOf(field.getType()) : formatter.kind(), oldValue, newValue));
            }
        }
        return List.copyOf(changes);
    }

    private @Nullable String format(final @Nullable Formatter formatter, final @Nullable Object value, final Locale locale) {
        if (value == null) {
            return null;
        }
        return formatter == null ? plain(value) : formatter.format().apply(value, locale);
    }

    private @Nullable Object read(final Field field, final @Nullable Object entity) {
        if (entity == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(field);
        return ReflectionUtils.getField(field, entity);
    }

    private List<Field> fieldsOf(final Class<?> entityClass, final Set<String> hidden) {
        return fields.computeIfAbsent(entityClass, type -> {
            final EntityType<?> entity = entityManagerFactory.getMetamodel().entity(type);
            final Set<String> attributes = entity.getAttributes().stream()
                    .filter(attribute -> !(attribute instanceof final SingularAttribute<?, ?> singular && (singular.isId() || singular.isVersion())))
                    .map(Attribute::getName)
                    .collect(Collectors.toSet());

            return java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(field -> attributes.contains(field.getName()))
                    .filter(field -> !field.isAnnotationPresent(NotAudited.class))
                    .filter(field -> !hidden.contains(field.getName()))
                    .toList();
        });
    }
}
