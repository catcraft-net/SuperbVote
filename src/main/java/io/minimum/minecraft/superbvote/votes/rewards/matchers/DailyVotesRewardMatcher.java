package io.minimum.minecraft.superbvote.votes.rewards.matchers;

import com.google.common.base.Preconditions;
import io.minimum.minecraft.superbvote.util.PlayerVotes;
import io.minimum.minecraft.superbvote.votes.Vote;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
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
 */
public class DailyVotesRewardMatcher implements RewardMatcher {
    static RewardMatcherFactory FACTORY = section -> {
        if (section.isInt("daily-votes")) {
            int votes = section.getInt("daily-votes");
            Preconditions.checkArgument(votes >= 1, "daily-votes number must be greater than or equal to 1.");
            return Optional.of(new DailyVotesRewardMatcher(votes));
        }
        return Optional.empty();
    };

    private static final long WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final int votes;

    public DailyVotesRewardMatcher(int votes) {
        this.votes = votes;
    }

    @Override
    public boolean matches(Vote vote, PlayerVotes pv) {
        long now = vote.getReceived() != null ? vote.getReceived().getTime() : System.currentTimeMillis();
        if (System.currentTimeMillis() - now >= WINDOW_MILLIS) return false;
        int before = 0;
        boolean siteAlreadyCounted = false;
        for (Map.Entry<String, Date> entry : pv.getLastVotes().entrySet()) {
            if (!inWindow(entry.getValue(), now)) continue;
            before++;
            if (entry.getKey().equals(vote.getServiceName())) siteAlreadyCounted = true;
        }
        return !siteAlreadyCounted && before + 1 == this.votes;
    }

    private static boolean inWindow(Date date, long now) {
        if (date == null) return false;
        long age = now - date.getTime();
        // Last-vote times are stored to the second, so allow a little clock slack for "just now".
        return age > -5000L && age < WINDOW_MILLIS;
    }
}
