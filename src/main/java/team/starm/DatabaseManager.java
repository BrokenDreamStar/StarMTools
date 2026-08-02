package team.starm;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DatabaseManager {

    private final StarMTools plugin;
    private Connection connection;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> joinedBeforeCache = new ConcurrentHashMap<>();

    public DatabaseManager(StarMTools plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            connection = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "starmtools.db").getAbsolutePath()
            );
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS fly_states (
                        uuid VARCHAR(36) PRIMARY KEY,
                        flying BOOLEAN NOT NULL DEFAULT 0
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_data (
                        uuid VARCHAR(36) PRIMARY KEY,
                        joined_before BOOLEAN NOT NULL DEFAULT 0
                    )
                """);
            }
            plugin.getLogger().info("数据库连接成功");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize SQLite", e);
        }
    }

    public Boolean getFlyState(UUID uuid) {
        Boolean cached = cache.get(uuid);
        if (cached != null) return cached;
        if (connection == null) return null;
        String sql = "SELECT flying FROM fly_states WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                boolean value = rs.getBoolean("flying");
                cache.put(uuid, value);
                return value;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get fly state for " + uuid, e);
        }
        return null;
    }

    public void setFlyState(UUID uuid, boolean flying) {
        cache.put(uuid, flying);
        if (connection == null) return;
        String sql = "INSERT OR REPLACE INTO fly_states (uuid, flying) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, flying);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set fly state for " + uuid, e);
        }
    }

    public boolean isFirstJoin(UUID uuid) {
        if (joinedBeforeCache.getOrDefault(uuid, false)) return false;
        if (connection == null) return true;
        String sql = "SELECT joined_before FROM player_data WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            boolean joinedBefore = rs.next() && rs.getBoolean("joined_before");
            if (joinedBefore) {
                joinedBeforeCache.put(uuid, true);
                return false;
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check first join for " + uuid, e);
            return true;
        }
    }

    public void markJoined(UUID uuid) {
        joinedBeforeCache.put(uuid, true);
        if (connection == null) return;
        String sql = "INSERT OR REPLACE INTO player_data (uuid, joined_before) VALUES (?, 1)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to mark joined for " + uuid, e);
        }
    }

    public void removeFromCache(UUID uuid) {
        cache.remove(uuid);
        joinedBeforeCache.remove(uuid);
    }

    public void close() {
        cache.clear();
        joinedBeforeCache.clear();
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to close database", e);
            }
        }
    }
}
