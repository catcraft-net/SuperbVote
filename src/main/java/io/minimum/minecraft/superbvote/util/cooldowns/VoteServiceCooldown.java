package io.minimum.minecraft.superbvote.util.cooldowns;

import io.minimum.minecraft.superbvote.votes.Vote;
import lombok.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class VoteServiceCooldown extends CooldownHandler<VoteServiceCooldown.IntData> {
    private final Map<String, Integer> overrides;

    public VoteServiceCooldown(int max) {
        this(max, Collections.emptyMap());
    }

    /**
     * @param max       the cooldown, in seconds, for every service without an override
     * @param overrides per-service cooldowns in seconds, keyed by service name (matched case-insensitively)
     */
    public VoteServiceCooldown(int max, Map<String, Integer> overrides) {
        super(max);
        Map<String, Integer> normalized = new HashMap<>();
        overrides.forEach((service, seconds) -> normalized.put(service.toLowerCase(Locale.ROOT), seconds));
        this.overrides = normalized;
    }

    /** The cooldown in seconds that applies to the given service. */
    public int getMax(String serviceName) {
        if (serviceName == null) {
            return getMax();
        }
        return overrides.getOrDefault(serviceName.toLowerCase(Locale.ROOT), getMax());
    }

    public boolean triggerCooldown(Vote vote) {
        return super.triggerCooldown(new IntData(vote.getUuid(), vote.getServiceName()), getMax(vote.getServiceName()));
    }

    @Value
    static class IntData {
        private final UUID uuid;
        private final String serviceName;
    }
}
