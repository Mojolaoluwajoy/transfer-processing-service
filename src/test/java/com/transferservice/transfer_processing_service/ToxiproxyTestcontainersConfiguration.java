package com.transferservice.transfer_processing_service;

import java.io.IOException;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Like {@link TestcontainersConfiguration}, but the application reaches RabbitMQ through a
 * Toxiproxy proxy that tests can cut ({@code proxy.disable()}) and restore to simulate a
 * real broker outage while RabbitMQ keeps a stable address.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ToxiproxyTestcontainersConfiguration {

	private static final int PROXY_PORT = 8666;

	private final Network network = Network.newNetwork();

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

	@Bean
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine")).withNetwork(this.network)
			.withNetworkAliases("rabbitmq");
	}

	@Bean
	ToxiproxyContainer toxiproxyContainer() {
		return new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
			.withNetwork(this.network);
	}

	@Bean
	Proxy rabbitProxy(ToxiproxyContainer toxiproxy, RabbitMQContainer rabbit) throws IOException {
		rabbit.start();
		toxiproxy.start();
		return new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort()).createProxy("rabbitmq",
				"0.0.0.0:" + PROXY_PORT, "rabbitmq:5672");
	}

	@Bean
	DynamicPropertyRegistrar rabbitViaToxiproxy(ToxiproxyContainer toxiproxy, RabbitMQContainer rabbit,
			Proxy rabbitProxy) {
		return (registry) -> {
			registry.add("spring.rabbitmq.host", toxiproxy::getHost);
			registry.add("spring.rabbitmq.port", () -> toxiproxy.getMappedPort(PROXY_PORT));
			registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
			registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
		};
	}

}
