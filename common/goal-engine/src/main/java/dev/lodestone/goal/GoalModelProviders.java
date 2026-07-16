// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

public final class GoalModelProviders {
    public static final String EXECUTOR_MODEL_ID = "gpt-5.4-mini";

    private GoalModelProviders() {
    }

    public static GoalModelProvider select() {
        var providers = new ArrayList<GoalModelProvider>();
        ServiceLoader.load(GoalModelProvider.class).forEach(providers::add);
        var configured = HttpJsonGoalModelProvider.fromEnvironment();
        configured.ifPresent(providers::add);
        var usable = providers.stream().filter(provider -> !provider.fallback()).toList();
        if (!usable.isEmpty()) {
            return usable.stream()
                    .min(Comparator.comparingLong(GoalModelProvider::measuredP95LatencyMs)
                            .thenComparing(provider -> EXECUTOR_MODEL_ID.equals(provider.id()) ? 0 : 1))
                    .orElseThrow();
        }
        return new DeterministicGoalModelProvider();
    }

    public static List<String> availableProviderIds() {
        var ids = new ArrayList<String>();
        ServiceLoader.load(GoalModelProvider.class).forEach(provider -> ids.add(provider.id()));
        HttpJsonGoalModelProvider.fromEnvironment().ifPresent(provider -> ids.add(provider.id()));
        ids.add("deterministic-fallback");
        return List.copyOf(ids);
    }

    private static final class DeterministicGoalModelProvider implements GoalModelProvider {
        @Override
        public String id() {
            return "deterministic-fallback";
        }

        @Override
        public long measuredP95LatencyMs() {
            return 0;
        }

        @Override
        public boolean fallback() {
            return true;
        }

        @Override
        public java.util.Optional<GoalDecision> choose(GoalDecisionRequest request) {
            return request.candidates().isEmpty()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(new GoalDecision(0, "deterministic plan order"));
        }
    }
}
