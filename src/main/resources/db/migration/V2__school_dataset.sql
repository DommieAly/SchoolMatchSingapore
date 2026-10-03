-- =====================================================================================================================
-- V2__school_dataset.sql
-- The school dataset. Filled only by persistence.dataset.SchoolDatasetStore, which copies the ACTIVE JSON snapshot
-- in one transaction (design doc, section 6). The app reads these tables once into SchoolDataCache.
-- Every length below has a matching SnapshotValidator "too-long" rule, so a snapshot that validates always loads.
-- Each format CHECK pairs REGEXP_LIKE(col, '^...$') with NOT REGEXP_LIKE(col, '[^ -~]'), as in V1 (design section 8).
-- =====================================================================================================================

-- One row per snapshot version ever loaded (manifest.json plus what the loader computed).
CREATE TABLE dataset_version (
    dataset_version   VARCHAR(40)                 NOT NULL,   -- manifest "version", e.g. 2026-10-03.5, 0000-seed
    dataset_kind      VARCHAR(4)                  NOT NULL,   -- manifest "kind", lower-cased by the loader
    effective_date    DATE                        NOT NULL,
    imported_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    manifest_status   VARCHAR(24),                            -- validationStatus written by the importer
    load_status       VARCHAR(24)                 NOT NULL,   -- validationStatus computed by the loader (shown)
    notes             VARCHAR(2000),
    content_sha256    VARCHAR(64)                 NOT NULL,   -- SHA-256 of manifest.json, schools.json, districts.geojson
    loader_format     INTEGER                     NOT NULL,   -- SchoolDatasetStore.FORMAT when loaded
    loaded_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,   -- last time this version was written and activated
    CONSTRAINT pk_dataset_version PRIMARY KEY (dataset_version),
    CONSTRAINT uq_dataset_version_sha256 UNIQUE (content_sha256),
    CONSTRAINT ck_dataset_version_name
        CHECK (REGEXP_LIKE(dataset_version, '^[A-Za-z0-9._-]+$') AND NOT REGEXP_LIKE(dataset_version, '[^ -~]')),
    CONSTRAINT ck_dataset_version_kind
        CHECK (REGEXP_LIKE(dataset_kind, '^(seed|full)$') AND NOT REGEXP_LIKE(dataset_kind, '[^ -~]')),
    CONSTRAINT ck_dataset_version_manifest_status
        CHECK (REGEXP_LIKE(manifest_status, '^(PASSED|PASSED_WITH_WARNINGS|FAILED)$')
               AND NOT REGEXP_LIKE(manifest_status, '[^ -~]')),
    CONSTRAINT ck_dataset_version_load_status                 -- a FAILED snapshot is never loaded
        CHECK (REGEXP_LIKE(load_status, '^(PASSED|PASSED_WITH_WARNINGS)$') AND NOT REGEXP_LIKE(load_status, '[^ -~]')),
    CONSTRAINT ck_dataset_version_sha256
        CHECK (REGEXP_LIKE(content_sha256, '^[0-9a-f]{64}$') AND NOT REGEXP_LIKE(content_sha256, '[^ -~]')),
    CONSTRAINT ck_dataset_version_loader_format CHECK (loader_format >= 1)
);

-- manifest "sources", in manifest order (the about page lists them in this order).
CREATE TABLE dataset_source (
    dataset_version  VARCHAR(40)                 NOT NULL,
    source_no        INTEGER                     NOT NULL,     -- 1, 2, 3 ... in manifest order
    source_id        VARCHAR(80)                 NOT NULL,     -- manifest "datasetId": data.gov.sg id, onemap-..., data/curated
    source_name      VARCHAR(300)                NOT NULL,
    downloaded_at    TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_dataset_source PRIMARY KEY (dataset_version, source_no),
    CONSTRAINT uq_dataset_source_id UNIQUE (dataset_version, source_id),
    CONSTRAINT fk_dataset_source_version FOREIGN KEY (dataset_version)
        REFERENCES dataset_version (dataset_version) ON DELETE CASCADE,
    CONSTRAINT ck_dataset_source_no CHECK (source_no >= 1)
);

-- manifest "warnings", in manifest order.
CREATE TABLE dataset_warning (
    dataset_version  VARCHAR(40)   NOT NULL,
    warning_no       INTEGER       NOT NULL,
    message          VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_dataset_warning PRIMARY KEY (dataset_version, warning_no),
    CONSTRAINT fk_dataset_warning_version FOREIGN KEY (dataset_version)
        REFERENCES dataset_version (dataset_version) ON DELETE CASCADE,
    CONSTRAINT ck_dataset_warning_no CHECK (warning_no >= 1)
);

