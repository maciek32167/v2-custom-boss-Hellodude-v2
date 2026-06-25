package com.hellodude.boss;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class BossListener implements Listener {

    private final HellodudeBossPlugin plugin;
    private final BossManager bossManager;

    public BossListener(HellodudeBossPlugin plugin, BossManager bossManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
    }

    // Śledzenie obrażeń zadanych bossowi
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!bossManager.isBoss(event.getEntity())) return;

        Player damager = null;

        // Bezpośrednie uderzenie
        if (event.getDamager() instanceof Player p) {
            damager = p;
        }
        // Strzała lub inny pocisk
        else if (event.getDamager() instanceof Projectile proj) {
            if (proj.getShooter() instanceof Player p) {
                damager = p;
            }
        }

        if (damager != null) {
            bossManager.onBossDamaged(damager, event.getFinalDamage());
        }
    }

    // Śmierć bossa
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!bossManager.isBoss(event.getEntity())) return;

        // Anuluj domyślne drapy
        event.getDrops().clear();
        event.setDroppedExp(0);

        bossManager.onBossDeath(event.getEntity().getLocation());
    }

    // Dodaj nowego gracza do boss bar
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        bossManager.onPlayerJoin(event.getPlayer());
    }
}
