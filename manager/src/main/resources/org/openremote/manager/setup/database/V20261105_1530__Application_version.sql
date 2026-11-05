-- Only first activations from this migration onwards are recorded.
CREATE TABLE application_version (
    version TEXT PRIMARY KEY,
    -- First activation timestamp; subsequent startups never change it.
    updated_on TIMESTAMP WITH TIME ZONE NOT NULL
);
