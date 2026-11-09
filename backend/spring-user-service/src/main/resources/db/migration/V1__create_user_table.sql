-- user 是 PostgreSQL 保留字，資料表改用 users
CREATE TABLE user_service.users (
    id             UUID          PRIMARY KEY,
    -- 區分大小寫：Foo@x.com 與 foo@x.com 視為不同帳號；254 為 email 的實務長度上限
    email          VARCHAR(254)  NOT NULL,
    -- DelegatingPasswordEncoder 格式，例如 {bcrypt}$2a$10$...，預留更換演算法的空間
    password_hash  VARCHAR(255)  NOT NULL,
    display_name   VARCHAR(50)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     UUID          NOT NULL,
    updated_by     UUID          NOT NULL,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- 對應 User 實體的 @ElementCollection，本身沒有 id 與稽核欄位
CREATE TABLE user_service.user_role (
    user_id  UUID         NOT NULL REFERENCES user_service.users(id) ON DELETE CASCADE,
    role     VARCHAR(20)  NOT NULL,

    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT chk_user_role_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);
