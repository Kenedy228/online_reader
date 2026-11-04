-- Populated on first startup under the exclusive PostgreSQL application lease.
CREATE TABLE book_storage (
    id integer PRIMARY KEY CHECK (id = 1),
    root_id uuid NOT NULL
);
