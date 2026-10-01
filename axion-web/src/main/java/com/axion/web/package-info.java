/**
 * OryxOS Web — 核心能力五：Web Service.
 *
 * <p>REST API 对外门面，包含：
 *
 * <ul>
 *   <li>{@code WebServer} — Spring MVC + Virtual Thread 服务器
 *   <li>6 个 ApiController：Session / Agent / Profile / Memory / Tool / System
 *   <li>{@code GlobalExceptionHandler} — 统一异常处理
 *   <li>OpenAPI 文档（springdoc-openapi）
 * </ul>
 *
 * <p>核心阶段 10 个端点，统一前缀 /api/v1。
 */
package com.oryxos.web;
