package io.minimum.minecraft.superbvote;

import io.minimum.minecraft.superbvote.commands.SuperbVoteCommand;
import io.minimum.minecraft.superbvote.configuration.SuperbVoteConfiguration;
import io.minimum.minecraft.superbvote.scoreboard.ScoreboardHandler;
import io.minimum.minecraft.superbvote.signboard.TopPlayerSignFetcher;
import io.minimum.minecraft.superbvote.signboard.TopPlayerSignListener;
import io.minimum.minecraft.superbvote.signboard.TopPlayerSignStorage;
import io.minimum.minecraft.superbvote.storage.QueuedVotesStorage;
import io.minimum.minecraft.superbvote.storage.RecentVotesStorage;
import io.minimum.minecraft.superbvote.storage.VoteStorage;
import io.minimum.minecraft.superbvote.util.BrokenNag;
import io.minimum.minecraft.superbvote.util.SpigotUpdater;
import io.minimum.minecraft.superbvote.util.cooldowns.VoteServiceCooldown;
import io.minimum.minecraft.superbvote.votes.SuperbVoteListener;
import io.minimum.minecraft.superbvote.votes.VoteReminder;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SuperbVote extends JavaPlugin {
    @Getter
    private static SuperbVote plugin;
    @Getter
    private SuperbVoteConfiguration configuration;
    @Getter
    private VoteStorage voteStorage;
    @Getter
    private QueuedVotesStorage queuedVotes;
    @Getter
    private RecentVotesStorage recentVotesStorage;
    @Getter
    private ScoreboardHandler scoreboardHandler;
    @Getter
    private VoteServiceCooldown voteServiceCooldown;
    @Getter
    private TopPlayerSignStorage topPlayerSignStorage;
    private BukkitTask voteReminderTask;

    @Override
    public void onEnable() {
        plugin = this;
        saveDefaultConfig();
        configuration = new SuperbVoteConfiguration(getConfig());

        if (configuration.isConfigurationError()) {
            BrokenNag.nag(getServer().getConsoleSender());
        }

        try {
            voteStorage = configuration.initializeVoteStorage();
        } catch (Exception e) {
            throw new RuntimeException("Exception whilst initializing vote storage", e);
        }

        try {
            queuedVotes = new QueuedVotesStorage(new File(getDataFolder(), "queued_votes.json"));
        } catch (IOException e) {
            throw new RuntimeException("Exception whilst initializing queued vote storage", e);
        }

        recentVotesStorage = new RecentVotesStorage();

        scoreboardHandler = new ScoreboardHandler();
        voteServiceCooldown = loadVoteServiceCooldown();

        topPlayerSignStorage = new TopPlayerSignStorage();
        try {
            topPlayerSignStorage.load(new File(getDataFolder(), "top_voter_signs.json"));
        } catch (IOException e) {
            throw new RuntimeException("Exception whilst loading top player signs", e);
        }

        getCommand("superbvote").setExecutor(new SuperbVoteCommand());
        registerCommands();

        getServer().getPluginManager().registerEvents(new SuperbVoteListener(), this);
        getServer().getPluginManager().registerEvents(new TopPlayerSignListener(), this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, voteStorage::save, 20, 20 * 30);
        getServer().getScheduler().runTaskTimerAsynchronously(this, queuedVotes::save, 20, 20 * 30);
        getServer().getScheduler().runTaskAsynchronously(this, SuperbVote.getPlugin().getScoreboardHandler()::doPopulate);
        getServer().getScheduler().runTaskAsynchronously(this, new TopPlayerSignFetcher(topPlayerSignStorage.getSignList()));

        int r = getConfig().getInt("vote-reminder.repeat");
        String text = getConfig().getString("vote-reminder.message");
        if (text != null && !text.isEmpty()) {
            if (r > 0) {
                voteReminderTask = getServer().getScheduler().runTaskTimerAsynchronously(this, new VoteReminder(), 20 * r, 20 * r);
            }
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            getLogger().info("Using clip's PlaceholderAPI to provide extra placeholders.");
        }

        SpigotUpdater updater = new SpigotUpdater();
        getServer().getScheduler().runTaskAsynchronously(this, updater);
        getServer().getPluginManager().registerEvents(updater, this);
    }

    @Override
    public void onDisable() {
        if (voteReminderTask != null) {
            voteReminderTask.cancel();
            voteReminderTask = null;
        }
        voteStorage.save();
        queuedVotes.save();
        voteStorage.close();
        try {
            topPlayerSignStorage.save(new File(getDataFolder(), "top_voter_signs.json"));
        } catch (IOException e) {
            throw new RuntimeException("Exception whilst saving top player signs", e);
        }
    }

    private VoteServiceCooldown loadVoteServiceCooldown() {
        // A list rather than a map: service names contain dots (Minecraft.Buzz), which a config key would treat as a path.
        Map<String, Integer> overrides = new HashMap<>();
        for (Map<?, ?> entry : getConfig().getMapList("votes.cooldown-per-service-overrides")) {
            Object service = entry.get("service");
            Object seconds = entry.get("seconds");
            if (service instanceof String && seconds instanceof Integer && (Integer) seconds >= 0) {
                overrides.put((String) service, (Integer) seconds);
            } else {
                getLogger().warning("Ignoring an entry in votes.cooldown-per-service-overrides: it needs a "
                        + "\"service\" name and a \"seconds\" number (0 or more), but got " + entry);
            }
        }
        return new VoteServiceCooldown(getConfig().getInt("votes.cooldown-per-service", 3600), overrides);
    }

    public void reloadPlugin() {
        reloadConfig();
        configuration = new SuperbVoteConfiguration(getConfig());
        scoreboardHandler.reload();
        voteServiceCooldown = loadVoteServiceCooldown();
        getServer().getScheduler().runTaskAsynchronously(this, getScoreboardHandler()::doPopulate);
        registerCommands();

        if (voteReminderTask != null) {
            voteReminderTask.cancel();
            voteReminderTask = null;
        }
        int r = getConfig().getInt("vote-reminder.repeat");
        String text = getConfig().getString("vote-reminder.message");
        if (text != null && !text.isEmpty() && r > 0) {
            voteReminderTask = getServer().getScheduler().runTaskTimerAsynchronously(this, new VoteReminder(), 20 * r, 20 * r);
        }
    }

    public ClassLoader _exposeClassLoader() {
        return getClassLoader();
    }

    private void registerCommands() {
        if (configuration.getVoteCommand() != null) {
            getCommand("vote").setExecutor(configuration.getVoteCommand());
        }
        if (configuration.getVoteStreakCommand() != null) {
            getCommand("votestreak").setExecutor(configuration.getVoteStreakCommand());
        }
    }
}
