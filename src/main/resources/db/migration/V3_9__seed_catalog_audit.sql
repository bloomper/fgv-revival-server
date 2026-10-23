-- V3_8 seeded the catalogs without audit rows, and the validity audit strategy refuses to update an entity that has
-- none ("Cannot update previous revision"). One baseline revision records every entry that still lacks history.
INSERT INTO revinfo (modified_at, modified_by, source, operation)
VALUES (UNIX_TIMESTAMP(NOW(3)) * 1000, 'system', 'SYSTEM', 'catalogBaseline');

SET @rev = LAST_INSERT_ID();

INSERT INTO capability_audit (id, name_sv, name_en, position, retired_at, version, rev, revtype)
SELECT c.id, c.name_sv, c.name_en, c.position, c.retired_at, c.version, @rev, 0
FROM capability c
WHERE NOT EXISTS (SELECT 1 FROM capability_audit a WHERE a.id = c.id);

INSERT INTO guide_item_type_audit (id, kind, name_sv, name_en, position, retired_at, version, rev, revtype)
SELECT t.id, t.kind, t.name_sv, t.name_en, t.position, t.retired_at, t.version, @rev, 0
FROM guide_item_type t
WHERE NOT EXISTS (SELECT 1 FROM guide_item_type_audit a WHERE a.id = t.id);

INSERT INTO revchanges (rev, entityname)
VALUES (@rev, 'nu.fgv.revival.server.member.Capability'),
       (@rev, 'nu.fgv.revival.server.guide.GuideItemType');
