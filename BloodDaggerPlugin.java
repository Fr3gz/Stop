package dev.blooddagger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BloodDaggerPlugin extends JavaPlugin implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int EFFECT_TICKS = 60; // refreshed every 20 ticks

    private NamespacedKey daggerKey;
    private NamespacedKey killsKey;

    /** Players currently holding the dagger and therefore buffed. */
    private final Set<UUID> buffed = new HashSet<>();
    private final Map<UUID, Long> bloodlustEnd = new HashMap<>();
    private final Map<UUID, Integer> bloodlustStacks = new HashMap<>();
    private final Map<UUID, Long> cooldownEnd = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        daggerKey = new NamespacedKey(this, "blood_dagger");
        killsKey = new NamespacedKey(this, "blood_kills");

        getServer().getPluginManager().registerEvents(new DaggerListener(this), this);
        var cmd = getCommand("blooddagger");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(buffed)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clearEffects(p);
        }
        buffed.clear();
    }

    // ------------------------------------------------------------ messages

    public void msg(CommandSender to, String mini) {
        to.sendMessage(MM.deserialize(mini));
    }

    // ------------------------------------------------------------ dagger item

    public ItemStack createDagger() {
        Material mat = Material.matchMaterial(getConfig().getString("dagger.material", "IRON_SWORD"));
        if (mat == null || !mat.isItem()) mat = Material.IRON_SWORD;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Blood Dagger", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MM.deserialize("<!italic><gray>Carry it to vanish from sight."),
                MM.deserialize("<!italic><red>3 kills: <gray>Speed + Bloodlust (Shift + Right Click)"),
                MM.deserialize("<!italic><red>5 kills: <gray>Speed II + Strength II")));
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(daggerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isDagger(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(daggerKey, PersistentDataType.BYTE);
    }

    public boolean hasDagger(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isDagger(it)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ kills / tiers

    public int getKills(Player p) {
        return p.getPersistentDataContainer().getOrDefault(killsKey, PersistentDataType.INTEGER, 0);
    }

    public void setKills(Player p, int kills) {
        p.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, Math.max(0, kills));
    }

    public int tierFor(int kills) {
        if (kills >= getConfig().getInt("kills.tier2", 5)) return 2;
        if (kills >= getConfig().getInt("kills.tier1", 3)) return 1;
        return 0;
    }

    public boolean countMobs() { return getConfig().getBoolean("kills.count-mobs", false); }
    public boolean requireDagger() { return getConfig().getBoolean("kills.require-dagger", true); }

    // ------------------------------------------------------------ effects

    /** Apply or remove buffs for one player immediately. */
    public void refresh(Player p) {
        if (hasDagger(p)) {
            applyEffects(p, tierFor(getKills(p)));
            buffed.add(p.getUniqueId());
        } else if (buffed.remove(p.getUniqueId())) {
            clearEffects(p);
        }
    }

    private void applyEffects(Player p, int tier) {
        p.addPotionEffect(effect(PotionEffectType.INVISIBILITY, 0));
        if (tier >= 1) p.addPotionEffect(effect(PotionEffectType.SPEED, tier >= 2 ? 1 : 0));
        if (tier >= 2) p.addPotionEffect(effect(PotionEffectType.STRENGTH, 1));
        if (tier < 2) p.removePotionEffect(PotionEffectType.STRENGTH);
        if (tier < 1) p.removePotionEffect(PotionEffectType.SPEED);
    }

    private PotionEffect effect(PotionEffectType type, int amp) {
        // ambient=false, particles=false, icon=true
        return new PotionEffect(type, EFFECT_TICKS, amp, false, false, true);
    }

    private void clearEffects(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.STRENGTH);
    }

    public void forget(Player p) {
        buffed.remove(p.getUniqueId());
        bloodlustEnd.remove(p.getUniqueId());
        bloodlustStacks.remove(p.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            refresh(p);

            UUID id = p.getUniqueId();
            Long end = bloodlustEnd.get(id);
            if (end != null) {
                if (now >= end) {
                    bloodlustEnd.remove(id);
                    bloodlustStacks.remove(id);
                    msg(p, "<gray>Your bloodlust fades...");
                } else {
                    int pct = (int) Math.round(bloodlustBonus(p) * 100);
                    long secs = (end - now + 999) / 1000;
                    p.sendActionBar(MM.deserialize(
                            "<dark_red><bold>BLOODLUST</bold> <red>+" + pct + "% damage <gray>(" + secs + "s)"));
                }
            }
        }
    }

    // ------------------------------------------------------------ bloodlust

    public boolean isBloodlust(Player p) {
        Long end = bloodlustEnd.get(p.getUniqueId());
        return end != null && System.currentTimeMillis() < end;
    }

    /** Bonus as a fraction, e.g. 0.09 = +9% damage. */
    public double bloodlustBonus(Player p) {
        if (!isBloodlust(p)) return 0.0;
        double per = getConfig().getDouble("bloodlust.percent-per-hit", 3.0);
        double max = getConfig().getDouble("bloodlust.max-percent", 30.0);
        int stacks = bloodlustStacks.getOrDefault(p.getUniqueId(), 0);
        return Math.min(stacks * per, max) / 100.0;
    }

    public void addBloodlustStack(Player p) {
        if (!isBloodlust(p)) return;
        double per = getConfig().getDouble("bloodlust.percent-per-hit", 3.0);
        double max = getConfig().getDouble("bloodlust.max-percent", 30.0);
        int maxStacks = (int) Math.ceil(max / per);
        bloodlustStacks.merge(p.getUniqueId(), 1, (a, b) -> Math.min(maxStacks, a + b));
    }

    public void activateBloodlust(Player p) {
        int kills = getKills(p);
        if (tierFor(kills) < 1) {
            msg(p, "<red>You need <bold>" + getConfig().getInt("kills.tier1", 3)
                    + "</bold> kills to use Bloodlust. <gray>(You have " + kills + ")");
            return;
        }
        long now = System.currentTimeMillis();
        if (isBloodlust(p)) return;

        Long cd = cooldownEnd.get(p.getUniqueId());
        if (cd != null && now < cd) {
            msg(p, "<red>Bloodlust is on cooldown for <bold>" + ((cd - now + 999) / 1000) + "s</bold>.");
            return;
        }

        long duration = getConfig().getLong("bloodlust.duration-seconds", 15) * 1000L;
        long cooldown = getConfig().getLong("bloodlust.cooldown-seconds", 45) * 1000L;
        bloodlustEnd.put(p.getUniqueId(), now + duration);
        bloodlustStacks.put(p.getUniqueId(), 0);
        cooldownEnd.put(p.getUniqueId(), now + Math.max(cooldown, duration));

        msg(p, "<dark_red><bold>BLOODLUST!</bold> <gray>Every hit you take empowers your attacks.");
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 60, 0.5, 0.9, 0.5, 0,
                new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.6f));
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blooddagger.admin")) {
            msg(sender, "<red>You don't have permission.");
            return true;
        }
        if (args.length == 0) {
            msg(sender, "<red>Usage: /bd <give [player]|kills [player]|setkills <player> <n>|reload>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> {
                Player target = args.length > 1 ? Bukkit.getPlayer(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { msg(sender, "<red>Player not found."); return true; }
                target.getInventory().addItem(createDagger()).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                refresh(target);
                msg(sender, "<green>Gave the Blood Dagger to " + target.getName() + ".");
            }
            case "kills" -> {
                Player target = args.length > 1 ? Bukkit.getPlayer(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { msg(sender, "<red>Player not found."); return true; }
                msg(sender, "<gray>" + target.getName() + " has <red>" + getKills(target) + "</red> blood kills.");
            }
            case "setkills" -> {
                if (args.length < 3) { msg(sender, "<red>Usage: /bd setkills <player> <n>"); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { msg(sender, "<red>Player not found."); return true; }
                try {
                    setKills(target, Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    msg(sender, "<red>'" + args[2] + "' is not a number.");
                    return true;
                }
                refresh(target);
                msg(sender, "<green>Set " + target.getName() + "'s kills to " + getKills(target) + ".");
            }
            case "reload" -> {
                reloadConfig();
                msg(sender, "<green>Config reloaded.");
            }
            default -> msg(sender, "<red>Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("blooddagger.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("give", "kills", "setkills", "reload"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}
