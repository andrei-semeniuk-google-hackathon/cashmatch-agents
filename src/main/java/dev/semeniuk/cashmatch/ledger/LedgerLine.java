package dev.semeniuk.cashmatch.ledger;

import dev.semeniuk.cashmatch.money.Money;

public record LedgerLine(Account account, Side side, Money amount, String reference) {

    public enum Account { CASH, RECEIVABLES, BANK_CHARGES, FX_DIFFERENCE, UNAPPLIED_CASH }

    public enum Side { DEBIT, CREDIT }

    public LedgerLine {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("ledger amounts are positive; use the side for direction");
        }
    }

    public static LedgerLine debit(Account account, Money amount, String reference) {
        return new LedgerLine(account, Side.DEBIT, amount, reference);
    }

    public static LedgerLine credit(Account account, Money amount, String reference) {
        return new LedgerLine(account, Side.CREDIT, amount, reference);
    }
}
