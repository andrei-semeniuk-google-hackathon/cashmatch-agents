package dev.semeniuk.cashmatch.banking;

import dev.semeniuk.cashmatch.money.Money;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * One incoming credit from a bank statement.
 *
 * @param credited   what landed on our account, after any conversion and deducted fees
 * @param instructed what the payer instructed, in the payer's currency, when the bank reports it
 * @param charges    fees deducted on the way (SWIFT SHA/BEN), when reported
 */
public record BankTransaction(
        UUID id,
        String bankReference,
        String endToEndId,
        Money credited,
        Optional<Money> instructed,
        Optional<Money> charges,
        ChargeBearer chargeBearer,
        LocalDate valueDate,
        String debtorName,
        String debtorIban,
        String remittanceInfo,
        Status status) {

    public enum Status { RECEIVED, POSTED, IN_REVIEW, UNMATCHED }

    /** SWIFT field 71A: who pays the fees. Only DEBT (OUR) guarantees we receive the full amount. */
    public enum ChargeBearer {
        SHAR, DEBT, CRED, SLEV, UNKNOWN;

        public boolean mayDeductFees() {
            return this == SHAR || this == CRED || this == SLEV || this == UNKNOWN;
        }
    }

    /** The amount to match invoices against: the payer's instruction when known. */
    public Money matchingAmount() {
        return instructed.orElse(credited);
    }
}
