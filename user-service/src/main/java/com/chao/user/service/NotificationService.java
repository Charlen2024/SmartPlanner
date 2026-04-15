package com.chao.user.service;

import com.chao.common.config.RabbitMqConfig;
import com.chao.common.dto.NotificationMessage;
import com.chao.user.controller.NotificationController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationController notificationController;

    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_QUEUE)
    public void receiveNotification(NotificationMessage message) {
        log.info("MQ收到通知，推给用户 {}: {}", message.getUserId(), message.getContent());
        notificationController.pushNotification(message);
    }
}
