package dev.semeniuk.cashmatch.reconciliation;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Finance-owned thresholds. Changing them is a policy decision, not a code change.
 *
 * @param fxRelative         max FX difference as a fraction of the settled amount (0.005 = 0.5%)
 * @param maxBankChargesEur  largest unreported deduction treated as correspondent fees
 * @param autoPostLimitEur   payments above this always go to a human, whoever matched them
 * @param minAgentConfidence agent proposals below this go to a human
 */
@ConfigurationProperties("cashmatch.tolerances")
public record Tolerances(
        @DefaultValue("0.005") BigDecimal fxRelative,
        @DefaultValue("35.00") BigDecimal maxBankChargesEur,
        @DefaultValue("25000.00") BigDecimal autoPostLimitEur,
        @DefaultValue("0.85") double minAgentConfidence) {}
