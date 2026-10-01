package com.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** llm_calls 审计表访问。核心阶段只做写入（不做查询接口，查询接口归扩展阶段）。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {}
