package io.minimum.minecraft.superbvote.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minimum.minecraft.superbvote.SuperbVote;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.bukkit.Bukkit;

@Value
public class PlayerVotes {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UUID uuid;
    private final String associatedUsername;
    private final int votes;
    private final Map<String, Date> lastVotes;
    private final Type type;

    public String getAssociatedUsername() {
        if (associatedUsername == null) {
            return Bukkit.getOfflinePlayer(uuid).getName();
        }
        return associatedUsername;
    }

    public enum Type {
        CURRENT,
        FUTURE;

    }
    public boolean hasVoteOnSameDay(String serviceName, Date voteDate) {
        Date lastVoteDate = lastVotes.get(serviceName);
        if (lastVoteDate == null) {
            return false;
        }
        if (lastVoteDate.equals(voteDate)) {
            // the new vote is just about being processed right now
            return false;
        }
        return DateUtils.isSameDay(lastVoteDate, voteDate);
    }

    public void updateLastVotes(String serviceName, Date voteReceived) {
        lastVotes.put(serviceName, voteReceived);
    }

    public String getSerializedLastVotes() {
        try {
            Map<String, Date> preprocessed = new HashMap<>();
            for (Map.Entry<String, Date> entry : lastVotes.entrySet()) {
                long dateSeconds = entry.getValue().getTime() / 1000;
                preprocessed.put(entry.getKey(), new Date(dateSeconds));
            }
            return OBJECT_MAPPER.writeValueAsString(preprocessed);
        } catch (JsonProcessingException e) {
            SuperbVote.getPlugin()
                    .getLogger()
                    .severe("Could not serialize last votes to JSON: " + lastVotes + "\n" + e.getMessage());
            return "";
        }
    }

    public static Map<String, Date> deserializeLastVotes(String lastVotesString) {
        if (StringUtils.isBlank(lastVotesString)) {
            return new HashMap<>();
        }
        try {
            Map<String, Long> lastVotes = OBJECT_MAPPER.readValue(lastVotesString, new TypeReference<>() {});
            return lastVotes.entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> new Date(e.getValue() * 1000)));
        } catch (JsonProcessingException e) {
            SuperbVote.getPlugin()
                    .getLogger()
                    .severe("Could not deserialize last votes from JSON: " + lastVotesString + "\n" + e.getMessage());
            return new HashMap<>();
        }
    }
}
