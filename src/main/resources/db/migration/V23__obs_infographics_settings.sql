CREATE TABLE obs_infographics_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    roster_page_seconds INTEGER NOT NULL CHECK (roster_page_seconds BETWEEN 2 AND 15),
    point_page_seconds INTEGER NOT NULL CHECK (point_page_seconds BETWEEN 2 AND 15),
    leader_page_seconds INTEGER NOT NULL CHECK (leader_page_seconds BETWEEN 2 AND 15)
);
INSERT INTO obs_infographics_settings VALUES (1, 0, 5, 5, 5);
CREATE TABLE obs_infographics_settings_history (
    revision BIGINT PRIMARY KEY,
    effective_at TIMESTAMPTZ NOT NULL,
    roster_page_seconds INTEGER NOT NULL CHECK (roster_page_seconds BETWEEN 2 AND 15),
    point_page_seconds INTEGER NOT NULL CHECK (point_page_seconds BETWEEN 2 AND 15),
    leader_page_seconds INTEGER NOT NULL CHECK (leader_page_seconds BETWEEN 2 AND 15)
);
