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

package nu.fgv.revival.server.mailing;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.mailing.MailingDtos.MailingTemplateDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The super admin's mailing plan (product spec 5.8, M6B-FR-10): planned mailings with an audience, a text in Swedish
 * and optionally English, and when they are due relative to each event of a type or to the premiere. Readers of the
 * guide read it; the super admin creates, changes, moves, retires and restores templates. Never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Service
public class MailingTemplateService {

    private final MailingTemplateRepository repository;
    private final EventTypeRepository eventTypeRepository;
    private final GuideAccess guideAccess;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public MailingTemplateService(final MailingTemplateRepository repository,
                                  final EventTypeRepository eventTypeRepository,
                                  final GuideAccess guideAccess,
                                  final CallerService callerService,
                                  final AccessPolicy accessPolicy,
                                  final Clock clock) {
        this.repository = repository;
        this.eventTypeRepository = eventTypeRepository;
        this.guideAccess = guideAccess;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * Swedish text and, optionally, English.
     */
    public record Text(@Nullable String sv, @Nullable String en) {
    }

    public record Definition(Text name, Text subject, Text body, MailingAudienceKind audience, @Nullable Long eventTypeId, RelativeDeadline due) {
    }

    @Transactional(readOnly = true)
    public List<MailingTemplateDto> list(final boolean includeRetired) {
        final Caller caller = callerService.current();

        accessPolicy.requireReadMailingTemplates(caller, guideAccess.callerRevivals(caller));
        return repository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(template -> includeRetired || !template.isRetired())
                .map(MailingTemplateDto::of)
                .toList();
    }

    @Transactional
    public MailingTemplateDto create(final Definition definition) {
        manageable();

        final List<MailingTemplate> templates = repository.findAllByOrderByPositionAscIdAsc();
        final MailingTemplate template = new MailingTemplate();

        AuditContext.operation("mailingTemplateCreate");
        define(template, definition);
        template.setPosition(templates.size());
        return MailingTemplateDto.of(repository.saveAndFlush(template));
    }

    @Transactional
    public MailingTemplateDto update(final Long id, final Long version, final Definition definition) {
        manageable();

        final MailingTemplate template = changeable(id, version);

        AuditContext.operation("mailingTemplateUpdate");
        define(template, definition);
        return MailingTemplateDto.of(repository.saveAndFlush(template));
    }

    /**
     * Returns every template, retired ones too, in the new order.
     */
    @Transactional
    public List<MailingTemplateDto> move(final Long id, final Long version, final int position) {
        manageable();

        final MailingTemplate template = changeable(id, version);
        final List<MailingTemplate> templates = new ArrayList<>(repository.findAllByOrderByPositionAscIdAsc());

        AuditContext.operation("mailingTemplateMove");
        templates.removeIf(candidate -> candidate.getId().equals(id));
        templates.add(Math.clamp(position, 0, templates.size()), template);
        for (int index = 0; index < templates.size(); index++) {
            if (templates.get(index).getPosition() != index) {
                templates.get(index).setPosition(index);
                repository.save(templates.get(index));
            }
        }
        repository.flush();
        return templates.stream().map(MailingTemplateDto::of).toList();
    }

    @Transactional
    public MailingTemplateDto retire(final Long id, final Long version) {
        manageable();

        final MailingTemplate template = changeable(id, version);

        AuditContext.operation("mailingTemplateRetire");
        template.setRetiredAt(template.isRetired() ? template.getRetiredAt() : clock.instant());
        return MailingTemplateDto.of(repository.saveAndFlush(template));
    }

    @Transactional
    public MailingTemplateDto restore(final Long id, final Long version) {
        manageable();

        final MailingTemplate template = changeable(id, version);

        AuditContext.operation("mailingTemplateRestore");
        template.setRetiredAt(null);
        return MailingTemplateDto.of(repository.saveAndFlush(template));
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private void manageable() {
        accessPolicy.requireManageMailingTemplates(callerService.current());
    }

    private MailingTemplate changeable(final Long id, final Long version) {
        final MailingTemplate template = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(MailingTemplate.class, id));

        if (!Objects.equals(version, template.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return template;
    }

    private void define(final MailingTemplate template, final Definition definition) {
        final Text name = required(definition.name(), MailingTemplate.NAME_MAX_LENGTH, "error.mailingTemplate.name");
        final Text subject = required(definition.subject(), Mailing.SUBJECT_MAX_LENGTH, "error.mailing.subject");
        final Text body = required(definition.body(), Mailing.BODY_MAX_LENGTH, "error.mailing.body");

        // A template has no position to name, and its event is the plan entry's, so attendees need an event type
        if (definition.audience() == MailingAudienceKind.POSITION
                || (definition.audience() == MailingAudienceKind.EVENT_ATTENDEES && definition.eventTypeId() == null)) {
            throw new BadRequestException("error.mailingTemplate.audience");
        }
        if (definition.eventTypeId() != null && !Objects.equals(definition.eventTypeId(), template.getEventTypeId())) {
            final Long typeId = definition.eventTypeId();
            final EventType type = eventTypeRepository.findById(typeId).orElseThrow(() -> new ResourceNotFoundException(EventType.class, typeId));

            if (type.isRetired()) {
                throw new BadRequestException("error.event.type.retired", type.getNameSv());
            }
        }
        template.setNameSv(Objects.requireNonNull(name.sv()));
        template.setNameEn(name.en());
        template.setSubjectSv(Objects.requireNonNull(subject.sv()));
        template.setSubjectEn(subject.en());
        template.setBodySv(Objects.requireNonNull(body.sv()));
        template.setBodyEn(body.en());
        template.setAudienceKind(definition.audience());
        template.setEventTypeId(definition.eventTypeId());
        template.setDue(definition.due().copy());
    }

    /**
     * Swedish required and English optional, trimmed, each 1 to {@code maxLength} characters.
     */
    private static Text required(final @Nullable Text text, final int maxLength, final String key) {
        final String sv = text == null || text.sv() == null ? "" : text.sv().strip();
        final String en = text == null || text.en() == null || text.en().isBlank() ? null : text.en().strip();

        if (sv.isEmpty() || sv.length() > maxLength || en != null && en.length() > maxLength) {
            throw new BadRequestException(key, maxLength);
        }
        return new Text(sv, en);
    }
}
