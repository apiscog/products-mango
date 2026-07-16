package com.mango.products.adapter.in.web.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenApiCustomizer productPriceDateParameterCustomizer() {
        return openApi -> {
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
}
