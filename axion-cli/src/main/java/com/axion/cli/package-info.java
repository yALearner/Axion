/**
 * OryxOS CLI — 命令行入口.
 *
 * <p>Picocli 主入口，注册 12 个子命令：
 *
 * <ul>
 *   <li>启动和状态：init / status / chat / serve / gateway
 *   <li>Agent 管理：profile list / create / show / delete
 *   <li>查询：provider list / tool list / session list
 * </ul>
 *
 * <p>包含 {@code ConfigLoader} — 统一配置与密钥加载。
 */
package com.oryxos.cli;
