package server.bots;

import client.BotClient;
import client.DefaultDates;
import client.creator.BotCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.BCrypt;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * Creates server-owned bot characters: resolves/creates the backing account (name == character name,
 * password {@code botbot}) and runs {@link BotCreator}. Extracted from {@code SpawnBotCommand} so both
 * the {@code @spawnbot} command and the population auto-generator share ONE creation path (rule #1/#6).
 *
 * <p>{@link #generateManaged} is the living-server inflow: a procedurally-named, managed (schedulable)
 * bot with a persisted {@link BotPersonality}, honoring the hardcore cap so the never-retiring set stays
 * small. Marking a row in {@code managed_bot} is the ONLY thing that makes a character schedulable.
 */
public final class BotGenerator {
    private static final Logger log = LoggerFactory.getLogger(BotGenerator.class);
    private static final int MAX_NAME_ATTEMPTS = 5;
    // BCrypt at cost 12 is ~250ms — hashing "botbot" per generated bot was THE cost in population
    // autogen (everything else is random rolls + a few inserts). Bots all share this throwaway
    // password, so one shared hash is fine (per-account salt buys nothing for server-owned bots) and
    // turns generation into near-pure inserts, which is what makes batch fill cheap.
    private static final String BOT_PASSWORD_HASH = BCrypt.hashpw("botbot", BCrypt.gensalt(12));

    private BotGenerator() {}

    /** Outcome of a creation attempt: a new character id, or a human-readable failure reason. */
    public record Result(int charId, String error) {
        public boolean ok() {
            return charId > 0;
        }

        static Result ok(int charId) {
            return new Result(charId, null);
        }

        static Result fail(String error) {
            return new Result(-1, error);
        }
    }

    /**
     * Create a bot character named {@code name} on its own account (created if absent, reused if it
     * exists but is empty). Pure creation — does NOT register ownership or mark the bot managed.
     */
    public static Result createBotCharacter(int world, int channel, String name) {
        Integer accountId;
        try {
            accountId = resolveBotAccount(name);
        } catch (SQLException e) {
            log.warn("bot account resolve failed for '{}'", name, e);
            return Result.fail("Failed to create or reuse the bot account for '" + name + "'.");
        }
        if (accountId == null) {
            return Result.fail("Account '" + name + "' already exists and still has characters, so it cannot be reused.");
        }

        BotClient creationClient = new BotClient(world, channel);
        creationClient.setAccID(accountId);
        creationClient.setAccountName(name);

        int charId = BotCreator.createCharacter(creationClient, name);
        if (charId == -1) {
            return Result.fail("Failed to create bot character '" + name + "'. Name may be invalid or already taken.");
        }
        return Result.ok(charId);
    }

    /**
     * Generate a fresh, procedurally-named MANAGED bot (schedulable by the population scheduler): rolls a
     * name (retrying on collision), creates the character, persists a personality (re-rolled off HARDCORE
     * if already at {@code cap}), and inserts the {@code managed_bot} row. Returns the new char id or -1.
     */
    public static int generateManaged(int world, int channel, int currentHardcore, int cap) {
        // Plan the whole 1st->2nd job arc up front (reusing the same weighted/uniform pickers the
        // autopilot uses), so the procedural name is flavored to the class the bot will actually become.
        client.Job firstJob = BotBuildManager.pickWeightedJob(client.Job.BEGINNER);
        client.Job secondJob = firstJob == null ? null : BotBuildManager.pickWeightedJob(firstJob);
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String name = firstJob == null ? BotNameGenerator.generate()
                    : BotNameGenerator.generate(firstJob, secondJob);
            Result r = createBotCharacter(world, channel, name);
            if (r.ok()) {
                int charId = r.charId();
                persistPersonality(charId, currentHardcore, cap, firstJob, secondJob);
                ManagedBotService.getInstance().insert(charId, null);
                log.info("auto-generated managed bot charId={}", charId);
                return charId;
            }
        }
        log.warn("auto-generation gave up after {} name attempts", MAX_NAME_ATTEMPTS);
        return -1;
    }

    /** Non-retired managed bots whose personality is hardcore (the veteran set the cap limits). */
    public static int countHardcore(List<ManagedBotService.ManagedBot> managed) {
        int n = 0;
        for (ManagedBotService.ManagedBot m : managed) {
            if (m.retired()) {
                continue;
            }
            String blob = BotConfigService.getInstance().load(m.botCharId());
            if (blob != null && BotPersonality.parse(blob).isHardcore()) {
                n++;
            }
        }
        return n;
    }

    private static void persistPersonality(int charId, int currentHardcore, int cap,
                                           client.Job firstJob, client.Job secondJob) {
        BotPersonality p = BotPersonality.random(charId);
        for (int guard = 1; guard <= 8 && p.isHardcore()
                && !BotScheduleMath.hardcoreAllowed(currentHardcore, cap); guard++) {
            p = BotPersonality.random(charId + guard); // re-roll to a finite-career personality
        }
        p = p.withPlannedJobs(firstJob, secondJob); // keep the name-tied job plan across any re-roll
        try {
            BotConfigService.getInstance().save(charId, p.serialize());
        } catch (RuntimeException e) {
            log.warn("failed to persist personality for generated bot {}", charId, e);
        }
    }

    // ---- account resolution (moved verbatim from SpawnBotCommand) ----

    /** Returns an account id to create the character on (new or existing-empty), or null if the name's
     *  account already exists with characters (cannot be reused). */
    private static Integer resolveBotAccount(String name) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            Integer existing = findAccountId(con, name);
            if (existing != null) {
                return countCharactersOnAccount(con, existing) == 0 ? existing : null;
            }
            int created = createBotAccount(con, name);
            return created > 0 ? created : null;
        }
    }

    private static Integer findAccountId(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM accounts WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        }
        return null;
    }

    private static int countCharactersOnAccount(Connection con, int accountId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) AS rowcount FROM characters WHERE accountid = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("rowcount");
                }
            }
        }
        return 0;
    }

    private static int createBotAccount(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO accounts (name, password, birthday, tempban) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, BOT_PASSWORD_HASH);
            ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
            ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }
}
