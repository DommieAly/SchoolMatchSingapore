-- School rows for @DataJpaTest classes (@Sql("/sql/schools.sql")). Since V3__school_references.sql, every school
-- code saved in shortlist_school or choice_plan_choice must name a school row (docs/database-design.md, step 7).
-- Runs inside each test's transaction, so it is rolled back with the test. Same SQL on H2 and PostgreSQL.
INSERT INTO dataset_version (dataset_version, dataset_kind, effective_date, imported_at, manifest_status, load_status,
                             content_sha256, loader_format, loaded_at)
    VALUES ('test-1', 'seed', DATE '2026-10-04', TIMESTAMP WITH TIME ZONE '2026-10-04 00:00:00+00', 'PASSED', 'PASSED',
            '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 1,
            TIMESTAMP WITH TIME ZONE '2026-10-04 00:00:00+00');
INSERT INTO district (planning_area_code, planning_area_name) VALUES ('BS', 'BISHAN');
INSERT INTO district (planning_area_code, planning_area_name) VALUES ('TM', 'TAMPINES');
INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)
    VALUES ('catholic-high-school', 'CATHOLIC HIGH SCHOOL', 1.354525, 103.844901, 'BS');
INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)
    VALUES ('tampines-secondary-school', 'TAMPINES SECONDARY SCHOOL', 1.3546, 103.9533, 'TM');
INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code)
    VALUES ('raffles-institution', 'RAFFLES INSTITUTION', 1.3467, 103.8436, 'BS');
-- a school that left the dataset: its row stays, so a shortlist that saved it still saves and reloads
INSERT INTO school (school_code, school_name, latitude, longitude, planning_area_code, withdrawn_in_version)
    VALUES ('closed-secondary-school', 'CLOSED SECONDARY SCHOOL', 1.35, 103.85, 'BS', 'test-1');
