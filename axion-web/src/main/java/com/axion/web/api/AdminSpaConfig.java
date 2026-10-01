package com.oryxos.web.api;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 管理台 SPA 回落（009-web-service FR-6）：{@code /admin/**} 静态资源从 {@code classpath:/static/admin/} 提供；
 * 未命中路径回落 {@code admin/index.html}——刷新子路由不 404（{@code GET /api/v1/**} 不受影响，路由优先级天然分开）。
 */
@Configuration
public class AdminSpaConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // 根路径 forward 到 index.html——URL 保持不变（302 会把 URL 改成 /admin/index.html，
    // 与 vue-router 的 history base '/admin/' 不兼容导致路由无匹配空白页）
    registry.addViewController("/admin").setViewName("forward:/admin/index.html");
    registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations("classpath:/static/admin/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                Resource requested;
                try {
                  requested = location.createRelative(resourcePath);
                } catch (IOException e) {
                  // /admin/ 根路径等空路径 createRelative 会抛异常——直接回落 index.html
                  return new ClassPathResource("/static/admin/index.html");
                }
                // 目录判断用 URL 尾斜杠（isFile() 对嵌套 jar 内资源恒 false——文件系统与 jar 内都成立的判据）
                if (requested.exists()
                    && requested.isReadable()
                    && !requested.getURL().toString().endsWith("/")) {
                  return requested;
                }
                return new ClassPathResource("/static/admin/index.html"); // SPA 回落
              }
            });
  }
}
