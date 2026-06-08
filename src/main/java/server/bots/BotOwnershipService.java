package server.bots;

import client.BotClient;
import client.Character;
import net.server.Server;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class BotOwnershipService {
    private static final BotOwnershipService instance = new BotOwnershipService();

    public static BotOwnershipService getInstance() {
        return instance;
    }

    private BotOwnershipService() {
    }

    public ResolvedCharacter resolveCharacterByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        Character online = findOnlineCharacter(name);
        if (online != null) {
            return new ResolvedCharacter(
                    online.getId(),
                    online.getName(),
                    online.getAccountID(),
                    online);
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, name, accountid FROM characters WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                return new ResolvedCharacter(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("accountid"),
                        null);
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public AuthorizationResult ensureCanControl(Character owner, ResolvedCharacter bot) {
        if (owner == null || bot == null) {
            return AuthorizationResult.denied("Bot could not be resolved.");
        }
        if (bot.id() == owner.getId()) {
            return AuthorizationResult.denied("You cannot spawn your current character as a bot.");
        }

        Integer registeredOwnerId = getRegisteredOwnerId(bot.id());
        if (registeredOwnerId != null) {
            if (registeredOwnerId == owner.getId()) {
                return AuthorizationResult.allowed(false);
            }
            if (bot.accountId() == owner.getAccountID()) {
                registerOwner(bot.id(), owner.getId());
                return AuthorizationResult.allowed(true);
            }

            String registeredOwnerName = Character.getNameById(registeredOwnerId);
            String ownerName = registeredOwnerName != null ? registeredOwnerName : "another character";
            return AuthorizationResult.denied(
                    "Bot '" + bot.name() + "' is registered to '" + ownerName + "'. Log in on "
                            + bot.name() + " and use @registerbot " + owner.getName() + " to change owner.");
        }

        if (bot.accountId() == owner.getAccountID()) {
            registerOwner(bot.id(), owner.getId());
            return AuthorizationResult.allowed(true);
        }

        return AuthorizationResult.denied(
                "Bot '" + bot.name() + "' is not registered to you. Log in on "
                        + bot.name() + " and use @registerbot " + owner.getName() + ".");
    }

    public boolean isAuthorizedOwner(int botCharId, int ownerCharId) {
        Integer registeredOwnerId = getRegisteredOwnerId(botCharId);
        return registeredOwnerId != null && registeredOwnerId == ownerCharId;
    }

    public Integer getRegisteredOwnerId(int botCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT owner_char_id FROM bot_owners WHERE bot_char_id = ?")) {
            ps.setInt(1, botCharId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("owner_char_id");
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public List<Integer> getRegisteredBotIds(int ownerCharId) {
        List<Integer> botIds = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT bot_char_id FROM bot_owners WHERE owner_char_id = ?")) {
            ps.setInt(1, ownerCharId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    botIds.add(rs.getInt("bot_char_id"));
                }
            }
        } catch (SQLException e) {
            return botIds;
        }
        return botIds;
    }

    /** Whether the owner has @spawnbots auto-spawn-on-login enabled. */
    public boolean isAutoSpawnEnabled(int ownerCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT enabled FROM bot_autospawn WHERE owner_char_id = ?")) {
            ps.setInt(1, ownerCharId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("enabled") != 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void setAutoSpawnEnabled(int ownerCharId, boolean enabled) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_autospawn (owner_char_id, enabled) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)")) {
            ps.setInt(1, ownerCharId);
            ps.setInt(2, enabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            // best-effort; the toggle simply won't persist if the write fails
        }
    }

    public void registerOwner(int botCharId, int ownerCharId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_owners (bot_char_id, owner_char_id) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE owner_char_id = VALUES(owner_char_id)")) {
            ps.setInt(1, botCharId);
            ps.setInt(2, ownerCharId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register bot owner", e);
        }
    }

    public ResolvedCharacter resolveCharacterById(int charId) {
        for (var world : Server.getInstance().getWorlds()) {
            Character online = world.getPlayerStorage().getCharacterById(charId);
            if (online != null) {
                return new ResolvedCharacter(charId, online.getName(), online.getAccountID(), online);
            }
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT name, accountid FROM characters WHERE id = ?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ResolvedCharacter(charId, rs.getString("name"), rs.getInt("accountid"), null);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    private Character findOnlineCharacter(String name) {
        for (var world : Server.getInstance().getWorlds()) {
            Character online = world.getPlayerStorage().getCharacterByName(name);
            if (online != null) {
                return online;
            }
        }
        return null;
    }

    public record ResolvedCharacter(int id, String name, int accountId, Character onlineCharacter) {
        public boolean isOnline() {
            return onlineCharacter != null;
        }

        public boolean isOnlineAsBot() {
            return onlineCharacter != null && onlineCharacter.getClient() instanceof BotClient;
        }
    }

    public record AuthorizationResult(boolean allowed, boolean autoRegistered, String failureMessage) {
        static AuthorizationResult allowed(boolean autoRegistered) {
            return new AuthorizationResult(true, autoRegistered, null);
        }

        static AuthorizationResult denied(String failureMessage) {
            return new AuthorizationResult(false, false, failureMessage);
        }
    }
}
