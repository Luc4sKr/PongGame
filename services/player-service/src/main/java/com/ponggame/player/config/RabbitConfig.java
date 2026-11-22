package com.ponggame.player.config;

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

    @Value("${app.rabbitmq.match-finished-queue:player.match.finished}")
    private String matchFinishedQueue;

    @Value("${app.rabbitmq.match-finished-dlq:player.match.finished.dlq}")
    private String matchFinishedDlq;

    @Value("${app.rabbitmq.match-finished-routing-key:match.finished}")
    private String matchFinishedRoutingKey;

    @Bean
    public TopicExchange pongEventsExchange() {
        return new TopicExchange(eventsExchange, true, false);
    }

    @Bean
    public DirectExchange pongDlxExchange() {
        return new DirectExchange(dlxExchange, true, false);
    }

    @Bean
    public Queue matchFinishedQueue() {
        return QueueBuilder.durable(matchFinishedQueue)
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .withArgument("x-dead-letter-routing-key", matchFinishedDlq)
                .build();
    }

    @Bean
    public Queue matchFinishedDlq() {
        return QueueBuilder.durable(matchFinishedDlq).build();
    }

    @Bean
    public Binding matchFinishedBinding(Queue matchFinishedQueue, TopicExchange pongEventsExchange) {
        return BindingBuilder.bind(matchFinishedQueue).to(pongEventsExchange).with(matchFinishedRoutingKey);
    }

    @Bean
    public Binding matchFinishedDlqBinding(Queue matchFinishedDlq, DirectExchange pongDlxExchange) {
        return BindingBuilder.bind(matchFinishedDlq).to(pongDlxExchange).with(this.matchFinishedDlq);
    }
}
