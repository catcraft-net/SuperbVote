package io.minimum.minecraft.superbvote.votes.rewards.matchers;

import com.google.common.base.Preconditions;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.Vote;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code max-age-hours: N} matches votes received less than N hours ago. Votes are forwarded to every realm and
 * queued for offline players, so a login can replay months of votes at once; this keeps rewards that should only
 * follow fresh votes (such as adding to the Piñata Party counter) from running for all of them.
 */
public class RecentVoteRewardMatcher implements RewardMatcher {
    static RewardMatcherFactory FACTORY = section -> {
        if (section.isInt("max-age-hours")) {
            int hours = section.getInt("max-age-hours");
            Preconditions.checkArgument(hours >= 1, "max-age-hours number must be greater than or equal to 1.");
            return Optional.of(new RecentVoteRewardMatcher(hours));
        }
        return Optional.empty();
    };

    private final long maxAgeMillis;

    public RecentVoteRewardMatcher(int hours) {
        this.maxAgeMillis = TimeUnit.HOURS.toMillis(hours);
    }

    @Override
    public boolean matches(Vote vote, PlayerVotes pv) {
        // New votes always carry their received time; only an old queue entry can lack it, so treat it as old.
        if (vote.getReceived() == null) return false;
        return System.currentTimeMillis() - vote.getReceived().getTime() < maxAgeMillis;
    }
}
