package client;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class DamageSkinInventory {

    public static final int DEFAULT_SKIN_ID = 0;

    private final Set<Integer> owned = new HashSet<>();
    private final Lock lock = new ReentrantLock();

    public boolean ownsSkin(int skinId) {
        if (skinId == DEFAULT_SKIN_ID) return true;
        lock.lock();
        try {
            return owned.contains(skinId);
        } finally {
            lock.unlock();
        }
    }

    public Set<Integer> getOwnedIds() {
        lock.lock();
        try {
            TreeSet<Integer> snapshot = new TreeSet<>();
            snapshot.add(DEFAULT_SKIN_ID);
            snapshot.addAll(owned);
            return Collections.unmodifiableSet(snapshot);
        } finally {
            lock.unlock();
        }
    }

    public boolean addSkin(int characterId, int skinId) throws SQLException {
        if (skinId == DEFAULT_SKIN_ID) return false;

        lock.lock();
        try {
            if (!owned.add(skinId)) return false;
        } finally {
            lock.unlock();
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT IGNORE INTO damageskin_inventory (characterId, skinId) VALUES (?, ?)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, skinId);
            ps.executeUpdate();
        }
        return true;
    }

    public void loadSkins(int characterId) throws SQLException {
        lock.lock();
        try {
            owned.clear();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT skinId FROM damageskin_inventory WHERE characterId = ?")) {
                ps.setInt(1, characterId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        owned.add(rs.getInt("skinId"));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void saveSkins(Connection con, int characterId) throws SQLException {
        // no-op: purchases are written eagerly in addSkin
    }
}
