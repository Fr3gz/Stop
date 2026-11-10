package dev.blooddagger;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.EquipmentSlot;
import org.bukkit.Sound;

public final class DaggerListener implements Listener {

    private final BloodDaggerPlugin plugin;

    public DaggerListener(BloodDaggerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.refresh(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.refresh(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.forget(e.getPlayer());
    }

    /** Shift + Right Click with the dagger = Bloodlust. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        if (!plugin.isDagger(e.getItem())) return;
        plugin.activateBloodlust(p);
    }

    /** Kill counting. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        if (victim instanceof ArmorStand) return;
        if (!(victim instanceof Player) && !plugin.countMobs()) return;
        if (plugin.requireDagger() && !plugin.hasDagger(killer)) return;

        int before = plugin.tierFor(plugin.getKills(killer));
        int kills = plugin.getKills(killer) + 1;
        plugin.setKills(killer, kills);
        int after = plugin.tierFor(kills);

        plugin.msg(killer, "<dark_red>Blood Dagger: <red>" + kills + " kill" + (kills == 1 ? "" : "s"));
        if (after > before) {
            killer.playSound(killer.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1.2f);
            if (after == 1) {
                plugin.msg(killer, "<dark_red><bold>The dagger awakens!</bold> <gray>Permanent Speed and the "
                        + "<red>Bloodlust</red> ability (Shift + Right Click) unlocked.");
            } else {
                plugin.msg(killer, "<dark_red><bold>The dagger is fully awakened!</bold> "
                        + "<gray>Speed II and Strength II unlocked.");
            }
        }
        plugin.refresh(killer);
    }

    /** Taking damage while in Bloodlust builds a stack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getFinalDamage() <= 0) return;
        plugin.addBloodlustStack(p);
    }

    /** Bloodlust bonus damage on attacks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player p)) return;
        double bonus = plugin.bloodlustBonus(p);
        if (bonus <= 0) return;
        e.setDamage(e.getDamage() * (1.0 + bonus));
    }
}
