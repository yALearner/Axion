package com.oryxos.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器（工程地基，由 java-spring-init skill 生成；009-web-service 扩展）。
 *
 * <p>所有异常统一转换为标准 JSON 错误体 {@link ErrorResponse} （errorCode / message / timestamp），覆盖 400 / 404 /
 * 500 / 503 / 504。业务 Controller 无需自行 try-catch。
 *
 * <p>009-web-service 扩展（双信封拍板 B：错误只经本类单出口产出 ErrorResponse）：⑨a 503 只映射「依赖不可用」语义类 （{@link
 * ProviderUnavailableException} + 地基 {@link ServiceUnavailableException}）——IllegalStateException 归
 * 500 兜底 （001/006/008 大量业务校验用 IllegalStateException，全域 503 会语义污染）；504 映射 {@link
 * AgentTimeoutException}（60s 上限）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 参数校验失败（@Valid / @Validated）。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .orElse("请求参数非法");
    return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
  }

  /** 009：请求参数非法（消息空/超 32KB 防呆）。 */
  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage());
  }

  /** 009：会话不存在 / 通用资源不存在。 */
  @ExceptionHandler({SessionNotFoundException.class, ResourceNotFoundException.class})
  public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage());
  }

  /** 009：Agent 调用超时（60 秒上限，⑨c 任务体继续后台跑完）。 */
  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ErrorResponse> handleTimeout(AgentTimeoutException ex) {
    return build(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.GATEWAY_TIMEOUT, ex.getMessage());
  }

  /** 请求体不可读（JSON 解析失败等）。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "请求体格式错误");
  }

  /** 静态资源未找到（Spring 6.2+）。 */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "资源不存在: " + ex.getResourcePath());
  }

  /** 无匹配 Handler（保留兜底，覆盖 404 语义）。 */
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "接口不存在");
  }

  /**
   * 服务不可用（⑨a：只映射「依赖不可用」语义类——Provider 故障 + 地基 ServiceUnavailable；IllegalStateException 归 500 兜底）。
   */
  @ExceptionHandler({ProviderUnavailableException.class, ServiceUnavailableException.class})
  public ResponseEntity<ErrorResponse> handleUnavailable(RuntimeException ex) {
    return build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, ex.getMessage());
  }

  /** 兜底：未预期异常。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "服务器内部错误");
  }

  private ResponseEntity<ErrorResponse> build(HttpStatus status, ErrorCode code, String message) {
    return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
  }
}
