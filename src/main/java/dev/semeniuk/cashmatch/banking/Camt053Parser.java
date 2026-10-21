package dev.semeniuk.cashmatch.banking;

import dev.semeniuk.cashmatch.money.Money;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads booked credits from an ISO 20022 camt.053 bank-to-customer statement.
 * Namespace-agnostic, so camt.053.001.02 through .001.08 all parse.
 */
@Component
class Camt053Parser {

    List<BankTransaction> parse(InputStream xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(xml);

            var entries = document.getElementsByTagNameNS("*", "Ntry");
            var result = new ArrayList<BankTransaction>();
            for (int i = 0; i < entries.getLength(); i++) {
                var entry = (Element) entries.item(i);
                if ("CRDT".equals(text(entry, "CdtDbtInd")) && isBooked(entry)) {
                    result.addAll(transactions(entry));
                }
            }
            return result;
        } catch (Exception e) {
            throw new StatementParseException("not a readable camt.053 statement: " + e.getMessage(), e);
        }
    }

    /**
     * A batch-booked entry carries one TxDtls per underlying payment. Each becomes its own
     * transaction, because each may pay different invoices of a different customer.
     */
    private static List<BankTransaction> transactions(Element entry) {
        var details = entry.getElementsByTagNameNS("*", "TxDtls");
        var entryReference = required(entry, "AcctSvcrRef");
        if (details.getLength() <= 1) {
            var single = details.getLength() == 1 ? (Element) details.item(0) : entry;
            return List.of(toTransaction(entry, single, entryAmount(entry), entryReference));
        }
        var result = new ArrayList<BankTransaction>();
        for (int i = 0; i < details.getLength(); i++) {
            var tx = (Element) details.item(i);
            var amount = directChild(tx, "Amt")
                    .or(() -> child(tx, "TxAmt").flatMap(t -> child(t, "Amt")))
                    .map(Camt053Parser::amount)
                    .orElseThrow(() -> new StatementParseException("batch entry without amounts", null));
            result.add(toTransaction(entry, tx, amount, entryReference + "/" + (i + 1)));
        }
        return result;
    }

    private static Money entryAmount(Element entry) {
        return directChild(entry, "Amt").map(Camt053Parser::amount)
                .orElseThrow(() -> new StatementParseException("entry without amount", null));
    }

    private static BankTransaction toTransaction(Element entry, Element details, Money credited, String reference) {
        var debtor = child(details, "Dbtr");
        var debtorAccount = child(details, "DbtrAcct");
        var chargeRecord = child(details, "Chrgs").flatMap(c -> child(c, "Rcrd"));
        return new BankTransaction(
                UUID.randomUUID(),
                reference,
                child(details, "EndToEndId").map(n -> n.getTextContent().trim()).orElse("NOTPROVIDED"),
                credited,
                child(details, "InstdAmt").flatMap(a -> child(a, "Amt")).map(Camt053Parser::amount),
                chargeRecord.flatMap(r -> child(r, "Amt")).map(Camt053Parser::amount).filter(Money::isPositive),
                chargeRecord.flatMap(r -> child(r, "Br"))
                        .map(b -> parseBearer(b.getTextContent()))
                        .orElse(BankTransaction.ChargeBearer.UNKNOWN),
                LocalDate.parse(child(entry, "ValDt").flatMap(d -> child(d, "Dt")).orElseThrow()
                        .getTextContent().trim()),
                debtor.flatMap(d -> child(d, "Nm")).map(n -> n.getTextContent().trim()).orElse(""),
                debtorAccount.flatMap(a -> child(a, "IBAN")).map(n -> n.getTextContent().trim())
                        .orElse(""),
                child(details, "RmtInf").map(r -> r.getTextContent().trim().replaceAll("\\s+", " "))
                        .orElse(""),
                BankTransaction.Status.RECEIVED);
    }

    private static boolean isBooked(Element entry) {
        // v02 has <Sts>BOOK</Sts>, v08 has <Sts><Cd>BOOK</Cd></Sts>
        return child(entry, "Sts").map(s -> s.getTextContent().trim()).orElse("BOOK").equals("BOOK");
    }

    private static Money amount(Element amt) {
        return Money.of(new BigDecimal(amt.getTextContent().trim()), Currency.getInstance(amt.getAttribute("Ccy")));
    }

    private static BankTransaction.ChargeBearer parseBearer(String code) {
        try {
            return BankTransaction.ChargeBearer.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return BankTransaction.ChargeBearer.UNKNOWN;
        }
    }

    private static String text(Element parent, String name) {
        return child(parent, name).map(n -> n.getTextContent().trim()).orElse("");
    }

    private static String required(Element parent, String name) {
        return child(parent, name).map(n -> n.getTextContent().trim())
                .orElseThrow(() -> new StatementParseException("entry without " + name, null));
    }

    private static Optional<Element> directChild(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** First descendant with the given local name. */
    private static Optional<Element> child(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? Optional.empty() : Optional.of((Element) nodes.item(0));
    }
}
