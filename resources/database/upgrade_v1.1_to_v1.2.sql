-- resources/database/upgrade_v1.1_to_v1.2.sql
-- RBAC: 角色 + 知识库可见性

CREATE TABLE IF NOT EXISTS t_role (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(256),
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0,
    CONSTRAINT uk_role_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS t_role_kb_relation (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    role_id     VARCHAR(20) NOT NULL,
    kb_id       VARCHAR(20) NOT NULL,
    created_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_role_kb_role_id ON t_role_kb_relation (role_id);
CREATE INDEX IF NOT EXISTS idx_role_kb_kb_id ON t_role_kb_relation (kb_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_kb ON t_role_kb_relation (role_id, kb_id) WHERE deleted = 0;

CREATE TABLE IF NOT EXISTS t_user_role (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(20) NOT NULL,
    role_id     VARCHAR(20) NOT NULL,
    created_by  VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON t_user_role (user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON t_user_role (role_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_role ON t_user_role (user_id, role_id) WHERE deleted = 0;
