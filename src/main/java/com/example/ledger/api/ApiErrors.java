package com.example.ledger.api;

import java.util.Map;
import java.util.concurrent.CompletionException;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ledger.domain.LedgerException;
import com.example.ledger.support.LedgerFormatting;

/**
 * Translates request failures into structured API errors without exposing internal details.
 */
@RestControllerAdvice
public class ApiErrors {
    /**
     * Returns a structured error containing the retryability and posting outcome.
     *
     * @param error Structured ledger failure retaining retryability and posting-outcome information.
     * @return HTTP error response retaining the failure's retryability and posting outcome.
     */
    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<Map<String, Object>> handleLedgerError(LedgerException error) {
        return ResponseEntity.status(error.getStatus())
                .body(
                        LedgerFormatting.createMap(
                                "code",
                                error.getCode(),
                                "message",
                                error.getMessage(),
                                "retryable",
                                error.isRetryable(),
                                "outcome",
                                error.getOutcome()));
    }

    /**
     * Returns a validation error for malformed request bodies or query parameters.
     *
     * @param error Framework parsing or binding failure; internal details are not exposed to the caller.
     * @return Validation response confirming that malformed input was not posted.
     */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception error) {
        return handleLedgerError(
                LedgerException.createInvalid("Malformed JSON, path, or query parameters"));
    }

    /**
     * Preserves framework client errors and reports unexpected failures as unknown outcomes.
     *
     * @param error Unhandled framework or worker failure to translate without exposing internal details.
     * @return Framework client error or unexpected-failure response with an unknown financial outcome.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpectedError(Exception error) {
        if (error instanceof ErrorResponse response
                && response.getStatusCode().is4xxClientError()) {
            return handleLedgerError(
                    new LedgerException(
                            "INVALID_HTTP_REQUEST",
                            "Requested route, method, or content is invalid",
                            response.getStatusCode().value(),
                            false,
                            "NOT_POSTED"));
        }
        if (error instanceof CompletionException
                && error.getCause() instanceof LedgerException ledgerError) {
            return handleLedgerError(ledgerError);
        }
        LoggerFactory.getLogger(ApiErrors.class).error("Unhandled ledger request failure", error);
        return handleLedgerError(
                new LedgerException(
                        "INTERNAL_ERROR",
                        "Request could not be completed; retry a financial request with its"
                                + " original key",
                        500,
                        true,
                        "UNKNOWN"));
    }
}
