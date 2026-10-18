package com.transferservice.transfer_processing_service.worker;

import java.util.Map;
import java.util.UUID;

import com.transferservice.transfer_processing_service.messaging.MessagingTopology;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes "process this transfer" messages. The message only names the transfer; the
 * database decides what to do, so a duplicate or redelivered message does no harm.
 * Acknowledged after processing returns: if the worker dies first, RabbitMQ redelivers.
 */
@Component
class TransferMessageListener {

	private final TransferProcessor processor;

	private final JsonMapper jsonMapper;

	TransferMessageListener(TransferProcessor processor, JsonMapper jsonMapper) {
		this.processor = processor;
		this.jsonMapper = jsonMapper;
	}

	@RabbitListener(queues = MessagingTopology.PROCESS_QUEUE, concurrency = "${worker.concurrency}")
	void onMessage(Message message) {
		Map<?, ?> payload = this.jsonMapper.readValue(message.getBody(), Map.class);
		this.processor.process(UUID.fromString(String.valueOf(payload.get("transferId"))));
	}

}