-- Exactly one row: which version the school tables hold now (NULL until the first load). The loader locks this
-- row (SELECT ... FOR UPDATE), so two app instances never load at the same time.
CREATE TABLE active_dataset (
    singleton_id     INTEGER     NOT NULL,
    dataset_version  VARCHAR(40),
    CONSTRAINT pk_active_dataset PRIMARY KEY (singleton_id),
    CONSTRAINT ck_active_dataset_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_active_dataset_version FOREIGN KEY (dataset_version)
        REFERENCES dataset_version (dataset_version) ON DELETE RESTRICT
);
INSERT INTO active_dataset (singleton_id, dataset_version) VALUES (1, NULL);

-- District (entity.school.District): a URA planning area. Never deleted; an area missing from a newer snapshot
-- gets withdrawn_in_version (a school that left the dataset may still point at it).
CREATE TABLE district (
    planning_area_code    VARCHAR(10)     NOT NULL,           -- e.g. BS
    planning_area_name    VARCHAR(60)     NOT NULL,           -- e.g. BISHAN
    boundary_geojson      VARCHAR(100000),                    -- one GeoJSON geometry; NULL = no boundary (DC-59)
    withdrawn_in_version  VARCHAR(40),                        -- NULL = in the active dataset
    CONSTRAINT pk_district PRIMARY KEY (planning_area_code),
    CONSTRAINT uq_district_name UNIQUE (planning_area_name),
    CONSTRAINT fk_district_withdrawn FOREIGN KEY (withdrawn_in_version)
        REFERENCES dataset_version (dataset_version) ON DELETE RESTRICT
);

-- School (entity.school.School, with the Place fields): one row per school code ever loaded. Never deleted,
-- because shortlists and plans refer to it (V3); a school missing from a newer snapshot gets withdrawn_in_version.
-- school_name is NOT unique: a withdrawn row keeps its name, and a school that is given a new code would clash.
CREATE TABLE school (
    school_code           VARCHAR(100)     NOT NULL,          -- frozen slug from data/curated/school-codes.csv
    school_name           VARCHAR(200)     NOT NULL,          -- Place.name
    address               VARCHAR(200),
    postal_code           VARCHAR(6),
    latitude              DOUBLE PRECISION NOT NULL,          -- Place.coordinate
    longitude             DOUBLE PRECISION NOT NULL,
    telephone             VARCHAR(40),
    website               VARCHAR(200),
    email                 VARCHAR(254),
    school_type           VARCHAR(60),
    session_type          VARCHAR(40),
    school_nature         VARCHAR(40),
    planning_area_code    VARCHAR(10)      NOT NULL,          -- School "located in" District
    withdrawn_in_version  VARCHAR(40),                        -- NULL = in the active dataset
    CONSTRAINT pk_school PRIMARY KEY (school_code),
    CONSTRAINT fk_school_district FOREIGN KEY (planning_area_code)
        REFERENCES district (planning_area_code) ON DELETE RESTRICT,
    CONSTRAINT fk_school_withdrawn FOREIGN KEY (withdrawn_in_version)
        REFERENCES dataset_version (dataset_version) ON DELETE RESTRICT,
    CONSTRAINT ck_school_code
        CHECK (REGEXP_LIKE(school_code, '^[a-z0-9-]+$') AND NOT REGEXP_LIKE(school_code, '[^ -~]')),
    CONSTRAINT ck_school_postal_code
        CHECK (REGEXP_LIKE(postal_code, '^[0-9]{6}$') AND NOT REGEXP_LIKE(postal_code, '[^ -~]')),
    CONSTRAINT ck_school_in_singapore                         -- FR-DATA-06, NFR-DATA-02
        CHECK (latitude BETWEEN 1.15 AND 1.48 AND longitude BETWEEN 103.59 AND 104.10)
);

