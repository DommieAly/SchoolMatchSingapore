-- =====================================================================================================================
-- V3__school_references.sql
-- A saved school code must name a school row. School rows are never deleted (a school missing from a newer
-- snapshot only gets withdrawn_in_version), so these keys never block a dataset load; RESTRICT makes the database
-- refuse a manual DELETE of a school that someone has saved.
-- =====================================================================================================================

ALTER TABLE shortlist_school ADD CONSTRAINT fk_shortlist_school_school
    FOREIGN KEY (school_code) REFERENCES school (school_code) ON DELETE RESTRICT;

ALTER TABLE choice_plan_choice ADD CONSTRAINT fk_choice_plan_choice_school
    FOREIGN KEY (school_code) REFERENCES school (school_code) ON DELETE RESTRICT;
