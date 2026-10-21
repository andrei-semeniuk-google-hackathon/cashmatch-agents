package dev.semeniuk.cashmatch.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.semeniuk.cashmatch.banking.BankTransaction;
import dev.semeniuk.cashmatch.money.Money;
import dev.semeniuk.cashmatch.reconciliation.Investigation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class LlmPaymentInvestigatorTest {

    static final BankTransaction PAYMENT = new BankTransaction(UUID.randomUUID(), "BNK-1", "E2E",
            Money.of("7250.00", "USD"), Optional.empty(), Optional.empty(), BankTransaction.ChargeBearer.DEBT,
            LocalDate.of(2026, 10, 20), "SAKURA COMPONENTS KK TOKYO", "", "PO 4471/4472", BankTransaction.Status.RECEIVED);

    final List<Prompt> prompts = new ArrayList<>();

    LlmPaymentInvestigator investigator(ChatModel model) {
        return new LlmPaymentInvestigator(ChatClient.builder(model).build(), mock(ReceivablesTools.class));
    }

    ChatModel replying(String content) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build(); // like every tool-capable model
            }
        };
    }

    @Test
    void parsesTheStructuredVerdictAndOffersTheTools() {
        var investigation = investigator(replying("""
                {"verdict": "MATCH", "customerId": "44444444-4444-4444-4444-444444444444",
                 "invoiceNumbers": ["INV-2026-0105", "INV-2026-0106"], "confidence": 0.93,
                 "reasoning": "Name matches Sakura Components; 3000 + 4250 = 7250 USD."}
                """)).investigate(PAYMENT);

        assertThat(investigation.verdict()).isEqualTo(Investigation.Verdict.MATCH);
        assertThat(investigation.invoiceNumbers()).containsExactly("INV-2026-0105", "INV-2026-0106");

        var prompt = prompts.getFirst();
        assertThat(prompt.getContents()).contains("SAKURA COMPONENTS KK TOKYO", "7250.00 USD", "not provided");
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(t -> t.getToolDefinition().name())
                .contains("searchCustomers", "openInvoices", "previewSettlement", "convert");
    }

    @Test
    void anAgentFailureBecomesAReviewNotAnOutage() {
        ChatModel broken = prompt -> {
            throw new IllegalStateException("529 overloaded");
        };
        var investigation = investigator(broken).investigate(PAYMENT);
        assertThat(investigation.verdict()).isEqualTo(Investigation.Verdict.NO_MATCH);
        assertThat(investigation.reasoning()).contains("overloaded");
    }

    @Test
    void unparseableOutputBecomesAReview() {
        var investigation = investigator(replying("I think it is probably Sakura?")).investigate(PAYMENT);
        assertThat(investigation.verdict()).isEqualTo(Investigation.Verdict.NO_MATCH);
    }
}
