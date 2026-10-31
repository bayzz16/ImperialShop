package id.imperial.shop;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import java.sql.*;
import java.util.UUID;

/** Shared MySQL/MariaDB balances; SQL writes are atomic across backend servers. */
final class SharedEconomyStorage {
    private final ImperialShopPlugin plugin;
    private final HikariDataSource dataSource;
    private final String table;

    SharedEconomyStorage(ImperialShopPlugin plugin) throws SQLException {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        String host = cfg.getString("economy.storage.mysql.host", "127.0.0.1");
        int port = cfg.getInt("economy.storage.mysql.port", 3306);
        String database = cfg.getString("economy.storage.mysql.database", "");
        String username = cfg.getString("economy.storage.mysql.username", "");
        String password = System.getenv("IMPERIALSHOP_DB_PASSWORD");
        if (password == null || password.isBlank()) password = cfg.getString("economy.storage.mysql.password", "");
        table = cfg.getString("economy.storage.mysql.table", "imperialshop_balances");
        if (host == null || !host.matches("[A-Za-z0-9._:-]{1,253}") ||
            database == null || !database.matches("[A-Za-z0-9_]{1,64}") ||
            username == null || username.isBlank() ||
            table == null || !table.matches("[A-Za-z0-9_]{1,48}") ||
            password == null || password.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid MySQL settings or missing password. Set IMPERIALSHOP_DB_PASSWORD.");
        }
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("ImperialShop-SharedEconomy");
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=true&requireSSL=true&serverTimezone=UTC&characterEncoding=utf8");
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(Math.max(2, Math.min(10, cfg.getInt("economy.storage.mysql.pool-size", 5))));
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(3000);
        hc.setValidationTimeout(2000);
        hc.setInitializationFailTimeout(5000);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(hc);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + table +
                " (uuid CHAR(36) NOT NULL PRIMARY KEY, balance DECIMAL(20,2) NOT NULL DEFAULT 0.00, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB");
        } catch (SQLException ex) {
            dataSource.close();
            throw ex;
        }
    }

    double balance(UUID uuid) throws SQLException {
        try (Connection c=dataSource.getConnection(); PreparedStatement p=c.prepareStatement("SELECT balance FROM "+table+" WHERE uuid=?")) {
            p.setString(1, uuid.toString());
            try (ResultSet rs=p.executeQuery()) { return rs.next() ? Math.max(0,rs.getBigDecimal(1).doubleValue()) : 0.0; }
        }
    }

    void createAccount(UUID uuid) throws SQLException {
        try (Connection c=dataSource.getConnection(); PreparedStatement p=c.prepareStatement("INSERT INTO "+table+" (uuid,balance) VALUES (?,0.00) ON DUPLICATE KEY UPDATE uuid=VALUES(uuid)")) {
            p.setString(1,uuid.toString()); p.executeUpdate();
        }
    }

    double deposit(UUID uuid,double amount) throws SQLException {
        java.math.BigDecimal value=java.math.BigDecimal.valueOf(amount).setScale(2,java.math.RoundingMode.HALF_UP);
        try (Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p=c.prepareStatement("INSERT INTO "+table+" (uuid,balance) VALUES (?,0.00) ON DUPLICATE KEY UPDATE uuid=VALUES(uuid)")) {
                    p.setString(1,uuid.toString()); p.executeUpdate();
                }
                java.math.BigDecimal current;
                try (PreparedStatement p=c.prepareStatement("SELECT balance FROM "+table+" WHERE uuid=? FOR UPDATE")) {
                    p.setString(1,uuid.toString());
                    try (ResultSet rs=p.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Account disappeared during deposit.");
                        current=rs.getBigDecimal(1);
                    }
                }
                java.math.BigDecimal next=current.add(value).setScale(2,java.math.RoundingMode.HALF_UP);
                try (PreparedStatement p=c.prepareStatement("UPDATE "+table+" SET balance=? WHERE uuid=?")) {
                    p.setBigDecimal(1,next); p.setString(2,uuid.toString()); p.executeUpdate();
                }
                c.commit();
                return next.doubleValue();
            } catch (SQLException ex) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw ex;
            } finally { try { c.setAutoCommit(true); } catch (SQLException ignored) {} }
        }
    }

    double withdraw(UUID uuid,double amount) throws SQLException {
        java.math.BigDecimal value=java.math.BigDecimal.valueOf(amount).setScale(2,java.math.RoundingMode.HALF_UP);
        try (Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p=c.prepareStatement("INSERT INTO "+table+" (uuid,balance) VALUES (?,0.00) ON DUPLICATE KEY UPDATE uuid=VALUES(uuid)")) {
                    p.setString(1,uuid.toString()); p.executeUpdate();
                }
                java.math.BigDecimal current;
                try (PreparedStatement p=c.prepareStatement("SELECT balance FROM "+table+" WHERE uuid=? FOR UPDATE")) {
                    p.setString(1,uuid.toString());
                    try (ResultSet rs=p.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Account disappeared during withdrawal.");
                        current=rs.getBigDecimal(1);
                    }
                }
                if (current.compareTo(value)<0) { c.rollback(); return Double.NaN; }
                java.math.BigDecimal next=current.subtract(value).setScale(2,java.math.RoundingMode.HALF_UP);
                try (PreparedStatement p=c.prepareStatement("UPDATE "+table+" SET balance=? WHERE uuid=?")) {
                    p.setBigDecimal(1,next); p.setString(2,uuid.toString()); p.executeUpdate();
                }
                c.commit();
                return next.doubleValue();
            } catch (SQLException ex) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw ex;
            } finally { try { c.setAutoCommit(true); } catch (SQLException ignored) {} }
        }
    }

    void close() { if (!dataSource.isClosed()) dataSource.close(); }
}