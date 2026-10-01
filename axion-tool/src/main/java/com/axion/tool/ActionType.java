package com.oryxos.tool;

/** 沙箱校验的四种涉外动作类型——文件读/文件写/Shell 命令/HTTP 请求（读写分开便于未来按读/写分权限）。 */
public enum ActionType {
  FILE_READ,
  FILE_WRITE,
  SHELL_COMMAND,
  HTTP_REQUEST
}
