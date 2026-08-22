package com.gighub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import javax.servlet.http.HttpServletRequest;

import com.gighub.common.api.PageRequests;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import springfox.documentation.oas.web.OpenApiTransformationContext;
import springfox.documentation.spi.DocumentationType;

class SwaggerPageParameterContractFilterTest {

    private final SwaggerPageParameterContractFilter filter =
            new SwaggerPageParameterContractFilter();

    @Test
    void addsApprovedDefaultsAndBoundsToPageQueryParameters() {
        Parameter page = queryParameter("page");
        Parameter size = queryParameter("size");
        Parameter keyword = queryParameter("keyword");
        Operation operation = new Operation()
                .addParametersItem(page)
                .addParametersItem(size)
                .addParametersItem(keyword);
        OpenAPI openApi = new OpenAPI().paths(
                new Paths().addPathItem("/api/items", new PathItem().get(operation)));

        assertSame(openApi, filter.transform(context(openApi)));

        assertEquals(PageRequests.DEFAULT_PAGE, page.getSchema().getDefault());
        assertEquals(BigDecimal.ZERO, page.getSchema().getMinimum());
        assertNull(page.getSchema().getMaximum());
        assertEquals(PageRequests.DEFAULT_SIZE, size.getSchema().getDefault());
        assertEquals(BigDecimal.ONE, size.getSchema().getMinimum());
        assertEquals(BigDecimal.valueOf(PageRequests.MAX_SIZE), size.getSchema().getMaximum());
        assertNull(keyword.getSchema().getDefault());
        assertNull(keyword.getSchema().getMinimum());
        assertNull(keyword.getSchema().getMaximum());
    }

    @Test
    void supportsOnlyOpenApi30AndHandlesMissingPaths() {
        OpenAPI openApi = new OpenAPI();

        assertSame(openApi, filter.transform(context(openApi)));
        assertTrue(filter.supports(DocumentationType.OAS_30));
        assertFalse(filter.supports(DocumentationType.SWAGGER_2));
    }

    private Parameter queryParameter(String name) {
        return new Parameter().name(name).in("query").schema(new IntegerSchema());
    }

    @SuppressWarnings("unchecked")
    private OpenApiTransformationContext<HttpServletRequest> context(OpenAPI openApi) {
        OpenApiTransformationContext<HttpServletRequest> context =
                mock(OpenApiTransformationContext.class);
        when(context.getSpecification()).thenReturn(openApi);
        return context;
    }
}
