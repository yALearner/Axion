-- llm_calls 审计表（手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- 相对需求文档 §10 原表结构的补充修订：新增 success / error_message 两列，
-- 保证调用失败时事故在库里同样留痕（与 tool_invocations 对称）。

CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        TEXT    NOT NULL,
    provider          TEXT    NOT NULL,
    model             TEXT    NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    success           INTEGER NOT NULL,
    error_message     TEXT,
    duration_ms       INTEGER,
    created_at        TEXT    NOT NULL
);

-- tool_invocations 审计表（002-react 新增，手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- 与 llm_calls 同口径：成功与失败都落账（success / error_message 两列真实存在），
-- created_at 以 ISO-8601 TEXT 存储（SQLite 无原生 TIMESTAMP，复用 InstantTextConverter）。

CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT    NOT NULL,
    tool_name     TEXT    NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       INTEGER NOT NULL,
    error_message TEXT,
    duration_ms   INTEGER,
    created_at    TEXT    NOT NULL
);

-- sessions 会话表（003-cli 新增，手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- 字段照技术方案 §9.2：session_id 由 SessionManager 按 channel|user|profile 唯一拼接；
-- messages_json 对话历史整体 JSON 序列化一列存（核心阶段不按条拆表）；
-- 时间戳 ISO-8601 TEXT（SQLite 无原生 TIMESTAMP，复用 InstantTextConverter）；归档流转归第 26 节。

CREATE TABLE IF NOT EXISTS sessions (
    session_id     TEXT PRIMARY KEY,
    profile_name   TEXT NOT NULL,
    channel        TEXT NOT NULL,
    user_id        TEXT NOT NULL,
    messages_json  TEXT,
    status         TEXT NOT NULL DEFAULT 'active',
    created_at     TEXT NOT NULL,
    last_active_at TEXT NOT NULL,
    archived_at    TEXT
);

-- notify_channels 通知渠道全局注册表（004-notify 新增，手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- 技术方案 §6.8：Agent 正文按 name 引用渠道，webhook 地址不进对话、不进 frontmatter；
-- type 核心阶段均为 webhook（扩展阶段其他类型自行解释 url 语义）；CRUD 归 Web Service 节。

CREATE TABLE IF NOT EXISTS notify_channels (
    name        TEXT PRIMARY KEY,
    type        TEXT NOT NULL,
    url         TEXT NOT NULL,
    description TEXT
);

-- memory_entries 长期记忆表（006-memory 新增，手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- sqlite 档行存储：scope 列与 MEMORY.md 两区块一一对应（CORE / ARCHIVAL，坑十七）；
-- created_at ISO-8601 TEXT（SQLite 无原生 TIMESTAMP，复用 InstantTextConverter）。

CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    content    TEXT NOT NULL,
    scope      TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- scheduled_tasks 定时任务状态表（010-scheduler-mgmt 新增，手工维护，不依赖 hibernate.ddl-auto 自动迁移）。
-- 定义源是 AGENT.md frontmatter 的 schedules（含 id），本表只存「状态」不作为定义源——重启时从文件重新注册。
-- task_id = frontmatter id（全局唯一，跨 Profile 冲突注册时报错）；next_run_at/last_run_at 以
-- ISO-8601 TEXT 存储 UTC（复用 InstantTextConverter）；enabled 停用后到点跳过不记历史。

CREATE TABLE IF NOT EXISTS scheduled_tasks (
    task_id      TEXT PRIMARY KEY,
    profile_name TEXT    NOT NULL,
    cron         TEXT    NOT NULL,
    zone         TEXT,
    message      TEXT    NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at  TEXT,
    last_run_at  TEXT,
    last_status  TEXT,
    run_count    INTEGER NOT NULL DEFAULT 0
);

-- task_executions 定时任务执行历史表（010-scheduler-mgmt 新增，手工维护）。
-- 成功失败都记（宪法 V 同源）：success=false 时 error_message 存人可读消息（非堆栈）；
-- started_at 以 ISO-8601 TEXT 存储 UTC（复用 InstantTextConverter）。

CREATE TABLE IF NOT EXISTS task_executions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id       TEXT    NOT NULL,
    session_id    TEXT,
    started_at    TEXT    NOT NULL,
    success       BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT
);
