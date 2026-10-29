package io.minimum.minecraft.superbvote.votes.rewards.matchers;

import com.google.common.base.Preconditions;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.Vote;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code daily-votes: N} matches the vote that takes a player to N different sites voted on within
 * the last 24 hours. It matches only on the vote that crosses N, so the reward runs once, and a repeat
 * vote on a site already counted never matches.
 *
 * <p>{@link PlayerVotes#getLastVotes()} holds the last vote time per site and never includes the vote
 * being processed (online, offline-queued and fake votes alike), so the vote is counted here.
 * The window is rolling and relative to when the vote was received, so queued votes are judged by
 * when they arrived, not when the player logged in.
 *
 * <p>Votes received more than 24 hours ago never match. Votes are forwarded to every realm, so a realm
 * queues months of votes for players who play elsewhere; without this, logging in replayed every old
 * day's crossing at once (dozens of piñata votes and broadcasts in one tick).
 *
 * <p>{@code bedrock-daily-votes: M} (optional, in the same reward) lowers the count for Bedrock players
 * (Floodgate UUIDs), since some vote sites only accept Java names.
 *
 * <p>{@code daily-votes-repeat: true} (optional) matches every vote that leaves the player at N or more sites, not
 * only the one that crosses N. Use it for rewards that must keep renewing while a player keeps voting, such as a
 * 24-hour permission: someone who re-votes every site as it reopens stays at 9-10 sites and never crosses N again.
 */
public class DailyVotesRewardMatcher implements RewardMatcher {
    static RewardMatcherFactory FACTORY = section -> {
        if (section.isInt("daily-votes")) {
            int votes = section.getInt("daily-votes");
            Preconditions.checkArgument(votes >= 1, "daily-votes number must be greater than or equal to 1.");
            int bedrockVotes = section.getInt("bedrock-daily-votes", votes);
            Preconditions.checkArgument(bedrockVotes >= 1, "bedrock-daily-votes number must be greater than or equal to 1.");
            return Optional.of(new DailyVotesRewardMatcher(votes, bedrockVotes, section.getBoolean("daily-votes-repeat", false)));
        }
        return Optional.empty();
    };

    private static final long WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final int votes;
    private final int bedrockVotes;
    private final boolean repeat;

    public DailyVotesRewardMatcher(int votes) {
        this(votes, votes, false);
    }

    public DailyVotesRewardMatcher(int votes, int bedrockVotes, boolean repeat) {
        this.votes = votes;
        this.bedrockVotes = bedrockVotes;
        this.repeat = repeat;
    }

    /** Floodgate gives Bedrock players UUIDs whose top 64 bits are zero (00000000-0000-0000-xxxx-...). */
    static boolean isBedrock(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0;
    }

    @Override
    public boolean matches(Vote vote, PlayerVotes pv) {
        // A vote without a received time (an old queue entry) can't be placed in the window, so it never matches.
        if (vote.getReceived() == null) return false;
        long now = vote.getReceived().getTime();
        if (System.currentTimeMillis() - now >= WINDOW_MILLIS) return false;
        int before = 0;
        boolean siteAlreadyCounted = false;
        for (Map.Entry<String, Date> entry : pv.getLastVotes().entrySet()) {
            if (!inWindow(entry.getValue(), now)) continue;
            before++;
            if (entry.getKey().equals(vote.getServiceName())) siteAlreadyCounted = true;
        }
        int needed = isBedrock(vote.getUuid()) ? this.bedrockVotes : this.votes;
        if (repeat) return before + (siteAlreadyCounted ? 0 : 1) >= needed;
        return !siteAlreadyCounted && before + 1 == needed;
    }

    private static boolean inWindow(Date date, long now) {
        if (date == null) return false;
        long age = now - date.getTime();
        // Last-vote times are stored to the second, so allow a little clock slack for "just now".
        return age > -5000L && age < WINDOW_MILLIS;
    }
}
