-- Enable btree_gist extension to allow equality operator (=) on UUID inside GiST exclusion constraint
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Prevent overlapping time ranges for the same resource at the database level
ALTER TABLE slots
    ADD CONSTRAINT slots_no_overlap
    EXCLUDE USING gist (
        resource_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    );
