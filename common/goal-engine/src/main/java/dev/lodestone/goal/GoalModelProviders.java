// SPDX-License-Identifier: MIT
package dev.lodestone.goal;

import java.util.ArrayList;
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
        var pinned = usable.stream().filter(provider -> EXECUTOR_MODEL_ID.equals(provider.id())).findFirst();
        if (pinned.isPresent()) return pinned.get();
        if (!usable.isEmpty()) return new UnavailablePinnedModelProvider();
        return new DeterministicGoalModelProvider();
    }

    public static List<String> availableProviderIds() {
        var ids = new ArrayList<String>();
        ServiceLoader.load(GoalModelProvider.class).forEach(provider -> ids.add(provider.id()));
        HttpJsonGoalModelProvider.fromEnvironment().ifPresent(provider -> ids.add(provider.id()));
        ids.add("deterministic-fallback");
        return List.copyOf(ids);
    }

    private static final class UnavailablePinnedModelProvider implements GoalModelProvider {
        @Override
        public String id() { return "unavailable:" + EXECUTOR_MODEL_ID; }

        @Override
        public boolean fallback() { return true; }

        @Override
        public java.util.Optional<GoalDecision> choose(GoalDecisionRequest request) {
            return java.util.Optional.empty();
        }
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
