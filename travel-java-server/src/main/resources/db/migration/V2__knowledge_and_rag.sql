CREATE TABLE knowledge_documents (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 city VARCHAR(20) NOT NULL,
 source_url VARCHAR(1000) NOT NULL,
 published_on DATE NULL,
 valid_from DATE NULL,
 valid_to DATE NULL,
 origin VARCHAR(32) NOT NULL,
 content MEDIUMTEXT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
 embedding_model VARCHAR(120) NOT NULL,
 dimensions INT NOT NULL,
 chunk_count INT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id),
 UNIQUE KEY uk_knowledge_fingerprint(user_id,fingerprint),
 INDEX ix_knowledge_filter(user_id,city,valid_from,valid_to)
);
CREATE TABLE knowledge_chunks (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal INT NOT NULL,
 start_offset INT NOT NULL,
 end_offset INT NOT NULL,
 content TEXT NOT NULL,
 embedding JSON NOT NULL,
 FOREIGN KEY(document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
 UNIQUE KEY uk_knowledge_chunk(document_id,ordinal)
);
CREATE TABLE rag_questions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL,
 question VARCHAR(2000) NOT NULL,
 city VARCHAR(20) NOT NULL,
 as_of DATE NOT NULL,
 status VARCHAR(20) NOT NULL,
 answer_json JSON NULL,
 error_code VARCHAR(80) NULL,
 message VARCHAR(255) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id),
 INDEX ix_rag_owner(user_id,created_at,id)
);
