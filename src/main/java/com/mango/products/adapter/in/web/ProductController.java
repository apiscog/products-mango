package com.mango.products.adapter.in.web;

import com.mango.products.adapter.in.web.dto.request.AddPriceRequest;
import com.mango.products.adapter.in.web.dto.request.CreateProductRequest;
import com.mango.products.adapter.in.web.dto.response.AddPriceResponse;
import com.mango.products.adapter.in.web.dto.response.CreateProductResponse;
import com.mango.products.adapter.in.web.dto.response.CurrentPriceResponse;
import com.mango.products.adapter.in.web.dto.response.ConvertedPriceResponse;
import com.mango.products.adapter.in.web.dto.response.PriceHistoryItemResponse;
import com.mango.products.adapter.in.web.dto.response.ProductHistoryResponse;
import com.mango.products.adapter.in.web.error.ApiErrorResponse;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import com.mango.products.domain.model.CurrencyCode;

@RestController
@RequestMapping("/products")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

    private final ProductUseCases productUseCases;

    public ProductController(ProductUseCases productUseCases) {
        this.productUseCases = productUseCases;
    }

    @Operation(summary = "Create a product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created",
                    content = @Content(schema = @Schema(implementation = CreateProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<CreateProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResult result = productUseCases.createProduct(
                new CreateProductCommand(request.name(), request.description())
        );
        return ResponseEntity.created(URI.create("/products/" + result.id())).body(toResponse(result));
    }

    @Operation(summary = "Add a price period to a product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Price created",
                    content = @Content(schema = @Schema(implementation = AddPriceResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Price period overlaps an existing price",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/prices")
    public ResponseEntity<AddPriceResponse> addPrice(
            @PathVariable @Positive long id,
            @Valid @RequestBody AddPriceRequest request
    ) {
        PriceResult result = productUseCases.addPrice(
                id,
                new AddPriceCommand(
                        request.value(),
                        request.currency() == null ? CurrencyCode.EUR : CurrencyCode.from(request.currency()),
                        request.initDate(),
                        request.endDate())
        );
        return ResponseEntity.status(201).body(toResponse(result));
    }

    @Operation(
            operationId = "getProductPrice",
            summary = "Get a product price or its complete history",
            description = "When date is present, returns the price valid on that ISO date; without date, returns the history."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current price or price history found",
                    content = @Content(schema = @Schema(oneOf = {
                            CurrentPriceResponse.class,
                            ConvertedPriceResponse.class,
                            ProductHistoryResponse.class
                    }))),
            @ApiResponse(responseCode = "400", description = "Invalid identifier or date",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or price not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Historical currency conversion unavailable",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(value = "/{id}/prices", params = "date")
    public Object getPriceAtDate(
            @PathVariable @Positive long id,
            @Parameter(
                    description = "Optional ISO date (yyyy-MM-dd); omit it to retrieve the complete history",
                    required = false
            )
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
            ,
            @Parameter(description = "Optional target currency for historical conversion",
                    schema = @Schema(allowableValues = {"EUR", "USD", "GBP", "JPY", "CHF"}))
            @RequestParam(value = "currency", required = false) String currency
    ) {
        if (currency == null) {
            var result = productUseCases.getPriceAtDate(id, date);
            return new CurrentPriceResponse(result.value(), result.currency());
        }
        return toResponse(productUseCases.getPriceAtDate(id, date, CurrencyCode.from(currency)));
    }

    @Operation(
            operationId = "getProductPrice",
            summary = "Get a product price or its complete history",
            description = "When date is present, returns the price valid on that ISO date; without date, returns the history."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current price or price history found",
                    content = @Content(schema = @Schema(oneOf = {
                            CurrentPriceResponse.class,
                            ProductHistoryResponse.class
                    }))),
            @ApiResponse(responseCode = "400", description = "Invalid identifier or date",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product or price not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(value = "/{id}/prices", params = {"!date", "!currency"})
    public ProductHistoryResponse getPriceHistory(@PathVariable @Positive long id) {
        return toResponse(productUseCases.getPriceHistory(id));
    }

    private static CreateProductResponse toResponse(ProductResult result) {
        return new CreateProductResponse(result.id(), result.name(), result.description());
    }

    private static AddPriceResponse toResponse(PriceResult result) {
        return new AddPriceResponse(result.value(), result.currency(), result.initDate(), result.endDate());
    }

    private static ProductHistoryResponse toResponse(ProductHistoryResult result) {
        return new ProductHistoryResponse(
                result.name(),
                result.description(),
                result.prices().stream().map(ProductController::toHistoryResponse).toList()
        );
    }

    private static PriceHistoryItemResponse toHistoryResponse(PriceResult result) {
        return new PriceHistoryItemResponse(
                result.value(), result.currency(), result.initDate(), result.endDate());
    }

    private static ConvertedPriceResponse toResponse(ConvertedPriceResult result) {
        return new ConvertedPriceResponse(
                result.value(),
                result.currency(),
                result.originalValue(),
                result.originalCurrency(),
                result.exchangeRate(),
                result.exchangeRateDate());
    }
}
