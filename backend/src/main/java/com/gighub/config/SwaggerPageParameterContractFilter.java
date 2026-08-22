package com.gighub.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.gighub.common.api.PageRequests;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import springfox.documentation.oas.web.OpenApiTransformationContext;
import springfox.documentation.oas.web.WebMvcOpenApiTransformationFilter;
import springfox.documentation.spi.DocumentationType;

/** Springfox가 누락하는 공통 Page Query 기본값과 경계를 Runtime OpenAPI에 보완합니다. */
@Order(Ordered.LOWEST_PRECEDENCE)
public class SwaggerPageParameterContractFilter implements WebMvcOpenApiTransformationFilter {

    @Override
    public OpenAPI transform(OpenApiTransformationContext<HttpServletRequest> context) {
        OpenAPI openApi = context.getSpecification();
        if (openApi.getPaths() == null) {
            return openApi;
        }

        openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .flatMap(operation -> parameters(operation).stream())
                .filter(parameter -> "query".equals(parameter.getIn()))
                .forEach(this::applyPageContract);
        return openApi;
    }

    @Override
    public boolean supports(DocumentationType documentationType) {
        return DocumentationType.OAS_30.equals(documentationType);
    }

    private List<Parameter> parameters(Operation operation) {
        return operation.getParameters() == null
                ? Collections.emptyList()
                : operation.getParameters();
    }

    private void applyPageContract(Parameter parameter) {
        Schema<?> schema = parameter.getSchema();
        if (schema == null) {
            return;
        }

        if ("page".equals(parameter.getName())) {
            schema.setDefault(PageRequests.DEFAULT_PAGE);
            schema.setMinimum(BigDecimal.ZERO);
        } else if ("size".equals(parameter.getName())) {
            schema.setDefault(PageRequests.DEFAULT_SIZE);
            schema.setMinimum(BigDecimal.ONE);
            schema.setMaximum(BigDecimal.valueOf(PageRequests.MAX_SIZE));
        }
    }
}
