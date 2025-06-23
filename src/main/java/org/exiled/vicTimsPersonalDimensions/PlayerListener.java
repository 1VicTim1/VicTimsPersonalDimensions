package org.exiled.vicTimsPersonalDimensions;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator.BiomeGrid;
import org.bukkit.generator.ChunkGenerator;

public class PlayerListener implements Listener {

    private final VicTimsPersonalDimensions plugin;

    public PlayerListener(VicTimsPersonalDimensions plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        // Не телепортируем в основной мир, просто оставляем игрока там, где он был
        // Если игрок был в личном мире и он не загружен — загружаем его
        Location loc = player.getLocation();
        String worldName = loc.getWorld().getName();
        if (worldName.startsWith("personal_")) {
            if (Bukkit.getWorld(worldName) == null) {
                WorldCreator wc = new WorldCreator(worldName);
                wc.environment(World.Environment.NORMAL);
                wc.type(WorldType.NORMAL);
                wc.generateStructures(true);
                wc.createWorld();
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        // Если игрок в личном мире, сохраняем его позицию
        if (player.getWorld().getName().startsWith("personal_")) {
            plugin.setReturnLocation(uuid, player.getLocation());
        }
    }
}