package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Append-only market tape (table {@code bot_market_event}, docs/bot/economy.md
 * sec 12) + in-memory faucet/sink tallies. Mirrors the {@link ManagedBotService} DAO pattern.
 *
 * <p>Consumers: the consensus sweep reads the recent window; the web observability endpoints read
 * stats. <b>Bot decision code never reads this class</b> (design sec 10.6) — bots learn prices only
 * through their own {@code BotMarketBook}.
 */
public final class BotMarketLedger {
    private static final Logger log = LoggerFactory.getLogger(BotMarketLedger.class);
    private static final BotMarketLedger instance = new BotMarketLedger();

    public static BotMarketLedger getInstance() {
        return instance;
    }

    private BotMarketLedger() {
    }

    /** Event kinds; codes are the {@code bot_market_event.kind} column values (stable, append-only). */
    public enum EventKind {
        TRADE(0), STALL_SALE(1), LIST(2), DELIST(3), EXPIRE(4), SHOUT(5),
        NPC_SELL(6), NPC_BUY(7), SINK(8), FAUCET(9), UNSOLD(10), SOLD_FAST(11);

        final int code;

        EventKind(int code) {
            this.code = code;
        }

        static EventKind fromCode(int code) {
            for (EventKind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            return null;
        }

        /** Clearing prices — the evidence class the consensus median is built from. */
        public boolean isClearing() {
            return this == TRADE || this == STALL_SALE;
        }
    }

    /** One tape row. {@code sellerId}/{@code buyerId}/{@code mapId} null when not applicable. */
    public record MarketEvent(long id, long atMs, EventKind kind, int itemId, int quality, int qty,
                              long unitPrice, Integer sellerId, Integer buyerId, Integer mapId) {
        public long priceKey() {
            return BotMarketMath.priceKey(itemId, quality);
        }
    }

    // ------------------------------------------------------------------ append

    /** Append one event; swallow-and-log on DB trouble (the tape must never break gameplay). */
    public void append(EventKind kind, int itemId, int quality, int qty, long unitPrice,
                       Integer sellerId, Integer buyerId, Integer mapId) {
        if (BotManager.cfg.MARKET_TX_CONSOLE) {
            String name;
            try {
                name = server.ItemInformationProvider.getInstance().getName(itemId);
            } catch (RuntimeException e) {
                name = null; // WZ not loaded (tests) - id-only line
            }
            log.info("market {}: {}x {} ({}) @{} seller={} buyer={} map={}",
                    kind, qty, name != null ? name : "?", itemId, unitPrice, sellerId, buyerId, mapId);
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO bot_market_event (kind, item_id, quality, qty, unit_price, seller_id, buyer_id, map_id)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, kind.code);
            ps.setInt(2, itemId);
            ps.setInt(3, quality);
            ps.setInt(4, qty);
            ps.setLong(5, unitPrice);
            setNullableInt(ps, 6, sellerId);
            setNullableInt(ps, 7, buyerId);
            setNullableInt(ps, 8, mapId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("bot_market_event append failed ({} item {} @{}): {}", kind, itemId, unitPrice, e.toString());
        }
    }

    /** Meso created/destroyed accounting (design sec 5: measured, never managed). */
    public void recordFlow(String category, long meso, boolean faucet) {
        (faucet ? faucetByCategory : sinkByCategory)
                .computeIfAbsent(category, k -> new LongAdder()).add(meso);
    }

    // ------------------------------------------------------------------ read (sweep + web only)

