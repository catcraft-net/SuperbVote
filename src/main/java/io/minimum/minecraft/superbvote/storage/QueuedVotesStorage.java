package io.minimum.minecraft.superbvote.storage;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.minimum.minecraft.superbvote.SuperbVote;
import io.minimum.minecraft.superbvote.votes.Vote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class QueuedVotesStorage {
    private final Map<UUID, List<Vote>> queuedVotes = new ConcurrentHashMap<>(32, 0.75f, 2);
    // 24-hour clock: "h" without an AM/PM marker read afternoon votes back 12 hours early after a restart.
    private final Gson gson = new GsonBuilder().setDateFormat("MMM d, yyyy H:mm:ss").create();
    private final File saveTo;

    public QueuedVotesStorage(File file) throws IOException {
        Preconditions.checkNotNull(file, "file");
        saveTo = file;

        if (!file.exists()) file.createNewFile();

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<UUID, List<Vote>> votes = gson.fromJson(reader, new TypeToken<Map<UUID, List<Vote>>>() {
            }.getType());
            if (votes != null) {
                int dropped = 0;
                for (Map.Entry<UUID, List<Vote>> entry : votes.entrySet()) {
                    // An entry without a received time can't be stored (MysqlVoteStorage needs it) or dated, so drop it.
                    List<Vote> valid = new CopyOnWriteArrayList<>();
                    for (Vote vote : entry.getValue()) {
                        if (vote != null && vote.getReceived() != null) valid.add(vote);
                        else dropped++;
                    }
                    if (!valid.isEmpty()) queuedVotes.put(entry.getKey(), valid);
                }
                if (dropped > 0) {
                    SuperbVote.getPlugin().getLogger().warning("Dropped " + dropped + " queued vote(s) with no received time from " + saveTo.getName() + ".");
                }
                int expired = removeExpiredVotes();
                if (expired > 0) {
                    SuperbVote.getPlugin().getLogger().info("Dropped " + expired + " queued vote(s) older than " +
                            SuperbVote.getPlugin().getConfiguration().getQueueMaxAgeDays() + " days from " + saveTo.getName() + ".");
                }
            }
        } catch (JsonSyntaxException e) {
            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            String movedName = String.format("%s-broken-%s", saveTo.getName(), date);
            SuperbVote.getPlugin().getLogger().severe("Your queued vote storage file is corrupted. Starting fresh by moving it to " + movedName + ".");
            Files.move(saveTo.toPath(), Paths.get(saveTo.getParent()).resolve(movedName));
        }
    }

    public void addVote(Vote vote) {
        Preconditions.checkNotNull(vote, "votes");
        List<Vote> votes = queuedVotes.computeIfAbsent(vote.getUuid(), (ignored) -> new CopyOnWriteArrayList<>());
        votes.add(vote);
    }

    public void clearVotes() {
        queuedVotes.clear();
    }

    public List<Vote> getAndRemoveVotes(UUID player) {
        Preconditions.checkNotNull(player, "player");
        List<Vote> votes = queuedVotes.remove(player);
        if (votes == null) return ImmutableList.of();
        long cutoff = expiryCutoff();
        if (cutoff > 0) votes.removeIf(vote -> vote.getReceived().getTime() < cutoff);
        return votes;
    }

    /**
     * Votes are forwarded to every realm, so a realm queues votes for players who play elsewhere and may never
     * log in there. Queued votes older than {@code queue-max-age-days} (0 keeps them forever) are dropped.
     */
    private int removeExpiredVotes() {
        long cutoff = expiryCutoff();
        if (cutoff <= 0) return 0;
        int removed = 0;
        for (Map.Entry<UUID, List<Vote>> entry : queuedVotes.entrySet()) {
            List<Vote> votes = entry.getValue();
            int before = votes.size();
            votes.removeIf(vote -> vote.getReceived().getTime() < cutoff);
            removed += before - votes.size();
            if (votes.isEmpty()) queuedVotes.remove(entry.getKey(), votes);
        }
        return removed;
    }

    private long expiryCutoff() {
        int days = SuperbVote.getPlugin().getConfiguration().getQueueMaxAgeDays();
        return days > 0 ? System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days) : 0;
    }

    public void save() {
        removeExpiredVotes();
        // Save to a temporary file and then copy over the existing file.
        try {
            Path tempPath = Files.createTempFile("superbvote-", ".json");
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardOpenOption.WRITE)) {
                gson.toJson(queuedVotes, writer);
            }

            Files.move(tempPath, saveTo.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Unable to save queued votes to " + saveTo, e);
        }
    }
}
