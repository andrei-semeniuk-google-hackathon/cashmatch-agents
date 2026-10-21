package dev.semeniuk.cashmatch.invoicing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface InvoiceRepository extends ListCrudRepository<Invoice, UUID> {

    Optional<Invoice> findByNumber(String number);

    List<Invoice> findByNumberIn(Collection<String> numbers);

    List<Invoice> findByCustomerIdAndStatusNotOrderByDueDateAscNumberAsc(
            UUID customerId, Invoice.Status status);
}
