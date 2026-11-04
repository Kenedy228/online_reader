-- Additive migration. Rollback: stop writes and restore the pre-migration DB backup.
ALTER TABLE books ADD CONSTRAINT books_id_owner_key UNIQUE (id, owner_id);
CREATE TABLE reading_progress (
    book_id bigint PRIMARY KEY,
    owner_id integer NOT NULL,
    chapter varchar(8) NOT NULL CHECK (chapter ~ '^(text|s[0-9]{1,4})$'),
    position_offset integer NOT NULL CHECK (position_offset BETWEEN 0 AND 10485760),
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    representation_version integer NOT NULL CHECK (representation_version = 1),
    revision integer NOT NULL CHECK (revision > 0),
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT progress_book_owner_fk FOREIGN KEY (book_id, owner_id) REFERENCES books(id, owner_id) ON DELETE CASCADE
);
