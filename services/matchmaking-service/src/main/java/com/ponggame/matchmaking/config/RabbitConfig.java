package com.ponggame.matchmaking.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${app.rabbitmq.events-exchange:pong.events}")
    private String eventsExchange;

    @Value("${app.rabbitmq.dlx-exchange:pong.dlx}")
    private String dlxExchange;

    @Value("${app.rabbitmq.match-ready-queue:matchmaking.match.ready}")
    private String matchReadyQueueName;

    @Value("${app.rabbitmq.match-failed-queue:matchmaking.match.failed}")
    private String matchFailedQueueName;

    @Value("${app.rabbitmq.match-finished-queue:matchmaking.match.finished}")
    private String matchFinishedQueueName;

    @Bean
    public TopicExchange pongEventsExchange() {
        return new TopicExchange(eventsExchange, true, false);
    }

    @Bean
    public DirectExchange pongDlxExchange() {
        return new DirectExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue matchReadyQueue() {
        return QueueBuilder.durable(matchReadyQueueName)
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .withArgument("x-dead-letter-routing-key", matchReadyQueueName + ".dlq")
                .build();
    }

    @Bean
    public Queue matchReadyDlq() {
        return QueueBuilder.durable(matchReadyQueueName + ".dlq").build();
    }

    @Bean
    public Binding matchReadyBinding(Queue matchReadyQueue, TopicExchange pongEventsExchange) {
        return BindingBuilder.bind(matchReadyQueue).to(pongEventsExchange).with("match.ready");
    }

    @Bean
    public Binding matchReadyDlqBinding(Queue matchReadyDlq, DirectExchange pongDlxExchange) {
        return BindingBuilder.bind(matchReadyDlq).to(pongDlxExchange).with(matchReadyQueueName + ".dlq");
    }

    @Bean
    public Queue matchFailedQueue() {
        return QueueBuilder.durable(matchFailedQueueName)
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .withArgument("x-dead-letter-routing-key", matchFailedQueueName + ".dlq")
                .build();
    }

    @Bean
    public Queue matchFailedDlq() {
        return QueueBuilder.durable(matchFailedQueueName + ".dlq").build();
    }

    @Bean
    public Binding matchFailedBinding(Queue matchFailedQueue, TopicExchange pongEventsExchange) {
        return BindingBuilder.bind(matchFailedQueue).to(pongEventsExchange).with("match.provisioning_failed");
    }

    @Bean
    public Binding matchFailedDlqBinding(Queue matchFailedDlq, DirectExchange pongDlxExchange) {
        return BindingBuilder.bind(matchFailedDlq).to(pongDlxExchange).with(matchFailedQueueName + ".dlq");
    }

    @Bean
    public Queue matchFinishedQueue() {
        return QueueBuilder.durable(matchFinishedQueueName)
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .withArgument("x-dead-letter-routing-key", matchFinishedQueueName + ".dlq")
                .build();
    }

    @Bean
    public Queue matchFinishedDlq() {
        return QueueBuilder.durable(matchFinishedQueueName + ".dlq").build();
    }

    @Bean
    public Binding matchFinishedBinding(Queue matchFinishedQueue, TopicExchange pongEventsExchange) {
        return BindingBuilder.bind(matchFinishedQueue).to(pongEventsExchange).with("match.finished");
    }

    @Bean
    public Binding matchFinishedDlqBinding(Queue matchFinishedDlq, DirectExchange pongDlxExchange) {
        return BindingBuilder.bind(matchFinishedDlq).to(pongDlxExchange).with(matchFinishedQueueName + ".dlq");
    }
}
