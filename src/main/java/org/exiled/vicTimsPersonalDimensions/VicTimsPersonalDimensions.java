package org.exiled.vicTimsPersonalDimensions;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VicTimsPersonalDimensions extends JavaPlugin implements Listener {

    private Storage storage;
    private FileConfiguration config;
    private final Map<String, Location> returnLocations = new ConcurrentHashMap<>();
    private String mainWorldName;
    private String templateWorldName = "template_personal";
    private File templateWorldFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        mainWorldName = config.getString("main-world", "world");
        templateWorldFolder = new File(getServer().getWorldContainer(), templateWorldName);

        // Инициализация хранилища
        if (config.getBoolean("database.use-mysql", false)) {
            storage = new MySQLStorage(
                    config.getString("database.host"),
                    config.getInt("database.port"),
                    config.getString("database.database"),
                    config.getString("database.username"),
                    config.getString("database.password")
            );
        } else {
            storage = new FileStorage(new File(getDataFolder(), "dimensions.db"));
        }

        // Создание шаблонного мира, если его нет
        if (!templateWorldFolder.exists()) {
            getLogger().info("Создаётся шаблонный мир для персональных измерений...");
            WorldCreator wc = new WorldCreator(templateWorldName);
            wc.type(WorldType.NORMAL);
            wc.generateStructures(true);
            wc.environment(World.Environment.NORMAL);
            World world = wc.createWorld();
            if (world != null) {
                int size = config.getInt("world-size", 1000);
                world.getWorldBorder().setCenter(0, 0);
                world.getWorldBorder().setSize(size * 2);
                world.getWorldBorder().setDamageAmount(0);
                world.setSpawnLocation(0, world.getHighestBlockYAt(0, 0) + 1, 0);
                world.setKeepSpawnInMemory(true);
                world.setAutoSave(true);
                world.setSpawnFlags(true, false); // Только животные
                world.loadChunk(0, 0, true);
                getLogger().info("Шаблонный мир создан.");
            } else {
                getLogger().severe("Не удалось создать шаблонный мир!");
            }
        } else {
            // Загружаем шаблонный мир, если он уже есть
            if (Bukkit.getWorld(templateWorldName) == null) {
                WorldCreator wc = new WorldCreator(templateWorldName);
                wc.type(WorldType.NORMAL);
                wc.generateStructures(true);
                wc.environment(World.Environment.NORMAL);
                wc.createWorld();
            }
        }

        Objects.requireNonNull(getCommand("is")).setExecutor(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new PersonalWorldMobListener(), this);
    }

    @Override
    public void onDisable() {
        if (storage != null) storage.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("template") && args[1].equalsIgnoreCase("recreate")) {
            if (!sender.hasPermission("vicdims.admin")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            recreateTemplate(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (!(sender.hasPermission("vicdims.admin"))) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("delete")) {
                String target = args[2];
                return handleDelete(sender, target);
            }
            if (args.length >= 4 && args[1].equalsIgnoreCase("biome")) {
                String target = args[2];
                String biome = args[3];
                return handleBiome(sender, target, biome);
            }
            sender.sendMessage("§cИспользование: /is admin <delete|biome> <игрок> [биом]");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков!");
            return true;
        }
        if (args.length < 1) return false;
        switch (args[0].toLowerCase()) {
            case "create" -> createDimension(player);
            case "tp" -> {
                if (!player.hasPermission("vicdims.tp")) {
                    player.sendMessage("§cНет прав!");
                    return true;
                }
                teleportToDimension(player);
            }
            case "back" -> {
                if (!player.hasPermission("vicdims.back")) {
                    player.sendMessage("§cНет прав!");
                    return true;
                }
                returnToMainWorld(player);
            }
            default -> player.sendMessage("§cНеизвестная команда. Используйте: /is [create|tp|back|admin]");
        }
        return true;
    }

    private void createDimension(Player player) {
        if (!player.hasPermission("vicdims.create")) {
            player.sendMessage("§cУ вас нет прав на создание измерения!");
            return;
        }
        String uuid = player.getUniqueId().toString();
        if (storage.hasDimension(uuid)) {
            player.sendMessage("§cУ вас уже есть персональное измерение!");
            return;
        }
        String worldName = "personal_" + uuid;
        player.sendMessage("§aСоздание измерения...");
        saveReturnLocation(player);
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    File newWorldFolder = new File(getServer().getWorldContainer(), worldName);
                    if (!newWorldFolder.exists()) {
                        copyWorld(templateWorldFolder, newWorldFolder);
                        // Удаляем uid.dat
                        File uid = new File(newWorldFolder, "uid.dat");
                        if (uid.exists()) uid.delete();
                    }
                    storage.saveDimension(uuid, worldName);
                    Bukkit.getScheduler().runTask(VicTimsPersonalDimensions.this, () -> {
                        loadAndTeleport(worldName, player);
                    });
                } catch (Exception e) {
                    player.sendMessage("§cОшибка создания: " + e.getMessage());
                    getLogger().severe("Dimension creation error: " + e);
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void loadAndTeleport(String worldName, Player player) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            WorldCreator wc = new WorldCreator(worldName);
            wc.environment(World.Environment.NORMAL);
            wc.type(WorldType.NORMAL);
            wc.generateStructures(true);
            world = wc.createWorld();
        }
        if (world != null) {
            int size = config.getInt("world-size", 1000);
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(size * 2);
            world.getWorldBorder().setDamageAmount(0);
            world.setKeepSpawnInMemory(true);
            world.setAutoSave(true);
            world.setSpawnFlags(true, false);
            Location spawn = world.getSpawnLocation().clone();
            spawn.setY(world.getHighestBlockYAt(spawn) + 1);
            spawn.add(0.5, 0, 0.5);
            world.loadChunk(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, true);
            // Принудительно force load чанки вокруг спавна (3x3)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setChunkForceLoaded((spawn.getBlockX() >> 4) + dx, (spawn.getBlockZ() >> 4) + dz, true);
                }
            }
            if (player.teleport(spawn)) {
                player.sendMessage("§aВы телепортированы в ваше измерение!");
            } else {
                player.sendMessage("§cОшибка телепортации! Попробуйте еще раз.");
                returnToMainWorld(player);
            }
        } else {
            player.sendMessage("§cОшибка загрузки мира!");
        }
    }

    private void teleportToDimension(Player player) {
        String uuid = player.getUniqueId().toString();
        if (!storage.hasDimension(uuid)) {
            player.sendMessage("§cУ вас нет персонального измерения!");
            return;
        }
        String worldName = storage.getWorldName(uuid);
        File worldFolder = new File(getServer().getWorldContainer(), worldName);
        if (!worldFolder.exists()) {
            player.sendMessage("§cВаше измерение было удалено. Обратитесь к администратору.");
            return;
        }
        saveReturnLocation(player);
        loadAndTeleport(worldName, player);
    }

    private void saveReturnLocation(Player player) {
        if (!player.getWorld().getName().startsWith("personal_")) {
            returnLocations.put(player.getUniqueId().toString(), player.getLocation());
        }
    }

    public void returnToMainWorld(Player player) {
        String uuid = player.getUniqueId().toString();
        Location returnLoc = returnLocations.get(uuid);
        if (returnLoc == null) {
            World mainWorld = Bukkit.getWorld(mainWorldName);
            if (mainWorld == null) mainWorld = Bukkit.getWorlds().get(0);
            returnLoc = mainWorld.getSpawnLocation();
        }
        // Гарантируем загрузку мира возврата
        if (Bukkit.getWorld(returnLoc.getWorld().getName()) == null) {
            WorldCreator wc = new WorldCreator(returnLoc.getWorld().getName());
            wc.environment(World.Environment.NORMAL);
            wc.type(WorldType.NORMAL);
            wc.generateStructures(true);
            wc.createWorld();
        }
        if (player.teleport(returnLoc)) {
            player.sendMessage("§aВы возвращены в основной мир!");
        } else {
            player.sendMessage("§cОшибка телепортации! Попробуйте еще раз.");
        }
    }

    public Location getReturnLocation(String uuid) {
        return returnLocations.get(uuid);
    }

    public void setReturnLocation(String uuid, Location location) {
        returnLocations.put(uuid, location);
    }

    // Копирование мира (директории)
    private void copyWorld(File source, File target) throws IOException {
        if (!source.exists()) throw new IOException("Шаблонный мир не найден!");
        Files.walk(source.toPath())
                .forEach(path -> {
                    try {
                        Path rel = source.toPath().relativize(path);
                        Path dest = target.toPath().resolve(rel);
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    // Интерфейс хранилища
    private interface Storage {
        boolean hasDimension(String uuid);
        void saveDimension(String uuid, String worldName);
        String getWorldName(String uuid);
        void close();
    }

    // Файловое хранилище
    private static class FileStorage implements Storage {
        private final File file;
        private final Properties data = new Properties();

        public FileStorage(File file) {
            this.file = file;
            load();
        }

        private void load() {
            try {
                if (file.exists()) {
                    data.load(new java.io.FileReader(file));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public boolean hasDimension(String uuid) {
            return data.containsKey(uuid);
        }

        @Override
        public void saveDimension(String uuid, String worldName) {
            data.setProperty(uuid, worldName);
            save();
        }

        @Override
        public String getWorldName(String uuid) {
            return data.getProperty(uuid);
        }

        private void save() {
            try {
                data.store(new java.io.FileWriter(file), "Personal Dimensions Database");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void close() {
            save();
        }
    }

    // MySQL хранилище
    private static class MySQLStorage implements Storage {
        private Connection connection;

        public MySQLStorage(String host, int port, String database, String user, String password) {
            try {
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false";
                connection = DriverManager.getConnection(url, user, password);
                createTable();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        private void createTable() throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS dimensions (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "world_name VARCHAR(64) NOT NULL)");
            }
        }

        @Override
        public boolean hasDimension(String uuid) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM dimensions WHERE uuid = ?")) {
                ps.setString(1, uuid);
                return ps.executeQuery().next();
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        public void saveDimension(String uuid, String worldName) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "REPLACE INTO dimensions (uuid, world_name) VALUES (?, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, worldName);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        @Override
        public String getWorldName(String uuid) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT world_name FROM dimensions WHERE uuid = ?")) {
                ps.setString(1, uuid);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getString("world_name") : null;
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        public void close() {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean handleDelete(CommandSender sender, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        String uuid = target.getUniqueId().toString();
        if (!storage.hasDimension(uuid)) {
            sender.sendMessage("§cУ игрока нет измерения!");
            return true;
        }
        String worldName = storage.getWorldName(uuid);
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            Bukkit.unloadWorld(world, false);
        }
        File worldFolder = new File(getServer().getWorldContainer(), worldName);
        try {
            deleteDirectory(worldFolder);
            storage.saveDimension(uuid, null);
            sender.sendMessage("§aИзмерение удалено!");
        } catch (Exception e) {
            sender.sendMessage("§cОшибка удаления: " + e.getMessage());
        }
        return true;
    }

    private boolean handleBiome(CommandSender sender, String playerName, String biomeName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        String uuid = target.getUniqueId().toString();
        if (!storage.hasDimension(uuid)) {
            sender.sendMessage("§cУ игрока нет измерения!");
            return true;
        }
        String worldName = storage.getWorldName(uuid);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cМир не загружен!");
            return true;
        }
        Biome biome;
        try {
            biome = Biome.valueOf(biomeName.toUpperCase());
        } catch (Exception e) {
            sender.sendMessage("§cНеизвестный биом!");
            return true;
        }
        int minX = (int) -world.getWorldBorder().getSize() / 2;
        int maxX = (int) world.getWorldBorder().getSize() / 2;
        int minZ = (int) -world.getWorldBorder().getSize() / 2;
        int maxZ = (int) world.getWorldBorder().getSize() / 2;
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    world.setBiome(x, y, z, biome);
                }
            }
        }
        sender.sendMessage("§aБиом изменён на " + biome);
        return true;
    }

    private void deleteDirectory(File dir) throws IOException {
        if (!dir.exists()) return;
        Files.walk(dir.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private void recreateTemplate(CommandSender sender) {
        // Отключаем все миры, основанные на шаблоне
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().startsWith("personal_")) {
                Bukkit.unloadWorld(world, false);
            }
        }
        try {
            deleteDirectory(templateWorldFolder);
            sender.sendMessage("§aСтарый шаблонный мир удалён.");
        } catch (IOException e) {
            sender.sendMessage("§cОшибка удаления старого мира: " + e.getMessage());
            return;
        }
        // Создаём новый шаблонный мир
        WorldCreator wc = new WorldCreator(templateWorldName);
        wc.type(WorldType.NORMAL);
        wc.generateStructures(true);
        wc.environment(World.Environment.NORMAL);
        World world = wc.createWorld();
        if (world != null) {
            int size = config.getInt("world-size", 1000);
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(size * 2);
            world.getWorldBorder().setDamageAmount(0);
            world.setSpawnLocation(0, world.getHighestBlockYAt(0, 0) + 1, 0);
            world.setKeepSpawnInMemory(true);
            world.setAutoSave(true);
            world.setSpawnFlags(true, false);
            world.loadChunk(0, 0, true);
            sender.sendMessage("§aШаблонный мир успешно пересоздан.");
        } else {
            sender.sendMessage("§cНе удалось создать шаблонный мир!");
        }
    }

    // Listener для запрета натурального спавна враждебных мобов в персональных мирах
    public static class PersonalWorldMobListener implements Listener {
        @org.bukkit.event.EventHandler
        public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
            World world = event.getLocation().getWorld();
            if (world == null) return;
            if (!world.getName().startsWith("personal_")) return;
            if (event.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL) {
                if (event.getEntity() instanceof org.bukkit.entity.Monster) {
                    event.setCancelled(true);
                }
            }
        }
    }
}
