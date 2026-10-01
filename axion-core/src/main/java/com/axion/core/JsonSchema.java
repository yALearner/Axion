package com.oryxos.core;

import java.util.Map;

/**
 * {@link OryxTool} 的参数说明（JSON Schema）。
 *
 * <p>core 保持框架无关：本类型只承载 schema 对象本身，向 Spring AI 工具格式的翻译 由 oryxos-provider 的适配层完成。
 *
 * @param value JSON Schema 对象（name/type/properties/required 等字段的映射）
 */
public record JsonSchema(Map<String, Object> value) {

  /** 防御性拷贝：不暴露可变映射的内部表示。 */
  public JsonSchema {
    value = Map.copyOf(value);
  }

  @Override
  public Map<String, Object> value() {
    return Map.copyOf(value);
  }
}
