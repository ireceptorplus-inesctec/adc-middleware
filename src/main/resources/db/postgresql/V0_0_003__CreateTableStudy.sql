CREATE TABLE study (
	id SERIAL PRIMARY KEY,
	study_id VARCHAR NOT NULL UNIQUE,
	uma_id VARCHAR NOT NULL UNIQUE,
	is_public BOOLEAN NOT NULL DEFAULT FALSE
);
