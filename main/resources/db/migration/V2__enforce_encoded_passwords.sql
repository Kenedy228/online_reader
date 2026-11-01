-- Preserve recognized legacy BCrypt hashes without rehashing or inventing credentials.
-- Unknown/plaintext values abort this transactional migration; see docs/authentication.md.
ALTER TABLE users ALTER COLUMN password TYPE varchar(255);
ALTER TABLE admin ALTER COLUMN password TYPE varchar(255);
UPDATE users SET password = '{bcrypt}' || password
    WHERE password ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$';
UPDATE admin SET password = '{bcrypt}' || password
    WHERE password ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$';
ALTER TABLE users ADD CONSTRAINT users_password_hash_check
    CHECK (password ~ '^\{bcrypt\}\$2[aby]\$12\$[./A-Za-z0-9]{53}$');
ALTER TABLE admin ADD CONSTRAINT admin_password_hash_check
    CHECK (password ~ '^\{bcrypt\}\$2[aby]\$12\$[./A-Za-z0-9]{53}$');
