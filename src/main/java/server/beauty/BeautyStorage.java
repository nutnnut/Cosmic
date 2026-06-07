package server.beauty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class BeautyStorage {
    private static final Logger log = LoggerFactory.getLogger(BeautyStorage.class);

    public static List<BeautyData> loadAll(int characterId) {
        List<BeautyData> result = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT * FROM beautystorage WHERE characterid = ? ORDER BY slottype, slot")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load beauty storage for character {}", characterId, e);
        }
        return result;
    }

    public static void save(BeautyData data) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO beautystorage (characterid, slot, slottype, gender, skin, hair, face, hat, top, bottom, shoes, weapon, cashweapon) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE gender=VALUES(gender), skin=VALUES(skin), hair=VALUES(hair), face=VALUES(face), "
                             + "hat=VALUES(hat), top=VALUES(top), bottom=VALUES(bottom), shoes=VALUES(shoes), weapon=VALUES(weapon), cashweapon=VALUES(cashweapon)")) {
            ps.setInt(1, data.characterId());
            ps.setInt(2, data.slot());
            ps.setInt(3, data.type());
            ps.setInt(4, data.gender());
            ps.setInt(5, data.skin());
            ps.setInt(6, data.hair());
            ps.setInt(7, data.face());
            ps.setInt(8, data.hat());
            ps.setInt(9, data.top());
            ps.setInt(10, data.bottom());
            ps.setInt(11, data.shoes());
            ps.setInt(12, data.weapon());
            ps.setInt(13, data.cashWeapon());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save beauty storage for character {}", data.characterId(), e);
        }
    }

    public static void delete(int characterId, int slot, int type) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM beautystorage WHERE characterid = ? AND slot = ? AND slottype = ?")) {
            ps.setInt(1, characterId);
            ps.setInt(2, slot);
            ps.setInt(3, type);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete beauty storage for character {}", characterId, e);
        }
    }

    public static int getUnlockedSlots(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT slots FROM beautyunlock WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("slots");
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load unlocked beauty slots for character {}", characterId, e);
        }
        return 0;
    }

    public static void setUnlockedSlots(int characterId, int slots) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO beautyunlock (characterid, slots) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE slots = VALUES(slots)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, slots);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save unlocked beauty slots for character {}", characterId, e);
        }
    }

    private static BeautyData fromResultSet(ResultSet rs) throws SQLException {
        return new BeautyData(
                rs.getInt("characterid"), rs.getInt("slot"), rs.getInt("slottype"),
                rs.getInt("gender"), rs.getInt("skin"), rs.getInt("hair"), rs.getInt("face"),
                rs.getInt("hat"), rs.getInt("top"), rs.getInt("bottom"), rs.getInt("shoes"),
                rs.getInt("weapon"), rs.getInt("cashweapon"));
    }
}
