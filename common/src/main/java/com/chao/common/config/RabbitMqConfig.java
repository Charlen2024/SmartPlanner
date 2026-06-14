package com.chao.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
    public static final String GOAL_AI_DLX = "goal.dlx.exchange";
    public static final String GOAL_AI_DLQ = "goal.ai.dlq";
    public static final String GOAL_AI_DLQ_ROUTING_KEY = "goal.ai.dlq.route";

    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_QUEUE = "user.notification.queue";
    public static final String NOTIFICATION_ROUTING_KEY = "notification.route";

    public static final String RESOURCE_EXCHANGE = "resource.exchange";
    public static final String RESOURCE_ADVICE_QUEUE = "resource.advice.queue";
    public static final String RESOURCE_ADVICE_ROUTING_KEY = "resource.advice.route";

    public static final String AGENT_JOURNAL_INDEX_QUEUE = "agent.journal.index.queue";
    public static final String AGENT_JOURNAL_INDEX_ROUTING_KEY = "agent.journal.index.route";

    @Bean
    public DirectExchange goalExchange() {
        return new DirectExchange(GOAL_EXCHANGE);
    }

    @Bean
    public Queue goalAiQueue() {
        return QueueBuilder.durable(GOAL_AI_QUEUE)
                .deadLetterExchange(GOAL_AI_DLX)
                .deadLetterRoutingKey(GOAL_AI_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding goalAiBinding(Queue goalAiQueue, DirectExchange goalExchange) {
        return BindingBuilder.bind(goalAiQueue).to(goalExchange).with(GOAL_AI_ROUTING_KEY);
    }

    @Bean
    public DirectExchange goalDlxExchange() {
        return new DirectExchange(GOAL_AI_DLX);
    }

    @Bean
    public Queue goalAiDlq() {
        return new Queue(GOAL_AI_DLQ, true);
    }

    @Bean
    public Binding goalAiDlqBinding(Queue goalAiDlq, DirectExchange goalDlxExchange) {
        return BindingBuilder.bind(goalAiDlq).to(goalDlxExchange).with(GOAL_AI_DLQ_ROUTING_KEY);
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

    @Bean
    public DirectExchange resourceExchange() {
        return new DirectExchange(RESOURCE_EXCHANGE);
    }

    @Bean
    public Queue resourceAdviceQueue() {
        return new Queue(RESOURCE_ADVICE_QUEUE, true);
    }

    @Bean
    public Binding resourceAdviceBinding(Queue resourceAdviceQueue, DirectExchange resourceExchange) {
        return BindingBuilder.bind(resourceAdviceQueue).to(resourceExchange).with(RESOURCE_ADVICE_ROUTING_KEY);
    }

    @Bean
    public Queue agentJournalIndexQueue() {
        return new Queue(AGENT_JOURNAL_INDEX_QUEUE, true);
    }

    @Bean
    public Binding agentJournalIndexBinding(Queue agentJournalIndexQueue, DirectExchange notificationExchange) {
        return BindingBuilder.bind(agentJournalIndexQueue).to(notificationExchange).with(AGENT_JOURNAL_INDEX_ROUTING_KEY);
    }
}
