package com.hellodude.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Logger;

public class BossManager {

    private final HellodudeBossPlugin plugin;
    private final Logger log;
    private final NamespacedKey bossKey;
    private final Random random = new Random();

    // Stan aktywnego bossa
    private Ravager activeBoss = null;
    private BossBar bossBar = null;
    private double maxBossHealth = 800.0;

    // Śledzenie obrażeń graczy (UUID -> suma obrażeń)
    private final Map<UUID, Double> damageMap = new HashMap<>();

    // Które progi HP już wyzwoliły miniony (600, 400, 200)
    private final Set<Integer> triggeredPhases = new HashSet<>();

    // ID tasków schedulera
    private int coordinateTaskId = -1;
    private int topDamagerTaskId = -1;
    private int actionBarTaskId = -1;
    private int pullTaskId = -1;
    private int stuckCheckTaskId = -1;

    // Śledzenie "zablokowania" bossa
    private Location lastBossLocation = null;
    private Player currentTarget = null;
    private int stuckTicks = 0;

    // Wpisy lootowe z configu
    private final List<LootEntry> lootEntries = new ArrayList<>();

    public BossManager(HellodudeBossPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.bossKey = new NamespacedKey(plugin, "hellodude_boss");
        loadLoot();
    }

    // ================================================
    //   Wczytanie loot table z configu
    // ================================================

    @SuppressWarnings("unchecked")
    public void loadLoot() {
        lootEntries.clear();
        var list = plugin.getConfig().getMapList("boss.loot");
        for (var rawMap : list) {
            try {
                Map<String, Object> map = (Map<String, Object>) rawMap;
                String type = (String) map.get("type");
                int amount = ((Number) map.getOrDefault("amount", 1)).intValue();
                double weight = ((Number) map.getOrDefault("weight", 1.0)).doubleValue();

                Map<String, Integer> enchants = null;
                if (map.containsKey("enchantments")) {
                    Map<String, Object> rawEnchants = (Map<String, Object>) map.get("enchantments");
                    enchants = new HashMap<>();
                    for (var e : rawEnchants.entrySet()) {
                        enchants.put(e.getKey(), ((Number) e.getValue()).intValue());
                    }
                }

                String trimMat = (String) map.getOrDefault("trim_material", null);
                String trimPat = (String) map.getOrDefault("trim_pattern", null);

                lootEntries.add(new LootEntry(type, amount, weight, enchants, trimMat, trimPat));
            } catch (Exception e) {
                log.warning("Blad parsowania loot entry: " + e.getMessage());
            }
        }
        log.info("Wczytano " + lootEntries.size() + " wpisow lootowych.");
    }

    // ================================================
    //   Spawn bossa
    // ================================================

    public void spawnBoss(Location location) {
        // Usuń istniejącego bossa jeśli żyje
        if (activeBoss != null && !activeBoss.isDead()) {
            activeBoss.remove();
            cleanup();
        }

        World world = location.getWorld();
        if (world == null) {
            log.warning("Swiat jest null - nie mozna spawnowac bossa!");
            return;
        }

        // Wczytaj parametry z configu
        maxBossHealth = plugin.getConfig().getDouble("boss.health", 800.0);
        double scale = plugin.getConfig().getDouble("boss.scale", 1.5);
        String bossName = plugin.getConfig().getString("boss.name", "Hellodude's Dad");

        // Spawn Ravager
        Ravager ravager = (Ravager) world.spawnEntity(location, EntityType.RAVAGER);

        // Nazwa
        ravager.customName(Component.text(bossName, NamedTextColor.AQUA));
        ravager.setCustomNameVisible(true);

        // Maksymalne HP
        setMaxHealth(ravager, maxBossHealth);
        ravager.setHealth(maxBossHealth);

        // Rozmiar 1.5x
        setScale(ravager, scale);

        // Nie despawnuje się sam
        ravager.setRemoveWhenFarAway(false);

        // === Efekty permanentne ===
        // Strength VI (amplifier 5 = poziom 6)
        addPermanentEffect(ravager, PotionEffectType.STRENGTH, 5);
        // Speed III (amplifier 2 = poziom 3)
        addPermanentEffect(ravager, PotionEffectType.SPEED, 2);
        // Fire Resistance I
        addPermanentEffect(ravager, PotionEffectType.FIRE_RESISTANCE, 0);
        // Resistance II (amplifier 1 = poziom 2)
        addPermanentEffect(ravager, PotionEffectType.RESISTANCE, 1);
        // Regeneration II (amplifier 1 = poziom 2)
        addPermanentEffect(ravager, PotionEffectType.REGENERATION, 1);

        // Oznacz jako naszego bossa
        ravager.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, "HellodudesDad");

