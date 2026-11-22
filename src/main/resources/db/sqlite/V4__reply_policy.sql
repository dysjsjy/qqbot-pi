CREATE TABLE reply_policy (
 group_id text PRIMARY KEY REFERENCES group_config(id) ON DELETE CASCADE,
 mode text NOT NULL DEFAULT 'MENTION',
 probability real NOT NULL DEFAULT 0.1,
 energy integer NOT NULL DEFAULT 0,
 energy_gain integer NOT NULL DEFAULT 1,
 energy_cost integer NOT NULL DEFAULT 10
);
