package dev.semeniuk.cashmatch.banking;

import java.util.UUID;
import org.jmolecules.event.annotation.DomainEvent;

/** Published once per new credit. Delivery is guaranteed by the Modulith event registry. */
@DomainEvent
public record PaymentReceived(UUID transactionId) {}
