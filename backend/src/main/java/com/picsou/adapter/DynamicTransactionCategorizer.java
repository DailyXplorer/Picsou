package com.picsou.adapter;

import com.picsou.config.AiConfigProvider;
import com.picsou.port.TransactionCategorizerPort;

import java.util.List;
import java.util.Optional;

/** {@link TransactionCategorizerPort} that resolves the active provider at call time via
 *  {@link AiConfigProvider#currentCategorizer()}, so an admin config change takes effect without
 *  a restart. When no provider is configured, that resolver yields a {@link NoopCategorizer} and
 *  this abstains (empty). */
public class DynamicTransactionCategorizer implements TransactionCategorizerPort {

    private final AiConfigProvider configProvider;

    public DynamicTransactionCategorizer(AiConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    public Optional<CategorySuggestion> categorize(CategorizationInput input,
                                                    List<CategoryOption> categories,
                                                    List<Example> examples) {
        return configProvider.currentCategorizer().categorize(input, categories, examples);
    }
}
