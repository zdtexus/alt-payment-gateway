package com.example.api_gateway.ratelimit;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.stereotype.Component;

@Component
class RateLimitBackendLogger {

	private static final Logger log = LoggerFactory.getLogger(RateLimitBackendLogger.class);

	RateLimitBackendLogger(DataRedisConnectionDetails connectionDetails, DataRedisProperties properties) {
		log.info(describe(connectionDetails, properties.getSsl().isEnabled()));
	}

	static String describe(DataRedisConnectionDetails details, boolean sslEnabled) {
		boolean tls = sslEnabled || details.getSslBundle() != null;
		return "Rate limits stored in " + endpoint(details) + " (TLS " + (tls ? "on" : "off") + ")";
	}

	private static String endpoint(DataRedisConnectionDetails details) {
		if (details.getSentinel() != null) {
			return "Redis Sentinel master " + details.getSentinel().getMaster() + " via "
					+ nodes(details.getSentinel().getNodes());
		}
		if (details.getCluster() != null) {
			return "Redis Cluster at " + nodes(details.getCluster().getNodes());
		}
		if (details.getMasterReplica() != null) {
			return "Redis master/replica at " + nodes(details.getMasterReplica().getNodes());
		}
		DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
		return "Redis at " + address(standalone.getHost(), standalone.getPort());
	}

	private static String nodes(List<DataRedisConnectionDetails.Node> nodes) {
		return nodes.stream().map((node) -> address(node.host(), node.port())).collect(Collectors.joining(", "));
	}

	private static String address(String host, int port) {
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
	}

}
