package id.imperial.shop.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Plugin(
        id = "imperialshop",
        name = "ImperialShop",
        version = "2.0.0",
        authors = {"Imperial X SOL"},
        description = "ImperialShop proxy bridge for Velocity 3.x"
)
public final class ImperialShopVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, String> config = new HashMap<>();

    @Inject
    public ImperialShopVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

        if (Boolean.parseBoolean(config.getOrDefault("commands.shop", "true"))) {
            proxy.getCommandManager().register("shop", new ForwardCommand(this));
        }
        if (Boolean.parseBoolean(config.getOrDefault("commands.sell", "true"))) {
            proxy.getCommandManager().register("sell", new ForwardCommand(this));
        }
        if (Boolean.parseBoolean(config.getOrDefault("commands.sellall", "true"))) {
            proxy.getCommandManager().register("sellall", new ForwardCommand(this));
        }
        if (Boolean.parseBoolean(config.getOrDefault("commands.sellhand", "true"))) {
            proxy.getCommandManager().register("sellhand", new ForwardCommand(this));
        }

        logger.info("ImperialShop 2.0.0 Velocity bridge enabled.");
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");

            if (!Files.exists(file)) {
                Files.writeString(file, """
backend-server: "survival"
commands:
  shop: true
  sell: true
  sellall: true
  sellhand: true
messages:
  forwarded: "Membuka ImperialShop di server utama..."
  unavailable: "Server shop sedang tidak tersedia."
""");
            }

            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(":")) continue;

                int separator = trimmed.indexOf(':');
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                value = value.replaceAll("^\"|\"$", "");

                if (line.startsWith("  ")) {
                    // The file only contains one level of nested keys.
                    String section = findSection(Files.readAllLines(file), line);
                    if (section != null) config.put(section + "." + key, value);
                } else {
                    config.put(key, value);
                }
            }
        } catch (IOException exception) {
            logger.error("Could not load ImperialShop Velocity config.", exception);
        }
    }

    private String findSection(java.util.List<String> lines, String target) {
        int targetIndex = lines.indexOf(target);
        if (targetIndex < 0) return null;

        for (int i = targetIndex - 1; i >= 0; i--) {
            String value = lines.get(i).trim();
            if (!lines.get(i).startsWith("  ") && value.endsWith(":")) {
                return value.substring(0, value.length() - 1);
            }
        }
        return null;
    }

    RegisteredServer target() {
        String backend = config.getOrDefault("backend-server", "survival");
        return proxy.getServer(backend).orElse(null);
    }

    String message(String key, String fallback) {
        return config.getOrDefault("messages." + key, fallback);
    }

    static final class ForwardCommand implements SimpleCommand {
        private final ImperialShopVelocity plugin;

        ForwardCommand(ImperialShopVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Player only."));
                return;
            }

            RegisteredServer server = plugin.target();
            if (server == null) {
                player.sendMessage(Component.text(plugin.message("unavailable",
                        "Server shop sedang tidak tersedia.")));
                return;
            }

            player.createConnectionRequest(server).fireAndForget();
            player.sendMessage(Component.text(plugin.message("forwarded",
                    "Membuka ImperialShop di server utama...")));
        }
    }
}