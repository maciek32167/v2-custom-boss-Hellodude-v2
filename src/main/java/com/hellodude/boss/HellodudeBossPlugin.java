package com.hellodude.boss;

import org.bukkit.plugin.java.JavaPlugin;

public class HellodudeBossPlugin extends JavaPlugin {

    private BossManager bossManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bossManager = new BossManager(this);

        getServer().getPluginManager().registerEvents(
            new BossListener(this, bossManager), this
        );

        var cmd = getCommand("bossfight");
        if (cmd != null) {
            cmd.setExecutor(new BossCommand(bossManager));
        }

        getLogger().info("Hellodude Boss Plugin wlaczony!");
    }

    @Override
    public void onDisable() {
        if (bossManager != null) bossManager.cleanup();
        getLogger().info("Hellodude Boss Plugin wylaczony.");
    }

    public BossManager getBossManager() {
        return bossManager;
    }
}
