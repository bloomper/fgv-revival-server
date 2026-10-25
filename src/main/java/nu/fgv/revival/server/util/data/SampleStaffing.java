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

import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.RelativeDeadlineKind;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.mailing.Mailing;
import nu.fgv.revival.server.mailing.MailingAudienceKind;
import nu.fgv.revival.server.mailing.MailingRecipient;
import nu.fgv.revival.server.mailing.MailingRecipientRepository;
import nu.fgv.revival.server.mailing.MailingRecipientStatus;
import nu.fgv.revival.server.mailing.MailingReminder;
import nu.fgv.revival.server.mailing.MailingReminderRepository;
import nu.fgv.revival.server.mailing.MailingRepository;
import nu.fgv.revival.server.mailing.MailingStatus;
import nu.fgv.revival.server.mailing.MailingTemplate;
import nu.fgv.revival.server.mailing.MailingTemplateRepository;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.participant.DietaryNeed;
import nu.fgv.revival.server.participant.DietaryNeedRepository;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.participant.ParticipationSettings;
import nu.fgv.revival.server.participant.ParticipationSettingsRepository;
import nu.fgv.revival.server.participant.PendingPerson;
import nu.fgv.revival.server.participant.PendingPersonRepository;
import nu.fgv.revival.server.participant.Referral;
import nu.fgv.revival.server.participant.ReferralRepository;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.staffing.Availability;
import nu.fgv.revival.server.staffing.AvailabilityAnswer;
import nu.fgv.revival.server.staffing.AvailabilityRepository;
import nu.fgv.revival.server.staffing.ShiftAssignment;
import nu.fgv.revival.server.staffing.ShiftAssignmentRepository;
import nu.fgv.revival.server.staffing.ShiftSlot;
import nu.fgv.revival.server.staffing.ShiftSlotRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sample participation, staffing and mailings: the mailing plan's two templates, one per rehearsal
 * weekend, "Monday the week before: remind about allergies"; and for a revival that is or has been open its settings,
 * fictitious participants and a helper (one removed, one with a fee of their own), availability for the first rehearsal
 * weekend, shift slots on the show weekend with some of them assigned, and mailings sent, drafted from the plan and
 * cancelled. While the revival is open, the sample users {@value SampleDataImporter#PARTICIPANT} and
 * {@value SampleDataImporter#HELPER} take part too, and there are dietary needs, a referral, someone who has not
 * answered and someone whose no closed them; once it has left open those are gone, as {@code ParticipantRetention}
 * leaves them. Goes through
 * the repositories, so the rows are audited as usual.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SampleStaffing {

    private static final String ALLERGIES_BODY_SV = """
            Hej!
            
            Om en vecka är det rephelg. Har du allergier eller andra kostbehov som vi inte känner till? Fyll i dem på \
            din sida i Nyuppsättning senast på torsdag.
            
            Väl mött!""";
    private static final String ALLERGIES_BODY_EN = """
            Hi!
            
            The rehearsal weekend is a week away. Do you have allergies or other dietary needs we do not know about? Add \
            them on your page in Nyuppsättning by Thursday.
            
            See you there!""";
    private static final String TERMS = """
            Deltagaravgiften betalas med Swish inom två veckor från att du tackat ja. Den täcker mat på rephelgerna och \
            kalaset. Lån av kostymer och rekvisita lämnas tillbaka efter sista föreställningen.""";

    private final MailingTemplateRepository templateRepository;
    private final MailingRepository mailingRepository;
    private final MailingRecipientRepository recipientRepository;
    private final MailingReminderRepository reminderRepository;
    private final EventTypeRepository eventTypeRepository;
    private final ParticipantRepository participantRepository;
    private final PendingPersonRepository pendingRepository;
    private final ParticipationSettingsRepository settingsRepository;
    private final DietaryNeedRepository dietaryRepository;
    private final ReferralRepository referralRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final RevivalMemberRepository memberRepository;
    private final PersonRepository personRepository;

    public SampleStaffing(final MailingTemplateRepository templateRepository, final MailingRepository mailingRepository,
                          final MailingRecipientRepository recipientRepository, final MailingReminderRepository reminderRepository,
                          final EventTypeRepository eventTypeRepository, final ParticipantRepository participantRepository,
                          final PendingPersonRepository pendingRepository, final ParticipationSettingsRepository settingsRepository,
                          final DietaryNeedRepository dietaryRepository, final ReferralRepository referralRepository,
                          final AvailabilityRepository availabilityRepository, final ShiftSlotRepository slotRepository,
                          final ShiftAssignmentRepository assignmentRepository, final RevivalMemberRepository memberRepository,
                          final PersonRepository personRepository) {
        this.templateRepository = templateRepository;
        this.mailingRepository = mailingRepository;
        this.recipientRepository = recipientRepository;
        this.reminderRepository = reminderRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.participantRepository = participantRepository;
        this.pendingRepository = pendingRepository;
        this.settingsRepository = settingsRepository;
        this.dietaryRepository = dietaryRepository;
        this.referralRepository = referralRepository;
        this.availabilityRepository = availabilityRepository;
        this.slotRepository = slotRepository;
        this.assignmentRepository = assignmentRepository;
        this.memberRepository = memberRepository;
        this.personRepository = personRepository;
    }

    /**
     * The mailing plan's sample templates, unless it has templates already.
     */
    void insertTemplates() {
        if (!templateRepository.findAllByOrderByPositionAscIdAsc().isEmpty()) {
            return;
        }

        int position = 0;

        for (final String weekend : List.of(SampleChecklistTemplates.REHEARSAL_WEEKEND + " 1", SampleChecklistTemplates.REHEARSAL_WEEKEND + " 2")) {
            final EventType type = eventTypeRepository.findByNameSv(weekend).orElse(null);

            if (type == null) {
                continue;
            }

            final MailingTemplate template = new MailingTemplate();
            final String english = type.getNameEn() == null ? weekend : type.getNameEn();

            template.setNameSv("Påminn om allergier inför " + weekend.toLowerCase(Locale.ROOT));
            template.setNameEn("Remind about allergies before " + english);
            template.setSubjectSv("Allergier inför " + weekend.toLowerCase(Locale.ROOT));
            template.setSubjectEn("Allergies before " + english);
            template.setBodySv(ALLERGIES_BODY_SV);
            template.setBodyEn(ALLERGIES_BODY_EN);
            template.setAudienceKind(MailingAudienceKind.CONFIRMED);
            template.setEventTypeId(type.getId());
            template.setDue(RelativeDeadline.of(RelativeDeadlineKind.WEEKDAY_WEEK_BEFORE, null, DayOfWeek.MONDAY));
            template.setPosition(position++);
            templateRepository.saveAndFlush(template);
        }
    }

    /**
     * A revival's participation, staffing and mailings. {@code payer} marks fees paid; {@code rehearsal} is the first
     * rehearsal weekend, which the availability is asked for and a plan entry drafted from.
     */
    void insert(final long revivalId, final RevivalStatus status, final @Nullable Event rehearsal, final Event showWeekend, final long directeur,
                final long payer, final SamplePositions positions, final LocalDate today) {
        final boolean open = status == RevivalStatus.OPEN;
        final boolean settled = status == RevivalStatus.SETTLING || status == RevivalStatus.ARCHIVED;
        final Instant invited = Instant.now().minus(Duration.ofDays(open ? 14 : 150));
        final ParticipationSettings settings = settingsRepository.findByRevivalId(revivalId).orElseGet(() -> new ParticipationSettings(revivalId));

        settings.setSwishNumber("1231234567");
        settings.setWhatsappLink("https://chat.whatsapp.com/ExempelGrupp" + revivalId);
        settings.setTerms(TERMS);
        settingsRepository.saveAndFlush(settings);

        final Participant anna = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, invited);

        anna.setPositionTaskId(positions.actorTaskId());
        anna.setRoleName(positions.actorTaskId() == null ? null : "Kungen");
        anna.setVocalPartId(positions.actorTaskId() == null ? null : positions.vocalPartId());
        anna.setComment("Kan inte komma före lunch på lördagarna.");
        anna.setRegisterConsent(true);
        anna.setRegisterConsentAt(invited.plus(Duration.ofDays(1)));
        anna.setFeePaidAt(invited.plus(Duration.ofDays(3)));
        anna.setFeePaidBy(payer);
        anna.setMembershipPaid(settled);

        final Participant bertil = participant(revivalId, ParticipantKind.PARTICIPANT, open ? ParticipantResponse.MAYBE : ParticipantResponse.YES, invited);

        bertil.setPositionTaskId(positions.crewTaskId());
        bertil.setRegisterConsent(false);
        bertil.setRegisterConsentAt(invited.plus(Duration.ofDays(2)));
        bertil.setMembershipPaid(settled);

        final Participant fredrika = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, invited);

        fredrika.setPreferredTaskId(positions.crewTaskId());
        fredrika.setFee(new BigDecimal("250.00"));
        fredrika.setComment("Halv avgift, enligt beslut i direktionen.");

        final Participant helper = participant(revivalId, ParticipantKind.HELPER, ParticipantResponse.YES, invited);

        helper.setCompensationPerShift(new BigDecimal("300.00"));

        // A pending person's name goes with the removal
        final Participant removed = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, invited);

        removed.setRemovedAt(invited.plus(Duration.ofDays(5)));
        removed.setRemovedBy(directeur);
        participantRepository.saveAndFlush(removed);

        final List<Participant> everyone = new ArrayList<>(List.of(
                save(anna, "Anna", "Exempelsson", null, "0701234567"),
                save(bertil, "Bertil", "Provsson", null, null),
                save(fredrika, "Fredrika", "Låtsasdotter", null, "0709876543"),
                save(helper, "Cecilia", "Springardotter", null, null),
                removed));
        final List<Participant> confirmed = new ArrayList<>(List.of(anna, bertil, fredrika, helper));
        Participant me = null;
        Participant undelivered = null;

        if (open) {
            final Participant david = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.PENDING, invited);
            // A pending person's no closes them at once, name and all
            final Participant declined = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.NO, invited);

            david.setRemindedAt(invited.plus(Duration.ofDays(7)));
            undelivered = save(david, "David", "Testberg", null, null);
            everyone.add(undelivered);
            declined.setComment("Tyvärr, jag är utomlands hela våren.");
            declined.setRemovedAt(invited.plus(Duration.ofDays(1)));
            participantRepository.saveAndFlush(declined);

            me = participant(revivalId, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, invited);
            me.setMemberId(member(revivalId, SampleDataImporter.PARTICIPANT, RevivalRole.PARTICIPANT, invited));
            me.setRegisterConsent(true);
            me.setRegisterConsentAt(invited.plus(Duration.ofDays(1)));
            everyone.add(save(me, "Participant", "Exempel", SampleDataImporter.PARTICIPANT, null));
            confirmed.add(me);

            final Participant meHelping = participant(revivalId, ParticipantKind.HELPER, ParticipantResponse.PENDING, invited);

            meHelping.setMemberId(member(revivalId, SampleDataImporter.HELPER, RevivalRole.HELPER, invited));
            meHelping.setCompensationPerShift(new BigDecimal("300.00"));
            everyone.add(save(meHelping, "Helper", "Exempel", SampleDataImporter.HELPER, null));

            dietary(anna, "Vegetarian, och allergisk mot nötter.");
            dietary(me, "Laktosintolerant.");

            final Referral referral = new Referral();

            referral.setRevivalId(revivalId);
            referral.setReferredByParticipantId(anna.getId());
            referral.setName("Hanna Hittepå");
            referral.setContact("hanna@example.org");
            referral.setComment("Var med i originaluppsättningen och vill gärna sjunga i kören.");
            referral.setCreatedAt(invited.plus(Duration.ofDays(4)));
            referralRepository.saveAndFlush(referral);
        }

        if (rehearsal != null) {
            for (LocalDate day = rehearsal.getStartDate(); !day.isAfter(rehearsal.getEndDate()); day = day.plusDays(1)) {
                final boolean firstDay = day.equals(rehearsal.getStartDate());

                availability(anna, rehearsal, day, AvailabilityAnswer.YES, firstDay ? "Från lunch" : null);
                availability(bertil, rehearsal, day, firstDay ? AvailabilityAnswer.MAYBE : AvailabilityAnswer.NO, firstDay ? "Beror på jobbet" : null);
                availability(fredrika, rehearsal, day, AvailabilityAnswer.YES, null);
                if (me != null) {
                    availability(me, rehearsal, day, AvailabilityAnswer.YES, null);
                }
            }
        }

        final ShiftSlot tickets = slot(revivalId, showWeekend, LocalTime.of(17, 0), LocalTime.of(19, 0), "Biljettkontroll", 2, null);
        final ShiftSlot drinks = slot(revivalId, showWeekend, LocalTime.of(18, 30), LocalTime.of(21, 0), "Sälja dryckesbiljetter", 2, null);

        slot(revivalId, showWeekend, LocalTime.of(22, 0), LocalTime.of(23, 30), "Bålvakt", 1, "Ingen alkohol under passet");
        assign(tickets, anna, true, null);
        assign(tickets, helper, false, directeur);
        assign(drinks, bertil, false, directeur);
        if (me != null) {
            assign(drinks, me, true, null);
        }

        // Sent, so with its recipients; the one who is gone now was still there
        final Mailing welcome = mailing(revivalId, MailingAudienceKind.PARTICIPANTS, "Välkommen till uppsättningen!", """
                Hej!
                
                Kul att du vill vara med! Svara på inbjudan på din sida i Nyuppsättning, och fyll i dina kostbehov där.""", directeur);

        sent(welcome, invited.plus(Duration.ofHours(1)), directeur, everyone, undelivered);
        if (rehearsal != null) {
            fromPlan(revivalId, rehearsal, directeur, confirmed, today);
        }
        if (open) {
            final Mailing schedule = mailing(revivalId, MailingAudienceKind.CONFIRMED, "Schema för föreställningshelgen", """
                    Hej!
                    
                    Nu finns **schemat** för föreställningshelgen. Anmäl dig till de pass du kan ta på din sida i Nyuppsättning.
                    
                    - Biljettkontroll
                    - Sälja dryckesbiljetter
                    - Bålvakt""", directeur);
            final Mailing reminder = mailing(revivalId, MailingAudienceKind.NON_RESPONDERS, "Påminnelse: svara på inbjudan",
                    "Hej! Vi har inte fått något svar från dig än.", directeur);

            reminder.setStatus(MailingStatus.CANCELLED);
            reminder.setCancelledAt(Instant.now().minus(Duration.ofDays(2)));
            mailingRepository.saveAndFlush(reminder);
            if (positions.actorTaskId() != null) {
                final Mailing ensemble = mailing(revivalId, MailingAudienceKind.POSITION, "Extra sångrep för ensemblen",
                        "Hej! Vi kör ett extra sångrep på torsdag kl. 18 i replokalen.", directeur);

                ensemble.setAudienceTaskId(positions.actorTaskId());
                mailingRepository.saveAndFlush(ensemble);
            }
            mailingRepository.saveAndFlush(schedule);
        } else {
            final Mailing thanks = mailing(revivalId, MailingAudienceKind.CONFIRMED, "Tack för i år!", """
                    Hej!
                    
                    Tack för en fantastisk föreställningshelg! Bilderna från kalaset finns under Material.""", directeur);

            sent(thanks, showWeekend.getEndDate().plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant(), directeur, confirmed, null);
        }
    }

    /**
     * The plan entry for the rehearsal weekend: sent, with the reminder that it was due, once its day has passed, and
     * a draft until then.
     */
    private void fromPlan(final long revivalId, final Event rehearsal, final long directeur, final List<Participant> confirmed, final LocalDate today) {
        final MailingTemplate template = templateRepository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(candidate -> rehearsal.getTypeId() != null && rehearsal.getTypeId().equals(candidate.getEventTypeId()))
                .findFirst()
                .orElse(null);

        if (template == null) {
            return;
        }

        final LocalDate due = template.getDue().apply(rehearsal.getStartDate());
        final Mailing mailing = mailing(revivalId, template.getAudienceKind(), template.getSubjectSv(), template.getBodySv(), directeur);

        mailing.setTemplateId(template.getId());
        mailing.setPlanEventId(rehearsal.getId());
        if (due.isAfter(today)) {
            mailingRepository.saveAndFlush(mailing);
            return;
        }

        final MailingReminder reminder = new MailingReminder();

        reminder.setRevivalId(revivalId);
        reminder.setTemplateId(template.getId());
        reminder.setEventId(rehearsal.getId());
        reminder.setSentOn(due);
        reminderRepository.saveAndFlush(reminder);
        sent(mailing, due.atTime(9, 0).atZone(ZoneOffset.UTC).toInstant(), directeur, confirmed, null);
    }

    private Participant participant(final long revivalId, final ParticipantKind kind, final ParticipantResponse response, final Instant invited) {
        final Participant participant = new Participant();

        participant.setRevivalId(revivalId);
        participant.setKind(kind);
        participant.setResponse(response);
        participant.setInvitedAt(invited);
        participant.setRespondedAt(response == ParticipantResponse.PENDING ? null : invited.plus(Duration.ofDays(1)));
        participant.setTermsAcceptedAt(response == ParticipantResponse.YES ? invited.plus(Duration.ofDays(1)) : null);
        return participant;
    }

    private Participant save(final Participant participant, final String firstName, final String lastName, final @Nullable String email,
                             final @Nullable String phone) {
        final Participant saved = participantRepository.saveAndFlush(participant);
        final PendingPerson person = new PendingPerson();

        person.setParticipantId(saved.getId());
        person.setFirstName(firstName);
        person.setLastName(lastName);
        person.setEmail(email != null ? email : firstName.toLowerCase(Locale.ROOT) + "@example.org");
        person.setPhone(phone);
        pendingRepository.saveAndFlush(person);
        return saved;
    }

    /**
     * The sample user's participant or helper role, claimed as when they logged in with the invited address.
     */
    private Long member(final long revivalId, final String email, final RevivalRole role, final Instant invited) {
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(revivalId);
        member.setPersonId(personRepository.findByEmailAndEmailVerifiedTrue(email).getFirst().getId());
        member.setRole(role);
        member.setInvitedAt(invited);
        return memberRepository.saveAndFlush(member).getId();
    }

    private void dietary(final Participant participant, final String text) {
        final DietaryNeed need = new DietaryNeed();

        need.setParticipantId(participant.getId());
        need.setText(text);
        need.setUpdatedAt(Instant.now().minus(Duration.ofDays(3)));
        dietaryRepository.saveAndFlush(need);
    }

    private void availability(final Participant participant, final Event event, final LocalDate day, final AvailabilityAnswer answer,
                              final @Nullable String comment) {
        final Availability availability = new Availability();

        availability.setParticipantId(participant.getId());
        availability.setEventId(event.getId());
        availability.setDay(day);
        availability.setAnswer(answer);
        availability.setComment(comment);
        availabilityRepository.saveAndFlush(availability);
    }

    private ShiftSlot slot(final long revivalId, final Event event, final LocalTime start, final LocalTime end, final String task, final int needed,
                           final @Nullable String comment) {
        final ShiftSlot slot = new ShiftSlot();

        slot.setRevivalId(revivalId);
        slot.setEventId(event.getId());
        slot.setDay(event.getStartDate());
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setTask(task);
        slot.setNeeded(needed);
        slot.setComment(comment);
        return slotRepository.saveAndFlush(slot);
    }

    private void assign(final ShiftSlot slot, final Participant participant, final boolean selfSignedUp, final @Nullable Long assignedBy) {
        final ShiftAssignment assignment = new ShiftAssignment();

        assignment.setSlotId(slot.getId());
        assignment.setParticipantId(participant.getId());
        assignment.setSelfSignedUp(selfSignedUp);
        assignment.setAssignedBy(assignedBy);
        assignmentRepository.saveAndFlush(assignment);
    }

    private Mailing mailing(final long revivalId, final MailingAudienceKind audience, final String subject, final String body, final long by) {
        final Mailing mailing = new Mailing();

        mailing.setRevivalId(revivalId);
        mailing.setAudienceKind(audience);
        mailing.setSubject(subject);
        mailing.setBody(body);
        mailing.setCreatedByPerson(by);
        return mailing;
    }

    /**
     * Sent to {@code recipients}, except that it could not be delivered to {@code failed}.
     */
    private void sent(final Mailing mailing, final Instant at, final long by, final List<Participant> recipients, final @Nullable Participant failed) {
        mailing.setStatus(MailingStatus.SENT);
        mailing.setSentAt(at);
        mailing.setSentByPerson(by);

        final Mailing saved = mailingRepository.saveAndFlush(mailing);

        for (final Participant participant : recipients) {
            final MailingRecipient recipient = new MailingRecipient();

            recipient.setMailingId(saved.getId());
            recipient.setParticipantId(participant.getId());
            recipient.setStatus(participant == failed ? MailingRecipientStatus.FAILED : MailingRecipientStatus.SENT);
            recipientRepository.save(recipient);
        }
    }
}
