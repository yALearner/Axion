package com.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** sessions 表访问（003-cli 交付）。核心阶段由 SessionManager 读写；查询接口归第 26 节 Web Service。 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {}
