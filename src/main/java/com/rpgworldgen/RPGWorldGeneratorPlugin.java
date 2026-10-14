package com.rpgworldgen;

import com.rpgworldgen.command.CommandManager;
import com.rpgworldgen.config.ConfigurationManager;
import com.rpgworldgen.gen.StructurePlacementEngine;
import com.rpgworldgen.integration.IntegrationManager;
import com.rpgworldgen.persist.GenerationPersistenceService;
import com.rpgworldgen.sched.GenerationTaskScheduler;
import com.rpgworldgen.zones.SafeZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * RPGWorldGenerator plugin entry point.
 *
 * <p>Supported/tested versions: Paper/Spigot 1.20.4, Java 17+. Other 1.20.x
 * versions are expected to work (stable Bukkit API surface used) but are not
 * claimed as tested — see README compatibility table.
 */
public class RPGWorldGeneratorPlugin extends JavaPlugin {

    private ConfigurationManager configManager;
    private GenerationPersistenceService db;
    private StructurePlacementEngine placementEngine;
    private GenerationTaskScheduler scheduler;
    private SafeZoneManager safeZoneManager;
    private WorldGenerationManager worldManager;
    private IntegrationManager integrations;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        com.rpgworldgen.gen.ChunkPreloader.init(this);
        configManager = new ConfigurationManager(this);
        configManager.loadAll();

        db = new GenerationPersistenceService(getLogger(), getDataFolder());
        try {
            db.init();
        } catch (SQLException e) {
            getLogger().severe("Database init failed, disabling: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        placementEngine = new StructurePlacementEngine(configManager.getMaxQueueSize());
        scheduler = new GenerationTaskScheduler(this, placementEngine, db,
            configManager.getBlocksPerTick(), configManager.getWildernessBudget(),
            configManager.getErrorHaltThreshold(), configManager.getLootDrainPerTick());
        safeZoneManager = new SafeZoneManager();
        worldManager = new WorldGenerationManager(this, configManager, db, scheduler, safeZoneManager);
        integrations = new IntegrationManager(getLogger());
        integrations.detect();

        CommandManager commands = new CommandManager(this);
        if (getCommand("rpgworld") != null) {
            getCommand("rpgworld").setExecutor(commands);
            getCommand("rpgworld").setTabCompleter(commands);
        }
        Bukkit.getPluginManager().registerEvents(safeZoneManager, this);
        // Warn about missing schematic templates (Mode B falls back to procedural).
        com.rpgworldgen.gen.SchematicIntegration schematics =
            new com.rpgworldgen.gen.SchematicIntegration(getLogger());
        schematics.detect(Bukkit.getServer());
        for (String warn : schematics.validate(configManager.getStructures(), getDataFolder())) {
            getLogger().warning("[schematics] " + warn);
        }
        // Throttled movement check for safe-zone enter/exit messages (every 10 ticks).
        Bukkit.getScheduler().runTaskTimer(this, () ->
            Bukkit.getOnlinePlayers().forEach(safeZoneManager::checkMovement), 10L, 10L);

        // Resume unfinished generation jobs after a restart.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                worldManager.recoverUnfinished();
            } catch (Exception e) {
                getLogger().warning("Recovery failed: " + e.getMessage());
            }
            scheduler.start();
        }, 40L);

        getLogger().info("RPGWorldGenerator 1.0.0 enabled.");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.stop();
        com.rpgworldgen.gen.ChunkPreloader.releaseAll();
        // Persist pause state for running jobs so recovery resumes them.
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                getLogger().warning("DB close failed: " + e.getMessage());
            }
        }
        getLogger().info("RPGWorldGenerator disabled.");
    }

    public ConfigurationManager getConfigManager() { return configManager; }
    public GenerationPersistenceService getDatabase() { return db; }
    public StructurePlacementEngine getPlacementEngine() { return placementEngine; }
    public GenerationTaskScheduler getScheduler() { return scheduler; }
    public SafeZoneManager getSafeZones() { return safeZoneManager; }
    public WorldGenerationManager getWorldManager() { return worldManager; }
    public IntegrationManager getIntegrations() { return integrations; }
}