    /** Events at/after {@code sinceMs}, ascending by time. Optionally filtered to clearing kinds. */
    public List<MarketEvent> recentEvents(long sinceMs, boolean clearingOnly) {
        List<MarketEvent> out = new ArrayList<>();
        String sql = "SELECT id, at, kind, item_id, quality, qty, unit_price, seller_id, buyer_id, map_id"
                + " FROM bot_market_event WHERE at >= ?"
                + (clearingOnly ? " AND kind IN (0, 1)" : "")
                + " ORDER BY at ASC";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(sinceMs));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EventKind kind = EventKind.fromCode(rs.getInt("kind"));
                    if (kind == null) {
                        continue;
                    }
                    out.add(new MarketEvent(
                            rs.getLong("id"),
                            rs.getTimestamp("at").getTime(),
                            kind,
                            rs.getInt("item_id"),
                            rs.getInt("quality"),
                            rs.getInt("qty"),
                            rs.getLong("unit_price"),
                            readNullableInt(rs, "seller_id"),
                            readNullableInt(rs, "buyer_id"),
                            readNullableInt(rs, "map_id")));
                }
            }
        } catch (SQLException e) {
            log.warn("bot_market_event read failed: {}", e.toString());
        }
        return out;
    }

    /** One item's tape rows since {@code sinceMs}, ascending — the price-history chart series. */
    public List<MarketEvent> itemHistory(int itemId, long sinceMs) {
        List<MarketEvent> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, at, kind, item_id, quality, qty, unit_price, seller_id, buyer_id, map_id"
                             + " FROM bot_market_event WHERE item_id = ? AND at >= ? ORDER BY at ASC")) {
            ps.setInt(1, itemId);
            ps.setTimestamp(2, new Timestamp(sinceMs));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EventKind kind = EventKind.fromCode(rs.getInt("kind"));
                    if (kind == null) {
                        continue;
                    }
                    out.add(new MarketEvent(
                            rs.getLong("id"),
                            rs.getTimestamp("at").getTime(),
                            kind,
                            rs.getInt("item_id"),
                            rs.getInt("quality"),
                            rs.getInt("qty"),
                            rs.getLong("unit_price"),
                            readNullableInt(rs, "seller_id"),
                            readNullableInt(rs, "buyer_id"),
                            readNullableInt(rs, "map_id")));
                }
            }
        } catch (SQLException e) {
            log.warn("bot_market_event history read failed: {}", e.toString());
        }
        return out;
    }

    /** An item with tape activity: how liquid it is and when it last moved (chart item picker). */
    public record TradedItem(int itemId, int clearings, int events, long lastAtMs, long lastUnitPrice) {}

    /** Items on the tape, most-cleared first (then most-listed), for the chart's item list. */
    public List<TradedItem> tradedItems(int limit) {
        List<TradedItem> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT item_id, SUM(kind IN (0, 1)) sales, COUNT(*) events, MAX(at) last_at,"
                             + " SUBSTRING_INDEX(GROUP_CONCAT(unit_price ORDER BY at DESC), ',', 1) last_price"
                             + " FROM bot_market_event GROUP BY item_id"
                             + " ORDER BY sales DESC, events DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TradedItem(rs.getInt("item_id"), rs.getInt("sales"), rs.getInt("events"),
                            rs.getTimestamp("last_at").getTime(), rs.getLong("last_price")));
                }
            }
        } catch (SQLException e) {
            log.warn("bot_market_event items read failed: {}", e.toString());
        }
        return out;
    }

    /** Resolve a set of character ids to names (web transaction-detail display). One batch query;
     *  ids absent from {@code characters} are simply omitted. Empty in, empty out. */
    public Map<Integer, String> charNames(java.util.Collection<Integer> ids) {
        Map<Integer, String> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        StringBuilder in = new StringBuilder();
        for (Integer id : ids) {
            if (id == null) {
                continue;
            }
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(id.intValue());
        }
        if (in.length() == 0) {
            return out;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, name FROM characters WHERE id IN (" + in + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt("id"), rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            log.warn("charNames lookup failed: {}", e.toString());
        }
        return out;
    }

    /** Snapshot of the in-memory faucet/sink tallies (category -> total meso since boot). */
    public Map<String, Long> flowSnapshot(boolean faucet) {
        Map<String, Long> out = new ConcurrentHashMap<>();
        (faucet ? faucetByCategory : sinkByCategory).forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    private final Map<String, LongAdder> faucetByCategory = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> sinkByCategory = new ConcurrentHashMap<>();

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    private static Integer readNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
