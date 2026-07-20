package com.mango.products.adapter.out.exchange;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ExchangeRateHttpConfiguration {

	@Bean
	RestClient exchangeRateRestClient(ExchangeRateProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
		requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
		return RestClient.builder()
				.requestFactory(requestFactory)
				.defaultHeader("User-Agent", "Products-API/1.0")
				.build();
	}
}
