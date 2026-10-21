CREATE TABLE IF NOT EXISTS person
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    external_id            VARCHAR(36)           NOT NULL,
    email                  VARCHAR(255)          NOT NULL,
    email_verified         BIT                   NOT NULL DEFAULT 0,
    display_name           VARCHAR(255)          NULL,
    CONSTRAINT PK_PERSON PRIMARY KEY (id),
    CONSTRAINT UC_PERSON_EXTERNAL_ID UNIQUE (external_id)
);

CREATE INDEX IX_PERSON_ON_EMAIL ON person (email);
