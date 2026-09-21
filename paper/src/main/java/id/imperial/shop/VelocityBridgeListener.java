package id.imperial.shop;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Locale;

final class VelocityBridgeListener implements PluginMessageListener {
    static final String CHANNEL = "imperialshop:bridge";
    private final ImperialShopPlugin plugin;

    VelocityBridgeListener(ImperialShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || player == null) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String secret = in.readUTF();
            if (!secret.equals(plugin.networkSecret())) return;

            String command = in.readUTF();
            if (command.isBlank() || command.length() > 256) return;

            String[] parts = command.trim().split("\\s+");
            if (parts.length == 0) return;

            String root = parts[0].toLowerCase(Locale.ROOT);
            if (!switch (root) {
                case "shop", "sell", "sellgui", "sellall", "sellhand" -> true;
                default -> false;
            }) return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.performCommand(command);
            });
        } catch (Exception exception) {
            plugin.getLogger().warning("Invalid ImperialShop Velocity bridge message.");
        }
    }
}
