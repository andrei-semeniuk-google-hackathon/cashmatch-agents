package dev.semeniuk.cashmatch.banking;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/statements")
class StatementController {

    private final Payments payments;

    StatementController(Payments payments) {
        this.payments = payments;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    Payments.IngestionResult upload(HttpServletRequest request) throws IOException {
        return payments.ingest(request.getInputStream());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Payments.IngestionResult uploadFile(@RequestParam MultipartFile file) throws IOException {
        return payments.ingest(file.getInputStream());
    }

    @ExceptionHandler(StatementParseException.class)
    ProblemDetail unreadable(StatementParseException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