        // === Zasięg wyczuwania graczy: 105 bloków ===
        try {
            AttributeInstance followRange = ravager.getAttribute(Attribute.GENERIC_FOLLOW_RANGE);
            if (followRange != null) followRange.setBaseValue(105.0);
        } catch (Exception e) {
            log.warning("Blad ustawiania follow range: " + e.getMessage());
        }

        // === Boss Bar ===
        bossBar = Bukkit.createBossBar(bossName, BarColor.RED, BarStyle.SOLID);
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
        }
        bossBar.setVisible(true);

        activeBoss = ravager;
        damageMap.clear();
        triggeredPhases.clear();
        lastBossLocation = null;
        currentTarget = null;
        stuckTicks = 0;

        // Wiadomość spawn z koordynatami
        broadcastCoordinates();

        // Uruchom timery
        startTimers();

        log.info("Hellodude's Dad zostal zspawnowany na " + location);
    }

    // ================================================
    //   Ustawianie atrybutów
    // ================================================

    private void setMaxHealth(LivingEntity entity, double health) {
        try {
            AttributeInstance attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(health);
        } catch (Exception e) {
            log.warning("Blad ustawiania max HP: " + e.getMessage());
        }
    }

    private void setScale(LivingEntity entity, double scale) {
        // Próbuje GENERIC_SCALE, potem SCALE (różne wersje API)
        boolean set = false;
        for (String attrName : new String[]{"GENERIC_SCALE", "SCALE"}) {
            try {
                Attribute attr = Attribute.valueOf(attrName);
                AttributeInstance inst = entity.getAttribute(attr);
                if (inst != null) {
                    inst.setBaseValue(scale);
                    set = true;
                    break;
                }
            } catch (IllegalArgumentException ignored) {}
        }
        if (!set) {
            log.warning("Nie mozna ustawic skali - atrybut niedostepny w tej wersji.");
        }
    }

    private void addPermanentEffect(LivingEntity entity, PotionEffectType type, int amplifier) {
        entity.addPotionEffect(new PotionEffect(type, Integer.MAX_VALUE, amplifier, false, false, false));
    }

    // ================================================
    //   Timery i wiadomości
    // ================================================

    private void broadcastCoordinates() {
        if (activeBoss == null || activeBoss.isDead()) return;
        Location loc = activeBoss.getLocation();
        Component msg = Component.text(
            "Hellodude's dad is at " + (int)loc.getX() + ", " + (int)loc.getY() + ", " + (int)loc.getZ(),
            NamedTextColor.AQUA
        );
        Bukkit.getServer().broadcast(msg);
    }

    private void announceTopDamager() {
        if (damageMap.isEmpty()) return;
        var entry = damageMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElse(null);
        if (entry == null) return;

        Player topPlayer = Bukkit.getPlayer(entry.getKey());
        String name = topPlayer != null ? topPlayer.getName() : "Unknown";
        int dmg = entry.getValue().intValue();

        Bukkit.getServer().broadcast(
            Component.text("[Boss] " + name + " is dealing the most damage! (" + dmg + " HP)", NamedTextColor.YELLOW)
        );
    }

    private void updateActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int dmg = damageMap.getOrDefault(player.getUniqueId(), 0.0).intValue();
            player.sendActionBar(
                Component.text("Damage dealt to the boss: " + dmg, NamedTextColor.YELLOW)
            );
        }
    }

    private void updateBossBar() {
        if (activeBoss == null || bossBar == null) return;
        double progress = Math.max(0.0, Math.min(1.0, activeBoss.getHealth() / maxBossHealth));
        bossBar.setProgress(progress);
    }

    private void startTimers() {
        // Koordynaty co 5 minut (6000 ticków)
        coordinateTaskId = new BukkitRunnable() {
            @Override public void run() {
                if (activeBoss == null || activeBoss.isDead()) { cancel(); return; }
                broadcastCoordinates();
            }
        }.runTaskTimer(plugin, 6000L, 6000L).getTaskId();

        // Top damager co 2 minuty (2400 ticków)
        topDamagerTaskId = new BukkitRunnable() {
            @Override public void run() {
                if (activeBoss == null || activeBoss.isDead()) { cancel(); return; }
                announceTopDamager();
            }
        }.runTaskTimer(plugin, 2400L, 2400L).getTaskId();

        // Przyciąganie graczy co 30 sekund (600 ticków)
        pullTaskId = new BukkitRunnable() {
            @Override public void run() {
                if (activeBoss == null || activeBoss.isDead()) { cancel(); return; }
                startPulling();
            }
        }.runTaskTimer(plugin, 600L, 600L).getTaskId();
        actionBarTaskId = new BukkitRunnable() {
            @Override public void run() {
                if (activeBoss == null || activeBoss.isDead()) { cancel(); return; }
                updateActionBars();
                updateBossBar();
            }
        }.runTaskTimer(plugin, 0L, 20L).getTaskId();

        // Sprawdzanie czy boss jest zablokowany — co 1 sekundę (20 ticków)
        stuckCheckTaskId = new BukkitRunnable() {
            @Override public void run() {
                if (activeBoss == null || activeBoss.isDead()) { cancel(); return; }
                checkStuck();
            }
        }.runTaskTimer(plugin, 20L, 20L).getTaskId();
    }

    // ================================================
    //   Obsługa obrażeń i faz
    // ================================================

    public void onBossDamaged(Player damager, double damage) {
        if (damager == null) return;
        damageMap.merge(damager.getUniqueId(), damage, Double::sum);

        // Sprawdzenie progów HP (opóźnione o 1 tick żeby HP zdążyło spaść)
        // Boss ma 1000 HP, miniony respią się co 150 HP straty
        // Progi: 850, 700, 550, 400, 250, 100
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (activeBoss == null || activeBoss.isDead()) return;
            double hp = activeBoss.getHealth();
            for (int threshold : new int[]{850, 700, 550, 400, 250, 100}) {
                if (hp <= threshold && !triggeredPhases.contains(threshold)) {
                    triggeredPhases.add(threshold);
                    spawnMinions(activeBoss.getLocation());
                    // Przy 250 HP dodatkowo 2 duże vindicatory
                    if (threshold == 250) {
                        spawnSpecialVindicators(activeBoss.getLocation());
                    }
                }
            }
        });
    }

    // ================================================
    //   Wykrywanie zablokowania bossa i eksplozja
    // ================================================

    private void checkStuck() {
        if (activeBoss == null || activeBoss.isDead()) return;

        Location bossLoc = activeBoss.getLocation();

        // Znajdź najbliższego gracza w zasięgu
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(bossLoc.getWorld())) continue;
            double dist = p.getLocation().distance(bossLoc);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = p;
            }
        }

        // Jeśli nie ma nikogo w pobliżu — reset
        if (nearest == null || nearestDist > 105) {
            stuckTicks = 0;
            lastBossLocation = null;
            currentTarget = null;
            return;
        }

        // Sprawdź czy boss jest aktywnie "goniony" i stoi w miejscu
        // Boss jest zablokowany jeśli: jest gracz w pobliżu, ale boss nie ruszył się >0.5 bloka
        if (lastBossLocation != null && lastBossLocation.getWorld().equals(bossLoc.getWorld())) {
            double moved = lastBossLocation.distance(bossLoc);
            if (moved < 0.5 && nearestDist > 2.0) {
                // Boss stoi w miejscu, a gracz jest dalej niż 2 bloki — potencjalnie zablokowany
                stuckTicks++;
            } else {
                // Boss się ruszył — reset licznika
                stuckTicks = 0;
            }
        }

        lastBossLocation = bossLoc.clone();
        currentTarget = nearest;

        // 10 sekund = 10 ticków (sprawdzamy co sekundę)
        if (stuckTicks >= 10) {
            stuckTicks = 0;
            triggerObsidianBreak(bossLoc);
        }
    }

    private void triggerObsidianBreak(Location bossLoc) {
        World world = bossLoc.getWorld();
        if (world == null) return;

        Bukkit.getServer().broadcast(
            Component.text("⚠ Hellodude's Dad is breaking through!", NamedTextColor.DARK_RED)
        );

        // Eksplozja w miejscu bossa która niszczy nawet obsydian
        // Używamy createExplosion z flagami: breakBlocks=true, setFire=false, source=boss
        // Moc 10 niszczy obsydian (normalnie odporna — musimy użyć setBlock)
        // Paper API: world.createExplosion niszczy bloki zgodnie z mocą
        // Obsydian ma blast resistance 1200, więc ręcznie usuwamy bloki w promieniu

        int radius = 3;
        int bx = bossLoc.getBlockX();
        int by = bossLoc.getBlockY();
        int bz = bossLoc.getBlockZ();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x*x + y*y + z*z > radius*radius) continue; // sfera
                    org.bukkit.block.Block block = world.getBlockAt(bx+x, by+y, bz+z);
                    Material mat = block.getType();
                    // Zniszcz wszystko oprócz powietrza i bedrocka
                    if (mat != Material.AIR && mat != Material.BEDROCK && mat != Material.VOID_AIR && mat != Material.CAVE_AIR) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }

        // Efekt wizualny eksplozji
        world.createExplosion(bossLoc, 5.0f, false, false);
    }

    // ================================================
    //   Przyciąganie graczy
    // ================================================

    private void startPulling() {
        if (activeBoss == null || activeBoss.isDead()) return;

        // Ostrzeżenie dla graczy
        Bukkit.getServer().broadcast(
            Component.text("⚠ Hellodude's Dad is pulling you in!", NamedTextColor.DARK_RED)
        );

        // Przyciągaj co 2 ticki przez 3 sekundy (30 ticków = 15 razy)
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (activeBoss == null || activeBoss.isDead() || ticks >= 30) {
                    cancel();
                    return;
                }
                ticks += 2;

                Location bossLoc = activeBoss.getLocation();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    double distance = player.getLocation().distance(bossLoc);

                    // Tylko gracze w promieniu 10 kratek
                    if (distance > 10) continue;
                    if (distance < 1) continue; // Już przy bossie

                    // Kierunek w stronę bossa
                    org.bukkit.util.Vector direction = bossLoc.toVector()
                        .subtract(player.getLocation().toVector())
                        .normalize()
                        .multiply(0.4); // Siła przyciągania

                    // Zachowaj trochę pionowego ruchu żeby nie ciągnęło w ziemię
                    direction.setY(Math.max(direction.getY(), 0.1));

                    player.setVelocity(direction);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ================================================
    //   Spawn minionów
    // ================================================

    private void spawnSpecialVindicators(Location location) {
        World world = location.getWorld();
        if (world == null) return;

        Bukkit.getServer().broadcast(
            Component.text("⚠ Hellodude's Dad summons his elite guards!", NamedTextColor.DARK_RED)
        );

        for (int i = 0; i < 2; i++) {
            Location loc = location.clone().add(
                random.nextInt(7) - 3,
                0,
                random.nextInt(7) - 3
            );
            LivingEntity guard = (LivingEntity) world.spawnEntity(loc, EntityType.VINDICATOR);

            // 70 HP
            setMaxHealth(guard, 70.0);
            guard.setHealth(70.0);

            // Rozmiar 1.2x
            setScale(guard, 1.2);

            // Efekty
            addPermanentEffect(guard, PotionEffectType.STRENGTH, 2);    // Strength III
            addPermanentEffect(guard, PotionEffectType.RESISTANCE, 1);  // Resistance II
            addPermanentEffect(guard, PotionEffectType.SPEED, 0);       // Speed I
            addPermanentEffect(guard, PotionEffectType.FIRE_RESISTANCE, 0); // Fire Resistance I

            guard.customName(Component.text("Minion", NamedTextColor.DARK_RED));
            guard.setCustomNameVisible(true);

            // Diamentowa siekiera z Sharpness 2 — bez dropu
            giveAxeNoDrops(guard);
        }
    }

    private void giveAxeNoDrops(LivingEntity entity) {
        try {
            ItemStack axe = new ItemStack(Material.DIAMOND_AXE);
            ItemMeta meta = axe.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.SHARPNESS, 2, true);
                axe.setItemMeta(meta);
            }
            EntityEquipment eq = entity.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(axe);
                eq.setItemInMainHandDropChance(0.0f); // Nie wypada po śmierci
            }
        } catch (Exception e) {
            log.warning("Blad wyposazania minionow w siekiery: " + e.getMessage());
        }
    }

    private void spawnMinions(Location location) {
        World world = location.getWorld();
        if (world == null) return;

        Bukkit.getServer().broadcast(
            Component.text("⚠ Hellodude's Dad calls for reinforcements!", NamedTextColor.RED)
        );

        // 6 Vindicatorów
        for (int i = 0; i < 6; i++) spawnMinion(world, location, EntityType.VINDICATOR);
        // 4 Pillagerzy
        for (int i = 0; i < 4; i++) spawnMinion(world, location, EntityType.PILLAGER);
    }

    private void spawnMinion(World world, Location base, EntityType type) {
        // Rozrzuć minionów losowo wokół bossa
        Location loc = base.clone().add(
            random.nextInt(7) - 3,
            0,
            random.nextInt(7) - 3
        );
        LivingEntity minion = (LivingEntity) world.spawnEntity(loc, type);
        setMaxHealth(minion, 35.0);
        minion.setHealth(35.0);
        // Strength III (amplifier 2 = poziom 3)
        addPermanentEffect(minion, PotionEffectType.STRENGTH, 2);
        // Resistance II (amplifier 1 = poziom 2)
        addPermanentEffect(minion, PotionEffectType.RESISTANCE, 1);
        minion.customName(Component.text("Minion", NamedTextColor.RED));
        minion.setCustomNameVisible(true);

        // Diamentowa siekiera z Sharpness 2 — bez dropu
        if (type == EntityType.VINDICATOR) {
            giveAxeNoDrops(minion);
        }
    }

    // ================================================
    //   Śmierć bossa
    // ================================================

    public void onBossDeath(Location deathLocation) {
        // Znajdź gracza z największymi obrażeniami
        UUID topUUID = damageMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        // Wylosuj jeden item z loot table
        ItemStack loot = pickLoot();

        if (loot != null) {
            Player topPlayer = topUUID != null ? Bukkit.getPlayer(topUUID) : null;

            if (topPlayer != null && topPlayer.isOnline()) {
                // Daj nagrode graczowi z maks obrażeniami
                var leftover = topPlayer.getInventory().addItem(loot);
                // Jeśli inwentarz pełny - upuść na ziemię
                leftover.forEach((slot, item) ->
                    deathLocation.getWorld().dropItemNaturally(deathLocation, item)
                );
                topPlayer.sendMessage(
                    Component.text("You defeated Hellodude's Dad and received a reward!", NamedTextColor.GOLD)
                );
            } else {
                // Jeśli top gracz jest offline - upuść na ziemię
                deathLocation.getWorld().dropItemNaturally(deathLocation, loot);
            }
        }

        // Ogłoszenie kto wygrał
        if (topUUID != null) {
            Player topPlayer = Bukkit.getPlayer(topUUID);
            String name = topPlayer != null ? topPlayer.getName() : "Unknown";
            int dmg = damageMap.getOrDefault(topUUID, 0.0).intValue();

            Bukkit.getServer().broadcast(
                Component.text(
                    "Hellodude's Dad has been defeated! " + name + " dealt the most damage (" + dmg + " HP)!",
                    NamedTextColor.GOLD
                )
            );
        }

        cleanup();
    }

    // ================================================
    //   Losowanie lootu
    // ================================================

    private ItemStack pickLoot() {
        if (lootEntries.isEmpty()) return null;

        double totalWeight = lootEntries.stream().mapToDouble(e -> e.weight).sum();
        if (totalWeight <= 0) return null;

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (LootEntry entry : lootEntries) {
            cumulative += entry.weight;
            if (roll < cumulative) {
                return entry.createItem(log);
            }
        }

        // Fallback (nie powinno się zdarzyć)
        return lootEntries.get(lootEntries.size() - 1).createItem(log);
    }

    // ================================================
    //   Pomocnicze metody publiczne
    // ================================================

    public void onPlayerJoin(Player player) {
        if (bossBar != null) bossBar.addPlayer(player);
    }

    public boolean isBoss(Entity entity) {
        return entity.getPersistentDataContainer().has(bossKey, PersistentDataType.STRING);
    }

    public Ravager getActiveBoss() {
        return activeBoss;
    }

    public void cleanup() {
        // Usuń boss bar
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }

        // Anuluj timery
        cancelTask(coordinateTaskId);
        cancelTask(topDamagerTaskId);
        cancelTask(actionBarTaskId);
        cancelTask(pullTaskId);
        cancelTask(stuckCheckTaskId);
        coordinateTaskId = -1;
        topDamagerTaskId = -1;
        actionBarTaskId = -1;
        pullTaskId = -1;
        stuckCheckTaskId = -1;

        activeBoss = null;
        damageMap.clear();
        triggeredPhases.clear();

        // Wyczyść action bar wszystkim graczom
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(Component.empty());
        }
    }

    private void cancelTask(int taskId) {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }
}
