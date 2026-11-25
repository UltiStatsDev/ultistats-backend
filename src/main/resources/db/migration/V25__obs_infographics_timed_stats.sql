ALTER TABLE obs_infographics_settings ADD COLUMN stats_seconds INTEGER NOT NULL DEFAULT 10 CHECK (stats_seconds > 0);
ALTER TABLE obs_infographics_settings_history ADD COLUMN stats_seconds INTEGER NOT NULL DEFAULT 10 CHECK (stats_seconds > 0);

ALTER TABLE obs_infographics_presentations ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'LEADERS';
ALTER TABLE obs_infographics_presentations ADD COLUMN origin VARCHAR(6) NOT NULL DEFAULT 'MANUAL' CHECK (origin IN ('MANUAL', 'AUTO'));
ALTER TABLE obs_infographics_presentations DROP CONSTRAINT obs_infographics_presentations_page_seconds_check;
ALTER TABLE obs_infographics_presentations ADD CHECK (page_seconds > 0 AND (kind = 'STATS' OR page_seconds BETWEEN 2 AND 15));

-- The lock row is stable across generations, including an empty or inactive binding.
CREATE TABLE obs_infographics_presentation_bindings (binding VARCHAR(36) PRIMARY KEY);
