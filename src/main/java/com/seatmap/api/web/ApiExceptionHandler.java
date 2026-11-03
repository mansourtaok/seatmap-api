package com.seatmap.api.web;

import java.util.List;

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
		return withProblems(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage()), ex.getProblems());
	}

	/** Two saves raced past the revision check; the later one loses, as if it had been stale. */
	@ExceptionHandler
	ProblemDetail handle(ObjectOptimisticLockingFailureException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"It was changed by someone else; reload it before saving");
	}

	@ExceptionHandler
	ProblemDetail handle(InvalidRequestException ex) {
		return withProblems(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()),
				ex.getProblems());
	}

	private static ProblemDetail withProblems(ProblemDetail problem, List<String> problems) {
		if (!problems.isEmpty()) {
			problem.setProperty("problems", problems);
		}
		return problem;
	}

}
