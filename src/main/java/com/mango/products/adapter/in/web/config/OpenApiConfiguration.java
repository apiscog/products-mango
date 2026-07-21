package com.mango.products.adapter.in.web.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "RS256 JWT. Use products.read for GET and products.write for POST."
)
public class OpenApiConfiguration {

    @Bean
    OpenApiCustomizer productPriceDateParameterCustomizer() {
        return openApi -> {
            openApi.getPaths().forEach((path, pathItem) -> {
                if (!path.startsWith("/products")) {
                    return;
                }
                pathItem.readOperations().forEach(operation -> {
                    operation.getResponses().addApiResponse(
                            "401", securityResponse("Authentication is required or the token is invalid"));
                    operation.getResponses().addApiResponse(
                            "403", securityResponse("The token does not grant the required scope"));
                });
            });

            PathItem pricePath = openApi.getPaths().get("/products/{id}/prices");
            Operation getOperation = pricePath == null ? null : pricePath.getGet();
            List<Parameter> parameters = getOperation == null ? null : getOperation.getParameters();
            if (parameters == null) {
                return;
            }
            parameters.stream()
                    .filter(parameter -> "date".equals(parameter.getName()))
                    .forEach(parameter -> parameter.setRequired(false));
        };
    }

    private static ApiResponse securityResponse(String description) {
        Schema<?> errorSchema = new Schema<>().$ref("#/components/schemas/ApiErrorResponse");
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(errorSchema)
                ));
    }
}
