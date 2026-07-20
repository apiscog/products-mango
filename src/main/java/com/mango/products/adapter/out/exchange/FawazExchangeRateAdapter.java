package com.mango.products.adapter.out.exchange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.application.model.ExchangeRate;
import com.mango.products.application.port.out.ExchangeRateProvider;
import com.mango.products.domain.model.CurrencyCode;

@Component
public class FawazExchangeRateAdapter implements ExchangeRateProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger(FawazExchangeRateAdapter.class);

	private final RestClient restClient;
	private final ExchangeRateProperties properties;

	public FawazExchangeRateAdapter(RestClient exchangeRateRestClient, ExchangeRateProperties properties) {
		this.restClient = exchangeRateRestClient;
		this.properties = properties;
	}

	@Override
	public ExchangeRate getRate(CurrencyCode source, CurrencyCode target, LocalDate date) {
		try {
			return fetch("primary", properties.getPrimaryUrl(), source, target, date);
		}
		catch (ProviderCallException primaryFailure) {
			if (!primaryFailure.retryable()) {
				throw new ExchangeRateUnavailableException(primaryFailure);
			}
			LOGGER.warn("Primary exchange-rate provider failed; trying fallback");
			try {
				return fetch("fallback", properties.getFallbackUrl(), source, target, date);
			}
			catch (ProviderCallException fallbackFailure) {
				LOGGER.warn("Fallback exchange-rate provider failed");
				throw new ExchangeRateUnavailableException(fallbackFailure);
			}
		}
	}

	private ExchangeRate fetch(
			String provider,
			String template,
			CurrencyCode source,
			CurrencyCode target,
			LocalDate requestedDate) {
		String url = template
				.replace("{date}", requestedDate.toString())
				.replace("{base}", source.lowerCase());
		try {
			JsonNode body = restClient.get()
					.uri(url)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(JsonNode.class);
			return parse(body, source, target, requestedDate);
		}
		catch (HttpClientErrorException exception) {
			throw new ProviderCallException(provider, exception.getStatusCode().value() == 404, exception);
		}
		catch (HttpServerErrorException | ResourceAccessException exception) {
			throw new ProviderCallException(provider, true, exception);
		}
		catch (RestClientException exception) {
			throw new ProviderCallException(provider, true, exception);
		}
		catch (InvalidProviderResponseException exception) {
			throw new ProviderCallException(provider, true, exception);
		}
	}

	private static ExchangeRate parse(
			JsonNode body,
			CurrencyCode source,
			CurrencyCode target,
			LocalDate requestedDate) {
		if (body == null || !body.isObject()) {
			throw new InvalidProviderResponseException();
		}
		LocalDate responseDate;
		try {
			JsonNode dateNode = body.get("date");
			responseDate = dateNode == null ? null : LocalDate.parse(dateNode.asText());
		}
		catch (DateTimeParseException exception) {
			throw new InvalidProviderResponseException(exception);
		}
		if (!requestedDate.equals(responseDate)) {
			throw new InvalidProviderResponseException();
		}
		JsonNode baseNode = body.get(source.lowerCase());
		JsonNode rateNode = baseNode == null ? null : baseNode.get(target.lowerCase());
		if (rateNode == null || !rateNode.isNumber()) {
			throw new InvalidProviderResponseException();
		}
		BigDecimal rate = rateNode.decimalValue();
		if (rate.compareTo(BigDecimal.ZERO) <= 0) {
			throw new InvalidProviderResponseException();
		}
		return new ExchangeRate(source, target, rate, responseDate);
	}

	private static final class ProviderCallException extends RuntimeException {
		private final boolean retryable;

		private ProviderCallException(String provider, boolean retryable, Throwable cause) {
			super(provider + " exchange-rate provider failed", cause);
			this.retryable = retryable;
		}

		private boolean retryable() {
			return retryable;
		}
	}

	private static final class InvalidProviderResponseException extends RuntimeException {
		private InvalidProviderResponseException() {
		}

		private InvalidProviderResponseException(Throwable cause) {
			super(cause);
		}
	}
}
