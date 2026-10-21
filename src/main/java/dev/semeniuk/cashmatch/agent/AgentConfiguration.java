package dev.semeniuk.cashmatch.agent;

import dev.semeniuk.cashmatch.reconciliation.PaymentInvestigator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {

    /** The LLM investigator. Switched off, the rules still run and everything else goes to review. */
    @Bean
    @ConditionalOnProperty(name = "cashmatch.agent.enabled", havingValue = "true")
    PaymentInvestigator llmPaymentInvestigator(ChatModel model, ReceivablesTools tools) {
        return new LlmPaymentInvestigator(ChatClient.builder(model).build(), tools);
    }

    /** The same tools over MCP, so Claude Code, Claude Desktop or any MCP client can query receivables. */
    @Bean
    ToolCallbackProvider receivablesMcpTools(ReceivablesTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
