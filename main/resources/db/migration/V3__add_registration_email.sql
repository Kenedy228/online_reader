-- Old accounts keep NULL: email must be supplied by the owner, never invented.
-- The public registration DTO/service requires a nonblank email for every new signup.
ALTER TABLE users ADD COLUMN email varchar(254);
ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT users_email_normalized_check
    CHECK (email IS NULL OR (email <> '' AND email = lower(btrim(email))));
