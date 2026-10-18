package com.transferservice.transfer_processing_service;

import org.springframework.boot.SpringApplication;

public class TestTransferProcessingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransferProcessingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
