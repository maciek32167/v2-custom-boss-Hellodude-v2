package com.hellodude.boss;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.Map;
import java.util.logging.Logger;

public class LootEntry {

    public final String type;
    public final int amount;
    public final double weight;
    public final Map<String, Integer> enchantments;
    public final String trimMaterial;
    public final String trimPattern;

    public LootEntry(String type, int amount, double weight,
                     Map<String, Integer> enchantments,
                     String trimMaterial, String trimPattern) {
        this.type = type;
        this.amount = amount;
        this.weight = weight;
        this.enchantments = enchantments;
        this.trimMaterial = trimMaterial;
        this.trimPattern = trimPattern;
    }

    public ItemStack createItem(Logger log) {
        Material material = Material.matchMaterial(type.toUpperCase());
        if (material == null) {
            log.warning("Nieznany material w config: " + type);
            return new ItemStack(Material.STONE);
        }

        ItemStack item = new ItemStack(material, amount);

        // === Enchanty ===
        if (enchantments != null) {
            for (var entry : enchantments.entrySet()) {
                try {
                    Enchantment enchant = Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft(entry.getKey().toLowerCase())
                    );
                    if (enchant != null) {
                        item.addUnsafeEnchantment(enchant, entry.getValue());
                    } else {
                        log.warning("Nieznany enchant: " + entry.getKey());
                    }
                } catch (Exception e) {
                    log.warning("Blad enchanta " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        // === Zdobienie (Trim) ===
        if (trimMaterial != null && trimPattern != null) {
            try {
                if (item.getItemMeta() instanceof ArmorMeta armorMeta) {
                    TrimMaterial mat = Registry.TRIM_MATERIAL.get(
                        NamespacedKey.minecraft(trimMaterial.toLowerCase())
                    );
                    TrimPattern pat = Registry.TRIM_PATTERN.get(
                        NamespacedKey.minecraft(trimPattern.toLowerCase())
                    );
                    if (mat != null && pat != null) {
                        armorMeta.setTrim(new ArmorTrim(mat, pat));
                        item.setItemMeta(armorMeta);
                    } else {
                        log.warning("Nieznany trim material lub pattern: " + trimMaterial + "/" + trimPattern);
                    }
                }
            } catch (Exception e) {
                log.warning("Blad podczas ustawiania trimu: " + e.getMessage());
            }
        }

        return item;
    }
}
