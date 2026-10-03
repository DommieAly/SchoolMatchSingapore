-- =====================================================================================================================
-- V1__user_tables.sql
-- The tables Hibernate used to create with ddl-auto: update, now created by Flyway. Hibernate only validates them
-- (ddl-auto: validate). Column names and types equal Hibernate 7.4's own DDL for the current entities; the keys and
-- CHECK constraints are new and repeat rules the Java code already enforces.
-- Each format CHECK pairs REGEXP_LIKE(col, '^...$') with NOT REGEXP_LIKE(col, '[^ -~]') (printable ASCII only):
-- H2 uses Java regex, where '$' also matches just before a final line break, so without the second test H2 would
-- accept 'ACTIVE' plus a line break while PostgreSQL refuses it (design section 8).
-- =====================================================================================================================

-- Account (entity.account.Account): one registered user.
CREATE TABLE account (
    account_id     VARCHAR(36)                 NOT NULL,   -- UUID text
    username       VARCHAR(30)                 NOT NULL,   -- as typed: ^[A-Za-z0-9_]{3,30}$ (AccountController)
    username_key   VARCHAR(30)                 NOT NULL,   -- username trimmed and lower-cased (DC-70)
    email          VARCHAR(254)                NOT NULL,   -- stored lower-case by the Account constructor
    password_hash  VARCHAR(100)                NOT NULL,   -- BCrypt hash; never the password (NFR-SEC-01)
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    status         VARCHAR(16)                 NOT NULL,   -- AccountStatus, by name
    CONSTRAINT pk_account PRIMARY KEY (account_id),
    CONSTRAINT uq_account_username UNIQUE (username),
    CONSTRAINT uq_account_username_key UNIQUE (username_key),
    CONSTRAINT uq_account_email UNIQUE (email),
    CONSTRAINT ck_account_username_key CHECK (username_key = LOWER(TRIM(username))),
    CONSTRAINT ck_account_status
        CHECK (REGEXP_LIKE(status, '^(ACTIVE|INACTIVE)$') AND NOT REGEXP_LIKE(status, '[^ -~]'))
);

-- AuthenticatedSession: one login. Ended rows are deleted every hour (DC-73).
CREATE TABLE authenticated_session (
    session_id   VARCHAR(64)                 NOT NULL,     -- value of the SM_SESSION cookie
    account_id   VARCHAR(36)                 NOT NULL,
    issued_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    invalidated  BOOLEAN                     NOT NULL,
    CONSTRAINT pk_authenticated_session PRIMARY KEY (session_id),
    CONSTRAINT fk_authenticated_session_account FOREIGN KEY (account_id)
        REFERENCES account (account_id) ON DELETE CASCADE,
    CONSTRAINT ck_authenticated_session_times CHECK (expires_at >= issued_at)
);
CREATE INDEX ix_authenticated_session_account ON authenticated_session (account_id);
CREATE INDEX ix_authenticated_session_expires ON authenticated_session (expires_at);   -- DC-73 clean-up query

-- UserProfile: shares its key with the account (@MapsId). All fields are optional until the member fills them in.
CREATE TABLE user_profile (
    account_id       VARCHAR(36)      NOT NULL,
    display_name     VARCHAR(50),                          -- ProfileController limits (trimmed text)
    psle_score       INTEGER,
    posting_group    INTEGER,
    primary_school   VARCHAR(100),
    home_address     VARCHAR(200),
    home_latitude    DOUBLE PRECISION,                     -- Coordinate homeLocation (embedded)
    home_longitude   DOUBLE PRECISION,
    max_commute_min  INTEGER,
    travel_mode      VARCHAR(16),                          -- TravelMode, by name
    CONSTRAINT pk_user_profile PRIMARY KEY (account_id),
    CONSTRAINT fk_user_profile_account FOREIGN KEY (account_id)
        REFERENCES account (account_id) ON DELETE CASCADE,
    CONSTRAINT ck_user_profile_psle_score CHECK (psle_score BETWEEN 4 AND 32),
    CONSTRAINT ck_user_profile_posting_group CHECK (posting_group BETWEEN 1 AND 3),
    CONSTRAINT ck_user_profile_max_commute                   -- 15, 30, 45 or 60
        CHECK (max_commute_min BETWEEN 15 AND 60 AND MOD(max_commute_min, 15) = 0),
    CONSTRAINT ck_user_profile_travel_mode
        CHECK (REGEXP_LIKE(travel_mode, '^(WALK|DRIVE|TRANSIT)$') AND NOT REGEXP_LIKE(travel_mode, '[^ -~]')),
    CONSTRAINT ck_user_profile_home_pair CHECK ((home_latitude IS NULL) = (home_longitude IS NULL)),
    CONSTRAINT ck_user_profile_home_in_sg                  -- Coordinate's Singapore box
        CHECK (home_latitude BETWEEN 1.15 AND 1.48 AND home_longitude BETWEEN 103.59 AND 104.10)
);

