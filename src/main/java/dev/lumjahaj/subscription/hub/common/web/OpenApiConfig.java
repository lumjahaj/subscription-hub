package dev.lumjahaj.subscription.hub.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI baseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Subscription Hub API")
                        .version("v0.1")
                        .description("Multi-tenant billing & subscriptions (portfolio project)")
                        .license(new License().name("MIT"))
                )
                .servers(List.of(new Server().url("http://localhost:8080")));
    }

    @Bean
    GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/api/**", "/actuator/health", "/ping")
                .pathsToExclude("/actuator/**")
                .build();
    }

    /**
     * Add a reusable RFC7807 Problem schema and attach common error responses
     * (application/problem+json) to every operation.
     */
    @Bean
    OpenApiCustomizer problemJsonCustomizer() {
        return openApi -> {
            // Reusable Problem schema
            Schema<?> problem = new ObjectSchema()
                    .addProperty("type", new StringSchema().example("about:blank"))
                    .addProperty("title", new StringSchema().example("Bad Request"))
                    .addProperty("status", new IntegerSchema().example(400))
                    .addProperty("detail", new StringSchema().example("Validation failed"))
                    .addProperty("code", new StringSchema().example("VALIDATION_ERROR"))
                    .addProperty("requestId", new StringSchema().example("8b7c6b2d-..."));

            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSchemas("Problem", problem);

            // Build one Content instance we can reuse
            Content problemContent = new Content()
                    .addMediaType("application/problem+json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem")));

            ApiResponse r400 = new ApiResponse().description("Bad Request").content(problemContent);
            ApiResponse r401 = new ApiResponse().description("Unauthorized").content(problemContent);
            ApiResponse r404 = new ApiResponse().description("Not Found").content(problemContent);
            ApiResponse r409 = new ApiResponse().description("Conflict").content(problemContent);
            ApiResponse r500 = new ApiResponse().description("Internal Server Error").content(problemContent);

            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(op -> {
                        var responses = op.getResponses();
                        responses.putIfAbsent("400", r400);
                        responses.putIfAbsent("401", r401);
                        responses.putIfAbsent("404", r404);
                        responses.putIfAbsent("409", r409);
                        responses.putIfAbsent("500", r500);
                    })
            );
        };
    }
}
