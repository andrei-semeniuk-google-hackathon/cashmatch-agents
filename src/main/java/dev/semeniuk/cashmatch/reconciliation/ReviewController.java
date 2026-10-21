package dev.semeniuk.cashmatch.reconciliation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
class ReviewController {

    private final Reconciliation reconciliation;

    ReviewController(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    record Decision(String reviewer, List<String> invoiceNumbers) {}

    @GetMapping
    List<ReviewTask> open() {
        return reconciliation.openReviews();
    }

    @PostMapping("/{id}/approve")
    PaymentReconciled approve(@PathVariable UUID id, @RequestBody Decision decision) {
        return reconciliation.approve(id, Optional.ofNullable(decision.invoiceNumbers()), decision.reviewer());
    }

    @PostMapping("/{id}/reject")
    void reject(@PathVariable UUID id, @RequestBody Decision decision) {
        reconciliation.reject(id, decision.reviewer());
    }

    @ExceptionHandler(ConcurrentReconciliationException.class)
    ProblemDetail conflict(ConcurrentReconciliationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