-- UserProfile.preferredCCAs and preferredProgrammes (@ElementCollection sets). No foreign key to school_cca or
-- school_programme: a preference must survive a dataset in which no school offers it any more (DC-57).
CREATE TABLE user_profile_cca (
    account_id  VARCHAR(36)  NOT NULL,
    cca         VARCHAR(255) NOT NULL,
    CONSTRAINT pk_user_profile_cca PRIMARY KEY (account_id, cca),
    CONSTRAINT fk_user_profile_cca_profile FOREIGN KEY (account_id)
        REFERENCES user_profile (account_id) ON DELETE CASCADE
);

CREATE TABLE user_profile_programme (
    account_id  VARCHAR(36)  NOT NULL,
    programme   VARCHAR(255) NOT NULL,
    CONSTRAINT pk_user_profile_programme PRIMARY KEY (account_id, programme),
    CONSTRAINT fk_user_profile_programme_profile FOREIGN KEY (account_id)
        REFERENCES user_profile (account_id) ON DELETE CASCADE
);

-- ChoicePlan.id is @GeneratedValue: Hibernate's default is a sequence named choice_plan_seq that hands out
-- blocks of 50. Hibernate checks the increment at start-up, so it must be 50.
CREATE SEQUENCE choice_plan_seq START WITH 1 INCREMENT BY 50;

-- ChoicePlan: keeps the score and posting group it was made for (DC-65).
CREATE TABLE choice_plan (
    id              BIGINT                      NOT NULL,
    based_on_score  INTEGER,
    posting_group   INTEGER,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_choice_plan PRIMARY KEY (id),
    CONSTRAINT ck_choice_plan_score CHECK (based_on_score BETWEEN 4 AND 32),
    CONSTRAINT ck_choice_plan_posting_group CHECK (posting_group BETWEEN 1 AND 3)
);

-- SchoolChoice (@Embeddable in ChoicePlan.choices): one ranked school. school_code gets its foreign key in V3.
CREATE TABLE choice_plan_choice (
    choice_plan_id  BIGINT       NOT NULL,
    choice_rank     INTEGER      NOT NULL,
    school_code     VARCHAR(100) NOT NULL,
    CONSTRAINT pk_choice_plan_choice PRIMARY KEY (choice_plan_id, choice_rank),
    CONSTRAINT uq_choice_plan_choice_school UNIQUE (choice_plan_id, school_code),
    CONSTRAINT fk_choice_plan_choice_plan FOREIGN KEY (choice_plan_id)
        REFERENCES choice_plan (id) ON DELETE CASCADE,
    CONSTRAINT ck_choice_plan_choice_rank CHECK (choice_rank BETWEEN 1 AND 6)   -- ChoicePlan.MAX_CHOICES
);
CREATE INDEX ix_choice_plan_choice_school ON choice_plan_choice (school_code);

-- Shortlist: one per account; owns the account's choice plan (orphanRemoval).
CREATE TABLE shortlist (
    account_id      VARCHAR(36)                 NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    choice_plan_id  BIGINT,
    CONSTRAINT pk_shortlist PRIMARY KEY (account_id),
    CONSTRAINT uq_shortlist_choice_plan UNIQUE (choice_plan_id),
    CONSTRAINT fk_shortlist_account FOREIGN KEY (account_id)
        REFERENCES account (account_id) ON DELETE CASCADE,
    CONSTRAINT fk_shortlist_choice_plan FOREIGN KEY (choice_plan_id)
        REFERENCES choice_plan (id) ON DELETE SET NULL
);

-- Shortlist.schoolCodes: one row per saved school (FR-DATA-05). No position column (DC-35).
CREATE TABLE shortlist_school (
    account_id   VARCHAR(36)  NOT NULL,
    school_code  VARCHAR(100) NOT NULL,
    CONSTRAINT pk_shortlist_school PRIMARY KEY (account_id, school_code),
    CONSTRAINT fk_shortlist_school_shortlist FOREIGN KEY (account_id)
        REFERENCES shortlist (account_id) ON DELETE CASCADE
);
CREATE INDEX ix_shortlist_school_school ON shortlist_school (school_code);

-- persistence.ExternalUsage: live Google units used per budget day and SKU (DC-41, DC-80). Not a design class.
CREATE TABLE external_usage (
    usage_day  DATE        NOT NULL,
    sku        VARCHAR(50) NOT NULL,
    used       INTEGER     NOT NULL,
    CONSTRAINT pk_external_usage PRIMARY KEY (usage_day, sku),
    CONSTRAINT ck_external_usage_used CHECK (used >= 0)
);
