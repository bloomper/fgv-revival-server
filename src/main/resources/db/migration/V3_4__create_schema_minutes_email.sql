-- Records of minutes sent by email; append-only and themselves the record, so not audited (research R9)
CREATE TABLE IF NOT EXISTS minutes_email
(
    id                     BIGINT AUTO_INCREMENT NOT NULL,
    meeting_id             BIGINT                NOT NULL,
    language               VARCHAR(2)            NOT NULL,
    sent_by                BIGINT                NOT NULL,
    sent_at                DATETIME(6)           NOT NULL,
    retry_of               BIGINT                NULL,
    waiting_left_out       INT                   NOT NULL,
    CONSTRAINT PK_MINUTES_EMAIL PRIMARY KEY (id),
    CONSTRAINT FK_MINUTES_EMAIL_ON_MEETING FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT FK_MINUTES_EMAIL_ON_SENT_BY FOREIGN KEY (sent_by) REFERENCES person (id),
    CONSTRAINT FK_MINUTES_EMAIL_ON_RETRY_OF FOREIGN KEY (retry_of) REFERENCES minutes_email (id),
    CONSTRAINT CK_MINUTES_EMAIL_LANGUAGE CHECK (language IN ('sv', 'en'))
) DEFAULT CHARSET = utf8mb4;

CREATE INDEX IX_MINUTES_EMAIL_ON_MEETING ON minutes_email (meeting_id);

-- Recipients as person ids; addresses are read at send time and never stored (FR-021)
CREATE TABLE IF NOT EXISTS minutes_email_recipient
(
    email_id               BIGINT                NOT NULL,
    person_id              BIGINT                NOT NULL,
    status                 VARCHAR(8)            NOT NULL,
    CONSTRAINT PK_MINUTES_EMAIL_RECIPIENT PRIMARY KEY (email_id, person_id),
    CONSTRAINT FK_MINUTES_EMAIL_RECIPIENT_ON_EMAIL FOREIGN KEY (email_id) REFERENCES minutes_email (id),
    CONSTRAINT FK_MINUTES_EMAIL_RECIPIENT_ON_PERSON FOREIGN KEY (person_id) REFERENCES person (id),
    CONSTRAINT CK_MINUTES_EMAIL_RECIPIENT_STATUS CHECK (status IN ('SENT', 'FAILED'))
) DEFAULT CHARSET = utf8mb4;
