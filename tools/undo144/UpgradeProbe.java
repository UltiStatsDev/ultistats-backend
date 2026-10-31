import org.flywaydb.core.Flyway;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Real PostgreSQL V7 -> V8 -> V9 data-preserving upgrade probe; no application runtime mutation. */
class UpgradeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the exact checked-out src/main/resources/db/migration directory");
        Path migrations = Path.of(args[0]).toAbsolutePath();
        try (var files = Files.list(migrations)) {
            if (files.noneMatch(p -> p.getFileName().toString().startsWith("V8__"))) {
                throw new IllegalStateException("V8 is absent: synchronize actual master after the independent scoreboard merge; do not claim complete upgrade history");
            }
        }
        String database = "undo144_upgrade_" + UUID.randomUUID().toString().substring(0, 8);
        String prefix = "jdbc:postgresql://127.0.0.1:19444/";
        String user = "undo144", password = "undo144-synthetic";
        // Only this task's reserved synthetic container, and a newly generated own database.
        try (var admin = DriverManager.getConnection(prefix + "postgres", user, password);
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " OWNER undo144");
        }
        String url = prefix + database;
        Flyway.configure().dataSource(url, user, password).locations("filesystem:" + migrations).target("7").load().migrate();
        UUID match = UUID.randomUUID(), home = UUID.randomUUID(), away = UUID.randomUUID();
        UUID participant = UUID.randomUUID(), event = UUID.randomUUID();
        try (var db = DriverManager.getConnection(url, user, password); var sql = db.createStatement()) {
            sql.execute("INSERT INTO teams(id,name) VALUES ('" + home + "','Undo144 upgrade home'),('" + away + "','Undo144 upgrade away')");
            sql.execute("INSERT INTO matches(id,players_per_team,started_at,event_log_revision) VALUES ('" + match + "',2,'2026-10-24T10:00:00Z',1)");
            sql.execute("INSERT INTO match_teams(match_id,team_id,team_name,position,score) VALUES ('" + match + "','" + home + "','Home',1,0),('" + match + "','" + away + "','Away',2,0)");
            sql.execute("INSERT INTO match_participants(match_id,participant_id,team_id,kind,unknown_slot) VALUES ('" + match + "','" + participant + "','" + away + "','UNKNOWN',1)");
            sql.execute("INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,from_participant_id) VALUES ('" + event + "','" + match + "',1,'PULL','2026-10-24T10:00:00Z','" + participant + "')");
        }
        Flyway latest = Flyway.configure().dataSource(url, user, password).locations("filesystem:" + migrations).load();
        latest.migrate();
        List<String> versions = Arrays.stream(latest.info().applied()).map(i -> i.getVersion().toString()).toList();
        if (!versions.equals(List.of("1", "1.1", "2", "3", "4", "5", "6", "7", "8", "9"))) {
            throw new AssertionError("Unexpected/incomplete ordered history: " + versions);
        }
        try (var db = DriverManager.getConnection(url, user, password); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT e.id,e.sequence_number,e.event_type,e.deleted_at,m.event_log_revision,mt.score FROM events e JOIN matches m ON e.match_id=m.id JOIN match_teams mt ON mt.match_id=m.id WHERE e.id='" + event + "' ORDER BY mt.position")) {
                int count = 0;
                while (rows.next()) {
                    if (!rows.getObject(1).equals(event) || rows.getInt(2) != 1 || !rows.getString(3).equals("PULL") || rows.getObject(4) != null || rows.getLong(5) != 1 || rows.getInt(6) != 0) {
                        throw new AssertionError("Pre-upgrade event/revision/score changed");
                    }
                    count++;
                }
                if (count != 2) throw new AssertionError("Pre-upgrade match teams/events missing");
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM undo_command_receipts")) {
                rows.next(); if (rows.getInt(1) != 0) throw new AssertionError("Migration fabricated command receipts");
            }
        }
        System.out.println("PASS ordered Flyway " + versions + "; V7 seeded UNKNOWN/PULL/match/revision/score preserved through V8/V9; database=" + database + "; matchId=" + match + "; eventId=" + event);
    }
}
