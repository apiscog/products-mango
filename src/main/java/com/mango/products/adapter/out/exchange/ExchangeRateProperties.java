package com.mango.products.adapter.out.exchange;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "exchange-rate")
public class ExchangeRateProperties {

	private String primaryUrl;
	private String fallbackUrl;
	private Duration connectTimeout = Duration.ofSeconds(2);
	private Duration readTimeout = Duration.ofSeconds(3);

	public String getPrimaryUrl() {
		return primaryUrl;
	}

	public void setPrimaryUrl(String primaryUrl) {
		this.primaryUrl = validateTemplate(primaryUrl, "primary-url");
	}

	public String getFallbackUrl() {
		return fallbackUrl;
	}

	public void setFallbackUrl(String fallbackUrl) {
		this.fallbackUrl = validateTemplate(fallbackUrl, "fallback-url");
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = validateTimeout(connectTimeout, "connect-timeout");
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = validateTimeout(readTimeout, "read-timeout");
	}

	private static String validateTemplate(String value, String property) {
		if (value == null || value.isBlank() || !value.contains("{date}") || !value.contains("{base}")) {
			throw new IllegalArgumentException(
					"exchange-rate." + property + " must contain {date} and {base}");
		}
		return value;
	}

	private static Duration validateTimeout(Duration value, String property) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("exchange-rate." + property + " must be positive");
		}
		return value;
	}
}
