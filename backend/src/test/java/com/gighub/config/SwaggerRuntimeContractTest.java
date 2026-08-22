package com.gighub.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

class SwaggerRuntimeContractTest {

    @Test
    void everyVoidResponseEntityDocumentsNoContentInRuntimeSwagger() throws ClassNotFoundException {
        List<String> missingOperations = new ArrayList<>();
        for (Class<?> controller : productionControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (returnsVoidResponseEntity(method) && !documentsNoContent(method)) {
                    missingOperations.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertTrue(missingOperations.isEmpty(),
                "ResponseEntity<Void> API는 Runtime Swagger에 204를 명시해야 합니다: "
                        + missingOperations);
    }

    private List<Class<?>> productionControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.gighub")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            if (isProductionClass(controller)) {
                controllers.add(controller);
            }
        }
        return controllers;
    }

    private boolean isProductionClass(Class<?> type) {
        String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
        return location.replace('\\', '/').endsWith("/classes/java/main/");
    }

    private boolean returnsVoidResponseEntity(Method method) {
        Type returnType = method.getGenericReturnType();
        if (!(returnType instanceof ParameterizedType)) {
            return false;
        }

        ParameterizedType parameterizedType = (ParameterizedType) returnType;
        Type[] arguments = parameterizedType.getActualTypeArguments();
        return parameterizedType.getRawType().equals(ResponseEntity.class)
                && arguments.length == 1
                && arguments[0].equals(Void.class);
    }

    private boolean documentsNoContent(Method method) {
        ApiResponses responses = method.getAnnotation(ApiResponses.class);
        if (responses == null) {
            return false;
        }

        for (io.swagger.v3.oas.annotations.responses.ApiResponse response : responses.value()) {
            if ("204".equals(response.responseCode())) {
                return true;
            }
        }
        return false;
    }
}
