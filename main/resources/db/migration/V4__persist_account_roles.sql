-- All existing users remain ordinary. Legacy admin/actions are preserved, never auto-promoted.
ALTER TABLE users ADD COLUMN role varchar(16) NOT NULL DEFAULT 'User';
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('User', 'Admin'));
