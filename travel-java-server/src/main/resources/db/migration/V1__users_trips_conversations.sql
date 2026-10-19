CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(32) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE trips (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(80) NOT NULL,
 city VARCHAR(50) NOT NULL,
 days INT NOT NULL,
 budget DECIMAL(12,2) NOT NULL,
 current_version INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX ix_trips_user_updated (user_id, updated_at, id)
);
CREATE TABLE trip_versions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 trip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version INT NOT NULL,
 plan_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE,
 UNIQUE KEY uk_trip_version (trip_id, version)
);
CREATE TABLE conversations (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 trip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 title VARCHAR(80) NOT NULL,
 context_json JSON NOT NULL,
 active_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE SET NULL,
 UNIQUE KEY uk_conversation_owner (id, user_id),
 INDEX ix_conversations_user_updated (user_id, updated_at, id)
);
CREATE TABLE messages (
 seq BIGINT PRIMARY KEY AUTO_INCREMENT,
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 role VARCHAR(12) NOT NULL,
 content MEDIUMTEXT NOT NULL,
 status VARCHAR(16) NOT NULL,
 error_code VARCHAR(80) NULL,
 error_message VARCHAR(255) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY (conversation_id, user_id) REFERENCES conversations(id, user_id) ON DELETE CASCADE,
 UNIQUE KEY uk_message_request_role (user_id, request_id, role),
 INDEX ix_message_conversation_seq (conversation_id, seq)
);
