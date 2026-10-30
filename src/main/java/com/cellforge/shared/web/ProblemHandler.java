package com.cellforge.shared.web;

import com.cellforge.shared.error.BusinessRuleException;
import com.cellforge.shared.error.DomainException;
import com.cellforge.shared.error.ForbiddenException;
import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.NotFoundException;
import com.cellforge.shared.error.NotHostedException;
import com.cellforge.shared.error.PaymentRequiredException;
import com.cellforge.shared.error.PreconditionFailedException;
import com.cellforge.shared.error.PreconditionRequiredException;
import com.cellforge.shared.error.RateLimitedException;
import com.cellforge.shared.error.StateConflictException;
import com.cellforge.shared.error.UnavailableException;
import com.cellforge.shared.error.UpstreamUnavailableException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as application/problem+json (RFC 9457) with a stable {@code code} clients can switch on.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
    private static final HttpStatusCode MISDIRECTED = HttpStatusCode.valueOf(421);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        HttpStatusCode status = switch (e) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case ForbiddenException ignored -> HttpStatus.FORBIDDEN;
            case StateConflictException ignored -> HttpStatus.CONFLICT;
            case BusinessRuleException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PreconditionFailedException ignored -> HttpStatus.PRECONDITION_FAILED;
            case PreconditionRequiredException ignored -> HttpStatus.PRECONDITION_REQUIRED;
            case InvalidRequestException ignored -> HttpStatus.BAD_REQUEST;
            case UpstreamUnavailableException ignored -> HttpStatus.BAD_GATEWAY;
            case UnavailableException ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            case NotHostedException ignored -> MISDIRECTED;
            case PaymentRequiredException ignored -> HttpStatus.PAYMENT_REQUIRED;
            case RateLimitedException ignored -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (e instanceof UnavailableException u) {
            response.header(HttpHeaders.RETRY_AFTER, Integer.toString(u.retryAfterSeconds()));
        }
        if (e instanceof RateLimitedException r) {
            response.header(HttpHeaders.RETRY_AFTER, Integer.toString(r.retryAfterSeconds()));
        }
        ProblemDetail body = problem(status, e.code(), e.getMessage());
        e.details().forEach(body::setProperty);
        return response.body(body);
    }

    /**
     * The cell's transaction manager refuses to begin a write for a frozen or departed tenant; Spring wraps
     * whatever doBegin throws, so unwrap ours.
     */
    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<ProblemDetail> transactionRefused(CannotCreateTransactionException e) {
        if (e.getCause() instanceof DomainException domain) {
            return domain(domain);
        }
        return unexpected(e);
    }

    /**
     * The tenant's statement_timeout fired. It is a per-plan budget, so this is the tenant's limit, not ours.
     */
    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ProblemDetail> queryTimeout(QueryTimeoutException e) {
        log.warn("Statement timeout: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "query-timeout", "Query exceeded this plan's time budget"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation-failed", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage()));
    }

    /**
     * lock_timeout fired: another transaction held a row we needed for too long. Safe to retry.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> lockTimeout(PessimisticLockingFailureException e) {
        log.warn("Lock wait timed out: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "busy", "Resource is busy, retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError()
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error"));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation-failed", ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation-failed", detail));
    }

    /**
     * Spring's own errors (unreadable body, wrong method, missing header) get a code too, so clients never
     * have to special-case them.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", status.is4xxClientError() ? "invalid-request" : "internal-error");
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    public static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:cellforge:problem:" + code));
        HttpStatus known = HttpStatus.resolve(status.value());
        problem.setTitle(known != null ? known.getReasonPhrase() : "Misdirected Request");
        problem.setProperty("code", code);
        return problem;
    }
}
