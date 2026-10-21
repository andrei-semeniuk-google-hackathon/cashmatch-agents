package dev.semeniuk.cashmatch.ledger;

public class UnbalancedPostingException extends IllegalStateException {
    public UnbalancedPostingException(String message) {
        super(message);
    }
}
