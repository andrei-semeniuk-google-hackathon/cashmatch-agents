package dev.semeniuk.cashmatch.banking;

public class StatementParseException extends RuntimeException {
    public StatementParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
