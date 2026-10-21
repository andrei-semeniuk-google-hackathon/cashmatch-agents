/**
 * Money value types shared by every module. Amounts are {@link java.math.BigDecimal} at the
 * currency's minor-unit scale; there is no floating point anywhere near a balance.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package dev.semeniuk.cashmatch.money;

import org.springframework.modulith.ApplicationModule;
