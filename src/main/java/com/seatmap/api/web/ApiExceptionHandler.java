package com.seatmap.api.web;

import java.util.List;

import com.seatmap.api.ai.AiAllowanceUsedException;
import com.seatmap.api.ai.AiUnavailableException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns the API's exceptions into RFC 9457 problem details. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler
	ProblemDetail handle(NotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(ConflictException ex) {
		return withProblems(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage()), ex.getProblems());
	}

	/** Two saves raced past the revision check; the later one loses, as if it had been stale. */
	@ExceptionHandler
	ProblemDetail handle(ObjectOptimisticLockingFailureException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"It was changed by someone else; reload it before saving");
	}

	@ExceptionHandler
	ProblemDetail handle(UnauthorizedException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(ForbiddenException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> handle(TooManyRequestsException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
	}

	@ExceptionHandler
	ProblemDetail handle(PayloadTooLargeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
	}

	/** The organization used this month's AI allowance; says when it renews. */
	@ExceptionHandler
	ProblemDetail handle(AiAllowanceUsedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
		problem.setProperty("resetsAt", ex.getResetsAt().toString());
		return problem;
	}

	/** The AI service failed or timed out: the user can try again later. */
	@ExceptionHandler
	ProblemDetail handle(AiUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(InvalidRequestException ex) {
		return withProblems(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()),
				ex.getProblems());
	}

	/** A {@code @Valid} body failed: say which fields and why, e.g. "password: size must be between 10 and 128". */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<String> problems = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> error.getField() + ": " + error.getDefaultMessage())
			.sorted()
			.toList();
		ProblemDetail problem = withProblems(ProblemDetail.forStatusAndDetail(status, "Some fields are invalid"),
				problems);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	private static ProblemDetail withProblems(ProblemDetail problem, List<String> problems) {
		if (!problems.isEmpty()) {
			problem.setProperty("problems", problems);
		}
		return problem;
	}

}
