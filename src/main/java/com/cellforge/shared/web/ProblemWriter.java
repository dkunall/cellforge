package com.cellforge.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/** For filters and security handlers, which run outside MVC and so outside {@link ProblemHandler}. */
public final class ProblemWriter {

    private ProblemWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper json, HttpStatusCode status, String code,
                             String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemHandler.problem(status, code, detail));
    }
}
