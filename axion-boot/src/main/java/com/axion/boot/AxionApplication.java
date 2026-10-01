package com.axion.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Axion 启动入口.
 *
 * <p>Spring Boot 主类，负责：
 *
 * <ul>
 *   <li>启动 Spring 容器
 *   <li>触发自动配置
 *   <li>聚合所有模块依赖
 * </ul>
 *
 * <p>启动模块聚合全部 8 个业务模块，组件分布在 {@code com.axion.*} 各包 （web 的 Controller / api 的
 * GlobalExceptionHandler、provider、memory 等）， 必须显式扩大扫描根到 {@code com.axion}，否则跨包组件不会被注册。
 *
 * <p>注意（002-react 人工验收实机暴露）：{@code scanBasePackages} 只作用于组件扫描，**不作用于 JPA 扫描**——仓储与 实体扫描根由
 * AutoConfigurationPackages 决定（默认仍是 {@code com.axion.boot}），必须显式声明 {@code com.axion.storage}，否则
 * {@code LlmCallRepository}/{@code ToolInvocationRepository} 及其实体不会被注册。
 */
@EnableJpaRepositories(basePackages = "com.axion.storage")
@EntityScan(basePackages = "com.axion.storage")
@SpringBootApplication(scanBasePackages = "com.axion")
public class AxionApplication {

  public static void main(String[] args) {
    SpringApplication.run(AxionApplication.class, args);
  }
}
