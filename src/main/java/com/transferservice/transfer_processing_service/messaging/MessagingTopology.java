package com.transferservice.transfer_processing_service.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology. Declared by Spring's RabbitAdmin on every (re)connection, so a broker
 * that comes back empty is set up again automatically.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingTopology {

	public static final String EXCHANGE = "transfers";

	public static final String PROCESS_QUEUE = "transfers.process";

	public static final String PROCESS_ROUTING_KEY = "transfer.process";

	public static final String DEAD_LETTER_EXCHANGE = "transfers.dlx";

	public static final String DEAD_LETTER_QUEUE = "transfers.process.dlq";

	@Bean
	DirectExchange transfersExchange() {
		return new DirectExchange(EXCHANGE, true, false);
	}

	@Bean
	DirectExchange transfersDeadLetterExchange() {
		return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue processQueue() {
		return QueueBuilder.durable(PROCESS_QUEUE)
			.deadLetterExchange(DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(PROCESS_ROUTING_KEY)
			.build();
	}

	@Bean
	Queue deadLetterQueue() {
		return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Binding processBinding() {
		return BindingBuilder.bind(processQueue()).to(transfersExchange()).with(PROCESS_ROUTING_KEY);
	}

	@Bean
	Binding deadLetterBinding() {
		return BindingBuilder.bind(deadLetterQueue()).to(transfersDeadLetterExchange()).with(PROCESS_ROUTING_KEY);
	}

}
