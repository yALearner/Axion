package com.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 坑九回归（003-cli，架构断言）：scanBasePackages 只作用于组件扫描，不作用于 JPA 扫描——启动类必须显式声明
 * {@code @EnableJpaRepositories}/{@code @EntityScan} 的 basePackages 指向 com.oryxos.storage，否则重命令启动 时
 * "Found 0 JPA repository interfaces"、审计写不进去直接报错（课件第 18 节 §二第四点；002 人工验收实机踩过）。 落 oryxos-boot：断言对象是
 * boot 主类，cli 测试引用 boot 会构成编译期反向循环。
 */
class JpaScanConfigurationTest {

  @Test
  @DisplayName("坑九回归：启动类显式声明 JPA 仓储与实体扫描根")
  void jpaScanRootsExplicitlyDeclaredOnApplicationClass() {
    EnableJpaRepositories jpa = OryxOsApplication.class.getAnnotation(EnableJpaRepositories.class);
    EntityScan entity = OryxOsApplication.class.getAnnotation(EntityScan.class);

    assertThat(jpa)
        .withFailMessage("启动类必须显式声明 @EnableJpaRepositories（坑九：scanBasePackages 不管 JPA 扫描）")
        .isNotNull();
    assertThat(Arrays.asList(jpa.basePackages()))
        .withFailMessage("@EnableJpaRepositories.basePackages 必须包含 com.oryxos.storage")
        .contains("com.oryxos.storage");
    assertThat(entity).withFailMessage("启动类必须显式声明 @EntityScan（坑九）").isNotNull();
    assertThat(Arrays.asList(entity.basePackages()))
        .withFailMessage("@EntityScan.basePackages 必须包含 com.oryxos.storage")
        .contains("com.oryxos.storage");
  }
}
