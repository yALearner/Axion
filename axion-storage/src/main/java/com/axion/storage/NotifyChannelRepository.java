package com.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** notify_channels 仓储——按注册名主键查；CRUD 端点由 Web Service 节直接消费本接口（004-notify 交付）。 */
public interface NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String> {}
