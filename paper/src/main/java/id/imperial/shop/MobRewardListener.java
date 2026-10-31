package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard-economy combat rewards: a player only earns a small reward for a configured
 * mob if that same mob hit the player during the fight. One-shot/safe kills pay zero.
 */
final class MobRewardListener implements Listener {
    private final ImperialShopPlugin plugin;
    private final Economy economy;
    private final Map<UUID, Set<UUID>> playerAttackersByMob = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> playersHitByMob = new ConcurrentHashMap<>();

    MobRewardListener(ImperialShopPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();

        // Track players who actually participated in damaging this mob.
        if (victim instanceof LivingEntity && !(victim instanceof Player)) {
            Player player = resolvePlayer(damager);
            if (player != null) {
                playerAttackersByMob.computeIfAbsent(victim.getUniqueId(), key -> ConcurrentHashMap.newKeySet())
                        .add(player.getUniqueId());
            }
        }

        // A reward requires the same mob to hit the player after that player engaged it.
        if (victim instanceof Player player && damager instanceof LivingEntity && !(damager instanceof Player)) {
            Set<UUID> attackers = playerAttackersByMob.get(damager.getUniqueId());
            if (attackers != null && attackers.contains(player.getUniqueId())) {
                playersHitByMob.computeIfAbsent(damager.getUniqueId(), key -> ConcurrentHashMap.newKeySet())
                        .add(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        Player killer = mob.getKiller();
        UUID mobId = mob.getUniqueId();
        Set<UUID> hitPlayers = playersHitByMob.remove(mobId);
        Set<UUID> attackers = playerAttackersByMob.remove(mobId);

        if (killer == null || hitPlayers == null || attackers == null) return;
        UUID playerId = killer.getUniqueId();

        // No pay for instant kills, ranged one-shots, traps, or kills where this mob
        // never hit the player who killed it.
        if (!attackers.contains(playerId) || !hitPlayers.contains(playerId)) return;

        String path = "mob-rewards." + mob.getType().name();
        if (!plugin.getConfig().isSet(path)) return; // only explicitly configured mobs pay
        double reward = plugin.getConfig().getDouble(path, 0.0);
        if (!Double.isFinite(reward) || reward <= 0) return;

        var response = economy.depositPlayer(killer, reward);
        if (response.transactionSuccess()) {
            String message = plugin.getConfig().getString(
                    "messages.mob-reward", "&a+%amount% &7karena menang melawan &f%mob%");
            message = message.replace("%amount%", economy.format(reward))
                    .replace("%mob%", mob.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
            killer.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            plugin.getLogger().warning("Could not pay mob reward to " + killer.getName() + ": " + response.errorMessage);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerAttackersByMob.values().forEach(set -> set.remove(playerId));
        playersHitByMob.values().forEach(set -> set.remove(playerId));
    }

    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }
}
