package com.chao.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    public static final String GOAL_EXCHANGE = "goal.exchange";
    public static final String GOAL_AI_QUEUE = "goal.ai.queue";
    public static final String GOAL_AI_ROUTING_KEY = "goal.ai.route";

    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_QUEUE = "user.notification.queue";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.route";

    @Bean
    public DirectExchange goalExchange() {
        return new DirectExchange(GOAL_EXCHANGE);
    }

    @Bean
    public Queue goalAiQueue() {
        return new Queue(GOAL_AI_QUEUE, true);
    }

    @Bean
    public Binding goalAiBinding(Queue goalAiQueue, DirectExchange goalExchange) {
        return BindingBuilder.bind(goalAiQueue).to(goalExchange).with(GOAL_AI_ROUTING_KEY);
    }

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE);
    }

    @Bean
    public Queue notificationQueue() {
        return new Queue(NOTIFICATION_QUEUE, true);
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(NOTIFICATION_ROUTING_KEY);
    }
}
