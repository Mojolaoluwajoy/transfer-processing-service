package com.transferservice.transfer_processing_service.worker;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class NoOpProcessingCheckpoints implements ProcessingCheckpoints {

	@Override
	public void reached(Checkpoint checkpoint, UUID transferId) {
	}

}
