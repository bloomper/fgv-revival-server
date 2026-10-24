-- The guide's example timeline (product spec 5.3, M4-FR-2), in its order. No checklist templates are linked: templates
-- are content the super admin creates.
INSERT INTO event_type (name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                        offset_min_days, offset_max_days, days, revival_pays, position, created_by, created_at, version)
VALUES ('AW', 'After work',
        'Öl och mat på restaurang. Alla betalar själva.',
        'Beer and food at a restaurant. Everyone pays for themselves.',
        NULL, NULL, NULL, NULL,
        NULL, NULL, 1, 0, 0, 'system', NOW(6), 0),
       ('Kickoff', 'Kickoff',
        'Intern lunch, sedan det aktuella spexets föreställning och fest. Alla betalar själva. Datumet väljs så att det passar det aktuella spexets föreställningar.',
        'An internal lunch, then the current spex''s show and party. Everyone pays for themselves. The date is chosen to match the current spex''s shows.',
        NULL, NULL, NULL, NULL,
        -120, -60, 1, 0, 1, 'system', NOW(6), 0),
       ('Rephelg 1', 'Rehearsal weekend 1',
        'Från och med den här helgen betalar nyuppsättningen mat och en omgång dryck.',
        'From this event the revival pays for food and one round of drinks.',
        'Två salar för ensemblen och en för orkestern, minst en av dem stor, samt kök och matsal. Verkstad i Kemihuset.',
        'Two rooms for the ensemble and one for the orchestra, at least one of them large, plus a kitchen and dining. A workshop in Kemihuset.',
        'Fika på förmiddagen och eftermiddagen, lunch som nyuppsättningen lagar. Intern fest med catering eller pizza.',
        'Fika in the morning and the afternoon, and lunch cooked by the revival. An internal party with catering or pizza.',
        -70, -56, 2, 1, 2, 'system', NOW(6), 0),
       ('Rephelg 2', 'Rehearsal weekend 2',
        'Som rephelg 1. Nyuppsättningen betalar mat och en omgång dryck.',
        'As rehearsal weekend 1. The revival pays for food and one round of drinks.',
        'Två salar för ensemblen och en för orkestern, minst en av dem stor, samt kök och matsal. Verkstad i Kemihuset.',
        'Two rooms for the ensemble and one for the orchestra, at least one of them large, plus a kitchen and dining. A workshop in Kemihuset.',
        'Fika på förmiddagen och eftermiddagen, lunch som nyuppsättningen lagar. Intern fest med catering eller pizza.',
        'Fika in the morning and the afternoon, and lunch cooked by the revival. An internal party with catering or pizza.',
        -42, -35, 2, 1, 3, 'system', NOW(6), 0),
       ('Rephelg 3', 'Rehearsal weekend 3',
        'Lördagen som rephelg 1, söndagen genrep. Nyuppsättningen betalar mat och en omgång dryck.',
        'Saturday as rehearsal weekend 1, Sunday the dress rehearsal. The revival pays for food and one round of drinks.',
        'Lördag: som rephelg 1. Söndag: lokalen för genrep, ett uppvärmningsrum och matsal.',
        'Saturday: as rehearsal weekend 1. Sunday: the venue for the dress rehearsal, a warm-up room and dining.',
        'Lördag: som rephelg 1.',
        'Saturday: as rehearsal weekend 1.',
        -21, -14, 2, 1, 4, 'system', NOW(6), 0),
       ('Föreställningshelg', 'Show weekend',
        'Premiär och föreställningar. Nyuppsättningen betalar mat och en omgång dryck.',
        'The premiere and the shows. The revival pays for food and one round of drinks.',
        'Föreställningslokal, uppvärmningsrum, loger och matsal. Lördag: lokal för en stor fest. Söndag: lokal för en intern fest.',
        'The venue, a warm-up room, dressing rooms and dining. Saturday: a venue for a big party. Sunday: a venue for an internal party.',
        'Enkel premiärlunch. Lördag: stor fest med lagad mat och servering. Söndag: intern fest med catering.',
        'A simple premiere lunch. Saturday: a big party with cooked food and serving. Sunday: an internal party with catering.',
        0, 0, 3, 1, 5, 'system', NOW(6), 0),
       ('Kortafton', 'Kortafton',
        'Gemensam kväll med det aktuella spexet.',
        'A joint evening with the current spex.',
        NULL, NULL, NULL, NULL,
        7, 90, 1, 0, 6, 'system', NOW(6), 0);

-- The validity audit strategy refuses to change a row without history ("Cannot update previous revision"), so the
-- seeded types get one baseline revision, as in V3_9
INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'eventTypeBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO event_type_audit (id, name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                              offset_min_days, offset_max_days, days, revival_pays, checklist_template_id, position, retired_at,
                              version, rev, revtype)
SELECT t.id, t.name_sv, t.name_en, t.description_sv, t.description_en, t.rooms_sv, t.rooms_en, t.catering_sv, t.catering_en,
       t.offset_min_days, t.offset_max_days, t.days, t.revival_pays, t.checklist_template_id, t.position, t.retired_at,
       t.version, @rev, 0
FROM event_type t
WHERE NOT EXISTS (SELECT 1 FROM event_type_audit a WHERE a.id = t.id);

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.event.EventType');
