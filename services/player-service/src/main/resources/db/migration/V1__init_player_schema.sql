CREATE TABLE IF NOT EXISTS players (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    elo INT NOT NULL DEFAULT 1000,
    matches_played INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    draws INT NOT NULL DEFAULT 0,
    total_points_scored INT NOT NULL DEFAULT 0,
    total_points_conceded INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_players_username_lower ON players (LOWER(username));

CREATE TABLE IF NOT EXISTS player_matches (
    id UUID PRIMARY KEY,
    match_id UUID NOT NULL,
    player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    opponent_id UUID NOT NULL,
    opponent_username VARCHAR(64) NOT NULL,
    player_score INT NOT NULL,
    opponent_score INT NOT NULL,
    result VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    duration_seconds INT NOT NULL DEFAULT 0,
    finished_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_player_matches_match_player UNIQUE (match_id, player_id)
);

CREATE INDEX IF NOT EXISTS ix_player_matches_player_finished ON player_matches (player_id, finished_at DESC);

CREATE TABLE IF NOT EXISTS processed_messages (
    message_id UUID PRIMARY KEY,
    message_type VARCHAR(100) NOT NULL,
    correlation_id UUID,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
