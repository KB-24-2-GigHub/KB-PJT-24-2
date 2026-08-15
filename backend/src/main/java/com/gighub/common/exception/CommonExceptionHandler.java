package com.gighub.common.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.api.ApiErrorResponse;
import com.gighub.common.api.ApiFieldError;
import com.gighub.common.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class CommonExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);
    private static final String VALIDATION_MESSAGE = "입력값을 확인해 주세요.";
    private static final String INTERNAL_ERROR_MESSAGE =
            "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    /** 구체적인 도메인 타입 대신 승인된 공통 예외 계약 하나만 처리합니다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException exception,
            HttpServletRequest request) {
        return body(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getFieldErrors(),
                request
        );
    }

    @ExceptionHandler({
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMissingRequestValue(
            Exception exception,
            HttpServletRequest request) {
        return body(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "필수 요청 값이 누락되었습니다.",
                null,
                request
        );
    }

    private static final String TYPE_MISMATCH_CODE = "typeMismatch";
    private static final String TYPE_MISMATCH_MESSAGE = "요청 파라미터 형식이 올바르지 않습니다.";

    /**
     * Bean Validation 필드 오류만 선택 필드로 포함하고 Spring 내부 객체는 노출하지 않습니다.
     *
     * <p>{@code @ModelAttribute} 바인딩이 원시 타입 변환에 실패하면 Spring이 자동으로
     * 채우는 {@code defaultMessage}에는 Java 예외 클래스명과 원인 메시지가 그대로 담깁니다.
     * 그 값을 응답에 그대로 내보내지 않고 {@link MethodArgumentTypeMismatchException}과 같은
     * 안전한 문구로 치환합니다.</p>
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            BindException exception,
            HttpServletRequest request) {
        List<ApiFieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiFieldError(
                        error.getField(),
                        TYPE_MISMATCH_CODE.equals(error.getCode())
                                ? TYPE_MISMATCH_MESSAGE
                                : String.valueOf(error.getDefaultMessage())
                ))
                .toList();

        return body(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE,
                fieldErrors,
                request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return body(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "요청 파라미터 형식이 올바르지 않습니다.",
                null,
                request
        );
    }

    /** Container Multipart 상한 초과는 각 도메인 파일 검증기와 같은 형태의 오류로 맞춘다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return body(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "업로드 파일 크기가 허용 범위를 벗어났습니다.",
                List.of(new ApiFieldError("file", "SIZE_EXCEEDED")),
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return body(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                "요청 본문 형식이 올바르지 않습니다.",
                null,
                request
        );
    }

    /** 예상 밖의 오류는 내부 원인을 숨기고 응답과 서버 로그를 같은 traceId로 연결합니다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        String traceId = TraceIds.getOrCreate(request);
        log.error("처리되지 않은 서버 오류 traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiErrorResponse(
                        ApiErrorCode.INTERNAL_ERROR,
                        INTERNAL_ERROR_MESSAGE,
                        traceId,
                        null
                )
        );
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status,
            ApiErrorCode code,
            String message,
            List<ApiFieldError> fieldErrors,
            HttpServletRequest request) {
        ApiErrorResponse response = new ApiErrorResponse(
                code,
                message,
                TraceIds.getOrCreate(request),
                fieldErrors
        );
        return ResponseEntity.status(status).body(response);
    }
}
