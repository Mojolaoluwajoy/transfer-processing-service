package com.transferservice.transfer_processing_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferProcessingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
