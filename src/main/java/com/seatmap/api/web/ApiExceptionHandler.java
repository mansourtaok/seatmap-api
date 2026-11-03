package com.seatmap.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	/** Two saves raced past the revision check; the later one loses, as if it had been stale. */
	@ExceptionHandler
	ProblemDetail handle(ObjectOptimisticLockingFailureException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The layout was changed by someone else; reload it before saving");
	}

	@ExceptionHandler
	ProblemDetail handle(InvalidRequestException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		if (!ex.getProblems().isEmpty()) {
			problem.setProperty("problems", ex.getProblems());
		}
		return problem;
	}

}
