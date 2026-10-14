package com.rpgworldgen.persist;

import com.rpgworldgen.model.PlannedStructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * SQLite persistence for world plans, structure records, safe zones,
 * and generation progress checkpoints.
 *
 * <p>Uses a single connection guarded by synchronized methods (SQLite + one
 * writer thread model). Schema is versioned via the meta table; migrations
 * run incrementally in {@link #migrate(int)}.
 */
public class GenerationPersistenceService {

    static final int SCHEMA_VERSION = 3;

    /** A persisted structure record with its build flag. */
    public static class StructureRow {
        public final PlannedStructure structure;
        public final boolean built;
        public StructureRow(PlannedStructure structure, boolean built) {
            this.structure = structure;
            this.built = built;
        }
    }

    /** A persisted road record (coarse waypoints; refined at build time). */
    public static class RoadRow {
        public final String fromId;
        public final String toId;
        public final String theme;
        public final List<int[]> waypoints;
        public RoadRow(String fromId, String toId, String theme, List<int[]> waypoints) {
            this.fromId = fromId;
            this.toId = toId;
            this.theme = theme == null ? "" : theme;
            this.waypoints = waypoints;
        }
    }

    private final Logger log;
    private final String jdbcUrl;
    private Connection conn;

    public GenerationPersistenceService(Logger log, java.io.File dataFolder) {
        this(log, "jdbc:sqlite:" + new java.io.File(dataFolder, "rpgworldgen.db").getAbsolutePath());
    }

    /** Direct-URL constructor (used by unit tests with jdbc:sqlite::memory:). */
    public GenerationPersistenceService(Logger log, String jdbcUrl) {
        this.log = log;
        this.jdbcUrl = jdbcUrl;
    }

    public synchronized void init() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver missing", e);
        }
        conn = DriverManager.getConnection(jdbcUrl);
        int version = 0;
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
            try (ResultSet rs = s.executeQuery("SELECT v FROM meta WHERE k='schema_version'")) {
                if (rs.next()) version = Integer.parseInt(rs.getString(1));
            } catch (NumberFormatException e) {
                version = 0;
            }
        }
        migrate(version);
    }

    private void migrate(int from) throws SQLException {
        try (Statement s = conn.createStatement()) {
            if (from < 1) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS worlds ("
                    + "name TEXT PRIMARY KEY, seed INTEGER NOT NULL, size INTEGER NOT NULL, "
                    + "preset TEXT, state TEXT NOT NULL DEFAULT 'PLANNED', "
                    + "planned_json TEXT, created_at INTEGER NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS structures ("
                    + "id TEXT PRIMARY KEY, world TEXT NOT NULL, kind TEXT NOT NULL, "
                    + "name TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, "
                    + "radius INTEGER NOT NULL, tier INTEGER NOT NULL, theme TEXT, extra TEXT, "
                    + "built INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS progress ("
                    + "world TEXT PRIMARY KEY, stage TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, "
                    + "total INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS safezones ("
                    + "id TEXT PRIMARY KEY, world TEXT NOT NULL, name TEXT NOT NULL, "
                    + "cx INTEGER NOT NULL, cz INTEGER NOT NULL, radius INTEGER NOT NULL, "
                    + "flags TEXT)");
            }
            if (from < 2) {
                try {
                    s.executeUpdate("ALTER TABLE progress ADD COLUMN errors INTEGER NOT NULL DEFAULT 0");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("duplicate column")) throw e;
                }
            }
            if (from < 3) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS roads ("
                    + "world TEXT NOT NULL, idx INTEGER NOT NULL, "
                    + "from_id TEXT NOT NULL, to_id TEXT NOT NULL, theme TEXT, "
                    + "waypoints TEXT NOT NULL, "
                    + "PRIMARY KEY (world, idx))");
            }
            s.executeUpdate("INSERT OR REPLACE INTO meta (k, v) VALUES ('schema_version', '" + SCHEMA_VERSION + "')");
        }
        log.info("Database schema at version " + SCHEMA_VERSION + " (migrated from " + from + ").");
    }

    public synchronized void saveWorld(String name, long seed, int size, String preset,
                                       String state, String planJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO worlds (name, seed, size, preset, state, planned_json, created_at) "
            + "VALUES (?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET seed=excluded.seed, "
            + "size=excluded.size, preset=excluded.preset, state=excluded.state, "
            + "planned_json=excluded.planned_json")) {
            ps.setString(1, name);
            ps.setLong(2, seed);
            ps.setInt(3, size);
            ps.setString(4, preset);
            ps.setString(5, state);
            ps.setString(6, planJson);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized void updateWorldState(String name, String state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE worlds SET state=? WHERE name=?")) {
            ps.setString(1, state);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    public synchronized String loadPlanJson(String world) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT planned_json FROM worlds WHERE name=?")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public synchronized void saveStructure(String world, PlannedStructure st, boolean built) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO structures (id, world, kind, name, x, z, radius, tier, theme, extra, built) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET built=excluded.built")) {
            ps.setString(1, st.getId());
            ps.setString(2, world);
            ps.setString(3, st.getKind().name());
            ps.setString(4, st.getName());
            ps.setInt(5, st.getX());
            ps.setInt(6, st.getZ());
            ps.setInt(7, st.getRadius());
            ps.setInt(8, st.getTier());
            ps.setString(9, st.getTheme());
            ps.setString(10, st.getExtra());
            ps.setInt(11, built ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public synchronized void markBuilt(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE structures SET built=1 WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public synchronized int countBuilt(String world) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM structures WHERE world=? AND built=1")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized int countStructures(String world) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) FROM structures WHERE world=?")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized List<String> listUnbuilt(String world) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM structures WHERE world=? AND built=0 ORDER BY rowid")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Load all structure records for a world (recovery path). */
    public synchronized List<StructureRow> loadStructures(String world) throws SQLException {
        List<StructureRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, kind, name, x, z, radius, tier, theme, extra, built "
            + "FROM structures WHERE world=? ORDER BY rowid")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlannedStructure.Kind kind;
                    try {
                        kind = PlannedStructure.Kind.valueOf(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        log.warning("Skipping structure with unknown kind: " + rs.getString(1));
                        continue;
                    }
                    out.add(new StructureRow(new PlannedStructure(
                        rs.getString(1), kind, rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getString(8), rs.getString(9)), rs.getInt(10) != 0));
                }
            }
        }
        return out;
    }

    public synchronized void saveRoad(String world, int idx, String fromId, String toId,
                                      String theme, List<int[]> waypoints) throws SQLException {
        StringBuilder wps = new StringBuilder();
        for (int[] w : waypoints) {
            if (wps.length() > 0) wps.append(';');
            wps.append(w[0]).append(',').append(w[1]);
        }
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO roads (world, idx, from_id, to_id, theme, waypoints) VALUES (?,?,?,?,?,?) "
            + "ON CONFLICT(world, idx) DO UPDATE SET from_id=excluded.from_id, to_id=excluded.to_id, "
            + "theme=excluded.theme, waypoints=excluded.waypoints")) {
            ps.setString(1, world);
            ps.setInt(2, idx);
            ps.setString(3, fromId);
            ps.setString(4, toId);
            ps.setString(5, theme);
            ps.setString(6, wps.toString());
            ps.executeUpdate();
        }
    }

    /** Load persisted roads in plan order (recovery path). */
    public synchronized List<RoadRow> loadRoads(String world) throws SQLException {
        List<RoadRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT from_id, to_id, theme, waypoints FROM roads WHERE world=? ORDER BY idx")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<int[]> wps = new ArrayList<>();
                    for (String pair : rs.getString(4).split(";")) {
                        String[] xy = pair.split(",");
                        if (xy.length == 2) {
                            try {
                                wps.add(new int[]{Integer.parseInt(xy[0]), Integer.parseInt(xy[1])});
                            } catch (NumberFormatException e) {
                                log.warning("Skipping malformed road waypoint: " + pair);
                            }
                        }
                    }
                    if (wps.size() >= 2) {
                        out.add(new RoadRow(rs.getString(1), rs.getString(2), rs.getString(3), wps));
                    }
                }
            }
        }
        return out;
    }

    public synchronized void saveProgress(String world, String stage, int done, int total, int errors)
        throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO progress (world, stage, done, total, errors, updated_at) VALUES (?,?,?,?,?,?) "
            + "ON CONFLICT(world) DO UPDATE SET stage=excluded.stage, done=excluded.done, "
            + "total=excluded.total, errors=excluded.errors, updated_at=excluded.updated_at")) {
            ps.setString(1, world);
            ps.setString(2, stage);
            ps.setInt(3, done);
            ps.setInt(4, total);
            ps.setInt(5, errors);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized void saveSafeZone(String id, String world, String name,
                                          int cx, int cz, int radius, String flags) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO safezones (id, world, name, cx, cz, radius, flags) VALUES (?,?,?,?,?,?,?) "
            + "ON CONFLICT(id) DO UPDATE SET name=excluded.name, cx=excluded.cx, cz=excluded.cz, "
            + "radius=excluded.radius, flags=excluded.flags")) {
            ps.setString(1, id);
            ps.setString(2, world);
            ps.setString(3, name);
            ps.setInt(4, cx);
            ps.setInt(5, cz);
            ps.setInt(6, radius);
            ps.setString(7, flags);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteWorld(String world) throws SQLException {
        try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM structures WHERE world=?");
             PreparedStatement p2 = conn.prepareStatement("DELETE FROM progress WHERE world=?");
             PreparedStatement p3 = conn.prepareStatement("DELETE FROM safezones WHERE world=?");
             PreparedStatement p4 = conn.prepareStatement("DELETE FROM roads WHERE world=?");
             PreparedStatement p5 = conn.prepareStatement("DELETE FROM worlds WHERE name=?")) {
            for (PreparedStatement ps : new PreparedStatement[]{p1, p2, p3, p4, p5}) {
                ps.setString(1, world);
                ps.executeUpdate();
            }
        }
    }

    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warning("Error closing database: " + e.getMessage());
        }
    }
}
