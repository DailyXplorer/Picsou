package com.picsou.config;

import com.picsou.adapter.NoopCategorizer;
import com.picsou.adapter.SpringAiCategorizer;
import com.picsou.port.TransactionCategorizerPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link TransactionCategorizerPort}. The active provider is selected entirely by
 * configuration: when {@code spring.ai.model.chat} names a provider (ollama / openai / anthropic),
 * Spring AI auto-configures a single {@link ChatModel} and we back the categorizer with it; when no
 * provider is selected (the default {@code none}), no {@link ChatModel} exists and we fall back to
 * {@link NoopCategorizer}, so the app boots with AI categorization fully OFF.
 *
 * <p>Resolution uses {@link ObjectProvider#getIfUnique()} rather than a {@code @ConditionalOnBean}
 * (which would race Spring AI's auto-configuration ordering): it yields the {@link ChatModel} only
 * when exactly one exists, and {@code null} on zero or ambiguous — both of which degrade to the
 * safe no-op.
 */
@Configuration
public class AiCategorizationConfig {

    @Bean
    TransactionCategorizerPort transactionCategorizer(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel chatModel = chatModelProvider.getIfUnique();
        return chatModel != null ? new SpringAiCategorizer(chatModel) : new NoopCategorizer();
    }
}