-- IndicativePsleScoreRange (composition part of School). MOE's cell text, e.g. "6(D) - 8(M)" or "26 - 30*",
-- is stored as its parts and rebuilt by MoeRangeText.format(...):
--   lower_score + lower_hcl_grade, upper_score + upper_hcl_grade, places_left ("*").
-- places_left NULL means MOE's text is not known (seed and test data); then both grades are NULL too.
CREATE TABLE indicative_psle_score_range (
    school_code           VARCHAR(100) NOT NULL,
    admission_year        INTEGER      NOT NULL,
    posting_group         INTEGER      NOT NULL,
    affiliated            BOOLEAN      NOT NULL,
    integrated_programme  BOOLEAN      NOT NULL,              -- DC-77
    lower_score           INTEGER      NOT NULL,
    upper_score           INTEGER      NOT NULL,
    lower_hcl_grade       VARCHAR(1),                         -- Higher Chinese grade after the lower score: D or M
    upper_hcl_grade       VARCHAR(1),
    places_left           BOOLEAN,                            -- MOE's "*": places were left after posting
    CONSTRAINT pk_indicative_psle_score_range
        PRIMARY KEY (school_code, admission_year, posting_group, affiliated, integrated_programme),
    CONSTRAINT fk_indicative_psle_score_range_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE,
    CONSTRAINT ck_indicative_psle_score_range_year CHECK (admission_year BETWEEN 2022 AND 2100),   -- AL scores
    CONSTRAINT ck_indicative_psle_score_range_pg CHECK (posting_group BETWEEN 1 AND 3),
    CONSTRAINT ck_indicative_psle_score_range_scores
        CHECK (lower_score BETWEEN 4 AND 32 AND upper_score BETWEEN 4 AND 32 AND lower_score <= upper_score),
    CONSTRAINT ck_indicative_psle_score_range_ip_pg3 CHECK (integrated_programme = FALSE OR posting_group = 3),
    CONSTRAINT ck_indicative_psle_score_range_lower_grade
        CHECK (REGEXP_LIKE(lower_hcl_grade, '^[DM]$') AND NOT REGEXP_LIKE(lower_hcl_grade, '[^ -~]')),
    CONSTRAINT ck_indicative_psle_score_range_upper_grade
        CHECK (REGEXP_LIKE(upper_hcl_grade, '^[DM]$') AND NOT REGEXP_LIKE(upper_hcl_grade, '[^ -~]')),
    CONSTRAINT ck_indicative_psle_score_range_text_known
        CHECK (places_left IS NOT NULL OR (lower_hcl_grade IS NULL AND upper_hcl_grade IS NULL))
);

-- School.ccas, School.programmes, School.affiliatedPrimarySchools: sets of names (all-key tables).
CREATE TABLE school_cca (
    school_code  VARCHAR(100) NOT NULL,
    cca_name     VARCHAR(100) NOT NULL,
    CONSTRAINT pk_school_cca PRIMARY KEY (school_code, cca_name),
    CONSTRAINT fk_school_cca_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE
);

CREATE TABLE school_programme (
    school_code     VARCHAR(100) NOT NULL,
    programme_name  VARCHAR(100) NOT NULL,
    CONSTRAINT pk_school_programme PRIMARY KEY (school_code, programme_name),
    CONSTRAINT fk_school_programme_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE
);

-- Many-to-many: 6 primary schools are affiliated with 2 secondary schools each.
CREATE TABLE school_affiliated_primary (
    school_code          VARCHAR(100) NOT NULL,
    primary_school_name  VARCHAR(120) NOT NULL,
    CONSTRAINT pk_school_affiliated_primary PRIMARY KEY (school_code, primary_school_name),
    CONSTRAINT fk_school_affiliated_primary_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE
);

-- School.busInfo as a list: one row per bus service, in published order (snapshot field "busServices", DC-84).
CREATE TABLE school_bus_service (
    school_code    VARCHAR(100) NOT NULL,
    service_no     VARCHAR(10)  NOT NULL,                     -- e.g. 904, 70M, 74e, CT18
    list_position  INTEGER      NOT NULL,                     -- 1, 2, 3 ...
    CONSTRAINT pk_school_bus_service PRIMARY KEY (school_code, service_no),
    CONSTRAINT uq_school_bus_service_position UNIQUE (school_code, list_position),
    CONSTRAINT fk_school_bus_service_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE,
    CONSTRAINT ck_school_bus_service_no
        CHECK (REGEXP_LIKE(service_no, '^[A-Z]{0,2}[0-9]{1,3}[A-Za-z]?$') AND NOT REGEXP_LIKE(service_no, '[^ -~]')),
    CONSTRAINT ck_school_bus_service_position CHECK (list_position >= 1)
);

-- School.nearestMrt as a list: one row per MRT/LRT station, in published order (snapshot field "mrtStations").
CREATE TABLE school_mrt_station (
    school_code    VARCHAR(100) NOT NULL,
    station_name   VARCHAR(80)  NOT NULL,                     -- as published, e.g. OUTRAM PARK MRT (EW16)
    list_position  INTEGER      NOT NULL,
    CONSTRAINT pk_school_mrt_station PRIMARY KEY (school_code, station_name),
    CONSTRAINT uq_school_mrt_station_position UNIQUE (school_code, list_position),
    CONSTRAINT fk_school_mrt_station_school FOREIGN KEY (school_code)
        REFERENCES school (school_code) ON DELETE CASCADE,
    CONSTRAINT ck_school_mrt_station_one_name CHECK (station_name NOT LIKE '%,%'),   -- one station per row (1NF)
    CONSTRAINT ck_school_mrt_station_position CHECK (list_position >= 1)
);
