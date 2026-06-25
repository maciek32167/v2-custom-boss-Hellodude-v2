package com.hellodude.boss;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BossCommand implements CommandExecutor {

    private final BossManager bossManager;

    public BossCommand(BossManager bossManager) {
        this.bossManager = bossManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Tylko gracze mogą używać tej komendy
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda dziala tylko dla graczy!");
            return true;
        }

        // Sprawdź uprawnienia
        if (!player.hasPermission("hellodude.boss.spawn")) {
            player.sendMessage("§cNie masz uprawnien do spawnowania bossow!");
            return true;
        }

        // Sprawdź argumenty
        if (args.length == 0) {
            player.sendMessage("§cUzycie: /bossfight <bossname>");
            player.sendMessage("§7Dostepni bossowie: §fHellodudesDad");
            return true;
        }

        String bossName = args[0];

        if (bossName.equalsIgnoreCase("HellodudesDad")) {
            bossManager.spawnBoss(player.getLocation());
            player.sendMessage("§aSpawnowanie Hellodude's Dad...");
        } else {
            player.sendMessage("§cNieznany boss: §f" + bossName);
            player.sendMessage("§7Dostepni bossowie: §fHellodudesDad");
        }

        return true;
    }
}
