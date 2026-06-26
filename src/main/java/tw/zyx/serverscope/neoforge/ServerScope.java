package tw.zyx.serverscope.neoforge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedHashMap;
import java.util.Map;

import tw.zyx.serverscope.core.Telemetry;

/**
 * NeoForge adapter — subscribes to server-side game events on NeoForge.EVENT_BUS
 * and hands them to the platform-agnostic core in three observability layers:
 *   discrete -> Telemetry.event (span + counter)
 *   high-freq -> Telemetry.count (counter only)
 *   gauges   -> Telemetry.sample* (sampled once/sec from ServerTickEvent.Post)
 * All FQNs verified against NeoForge 21.1.233 (jar + 1.21.1 docs).
 */
@Mod(ServerScope.MODID)
public class ServerScope {
    public static final String MODID = "serverscope";
    public static final Logger LOGGER = LogUtils.getLogger();

    private int tickCounter = 0;
    private final String modVersion;

    public ServerScope(IEventBus modEventBus, ModContainer modContainer) {
        this.modVersion = modContainer.getModInfo().getVersion().toString();
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[serverscope] loaded");
    }

    // ---- server lifecycle (discrete) ----
    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        MinecraftServer server = e.getServer();
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("service.name", MODID);
        resource.put("service.version", modVersion);
        resource.put("mc.version", SharedConstants.getCurrentVersion().getName());
        resource.put("mc.loader", "neoforge");
        Telemetry.init(resource); // emits server.starting

        // before marking this run live, report whether the previous run died unclean
        detectPreviousCrash(server);
        writeState(server, "running");
    }

    // ---- crash / unclean-restart detection -------------------------------------------
    // A state file records "running" while up and "stopped" on a clean shutdown. If we
    // boot and find it still "running", the previous run was killed (watchdog hang, OOM,
    // SIGKILL...) -> emit server.unclean_restart, enriched with the crash report MC left.
    private java.nio.file.Path stateFile(MinecraftServer server) {
        return server.getServerDirectory().resolve("serverscope-state.txt");
    }

    private void writeState(MinecraftServer server, String status) {
        try {
            java.nio.file.Files.writeString(stateFile(server),
                    status + " " + System.currentTimeMillis());
        } catch (Exception ex) {
            LOGGER.warn("[serverscope] state write failed", ex);
        }
    }

    private void detectPreviousCrash(MinecraftServer server) {
        try {
            java.nio.file.Path state = stateFile(server);
            if (!java.nio.file.Files.exists(state)) {
                return; // first run, nothing to compare against
            }
            String[] parts = java.nio.file.Files.readString(state).trim().split("\\s+");
            boolean wasRunning = parts.length >= 1 && "running".equals(parts[0]);
            long lastStart = 0;
            if (parts.length >= 2) {
                try {
                    lastStart = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (!wasRunning) {
                return; // previous run shut down cleanly
            }

            // find the newest crash report from the previous run, if it left one
            String reportName = null;
            String reason = null;
            java.nio.file.Path crashDir = server.getServerDirectory().resolve("crash-reports");
            if (java.nio.file.Files.isDirectory(crashDir)) {
                java.nio.file.Path newest = null;
                long newestMs = lastStart;
                try (var stream = java.nio.file.Files.newDirectoryStream(crashDir, "crash-*-server.txt")) {
                    for (java.nio.file.Path p : stream) {
                        long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                        if (mtime >= newestMs) {
                            newestMs = mtime;
                            newest = p;
                        }
                    }
                }
                if (newest != null) {
                    reportName = newest.getFileName().toString();
                    reason = crashDescription(newest);
                }
            }
            // null kv pairs are dropped by Telemetry.event -> OOM/SIGKILL (no report) still emits
            Telemetry.event("server.unclean_restart", null,
                    "crash.report", reportName,
                    "crash.reason", reason);
            LOGGER.warn("[serverscope] previous run did not shut down cleanly (report={}, reason={})",
                    reportName, reason);
        } catch (Exception ex) {
            LOGGER.warn("[serverscope] crash detection failed", ex);
        }
    }

    private String crashDescription(java.nio.file.Path crash) {
        try (var lines = java.nio.file.Files.lines(crash)) {
            return lines.filter(l -> l.startsWith("Description:"))
                    .map(l -> l.substring("Description:".length()).trim())
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        Telemetry.event("server.started");
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        Telemetry.event("server.stopping");
        writeState(e.getServer(), "stopped"); // clean shutdown -> next boot won't flag a crash
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent e) {
        Telemetry.event("server.stopped");
    }

    // ---- player lifecycle (discrete) ----
    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        Telemetry.event("player.join", null, "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        Telemetry.event("player.leave", null, "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        Telemetry.event("player.respawn", null, "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        Telemetry.event("player.dimension", null,
                "player.name", e.getEntity().getName().getString(),
                "from", e.getFrom().location().toString(),
                "to", e.getTo().location().toString());
    }

    // ---- activity (discrete) ----
    @SubscribeEvent
    public void onChat(ServerChatEvent e) {
        Telemetry.event("chat", null, "player.name", e.getPlayer().getName().getString());
    }

    @SubscribeEvent
    public void onCommand(CommandEvent e) {
        CommandSourceStack src = e.getParseResults().getContext().getSource();
        // command text is high-cardinality -> span only; counter just counts commands
        Telemetry.event("command", null,
                "command", e.getParseResults().getReader().getString(),
                "actor", src.getTextName(),
                "dimension", src.getLevel().dimension().location().toString());
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        AdvancementHolder adv = e.getAdvancement();
        var display = adv.value().display();
        if (display.isEmpty()) {
            return; // recipe-unlock advancements have no display -> not a real achievement, skip the noise
        }
        DisplayInfo d = display.get();
        Telemetry.event("advancement", d.getType().getSerializedName(),
                "player.name", e.getEntity().getName().getString(),
                "advancement.id", adv.id().toString(),
                "advancement.title", d.getTitle().getString(),
                "advancement.type", d.getType().getSerializedName());
    }

    // ---- death (discrete) ----
    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        // telemetry must never crash a server tick: this handler reads an arbitrary
        // mod's DamageSource, so swallow anything rather than propagate into die().
        try {
            DamageSource src = e.getSource();
            String cause = src.getMsgId();
            LivingEntity victim = e.getEntity();
            if (victim instanceof Player p) {
                Entity killer = src.getEntity();
                // getWeaponItem() is @Nullable (env/melee/explosion deaths have no weapon)
                ItemStack weapon = src.getWeaponItem();
                Telemetry.event("player.death", cause,
                        "player.name", p.getName().getString(),
                        "cause", cause,
                        "message", src.getLocalizedDeathMessage(p).getString(),
                        "dimension", p.level().dimension().location().toString(),
                        "x", Integer.toString(p.blockPosition().getX()),
                        "y", Integer.toString(p.blockPosition().getY()),
                        "z", Integer.toString(p.blockPosition().getZ()),
                        "killer", killer == null ? null
                                : BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()).toString(),
                        "weapon", weapon == null || weapon.isEmpty() ? null
                                : BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString());
            } else {
                // mobs die constantly -> counter only, keyed by victim type (mirrors mob.spawn)
                Telemetry.count("mob.death", BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString());
            }
        } catch (Exception ex) {
            LOGGER.warn("[serverscope] onDeath telemetry failed", ex);
        }
    }

    // ---- high-frequency (counter only) ----
    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        Telemetry.count("block.break", BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).toString());
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        Telemetry.count("block.place", BuiltInRegistries.BLOCK.getKey(e.getPlacedBlock().getBlock()).toString());
    }

    @SubscribeEvent
    public void onSpawn(FinalizeSpawnEvent e) {
        Telemetry.count("mob.spawn", BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString());
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.RightClickBlock e) {
        if (!e.getLevel().isClientSide()) {
            Telemetry.count("interact", "right_click_block");
        }
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post e) {
        Telemetry.count("damage", BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString());
    }

    // every vanilla stat increment (distance, jumps, mob kills, play time, item use, ...);
    // fires very frequently -> counter only, keyed by stat name (bounded set, no span flood)
    @SubscribeEvent
    public void onStat(StatAwardEvent e) {
        Telemetry.count("stat", e.getStat().getName());
    }

    // ---- gauge sampling: ServerTickEvent.Post, once per second (every 20 ticks) ----
    @SubscribeEvent
    public void onTick(ServerTickEvent.Post e) {
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        MinecraftServer server = e.getServer();
        double mspt = server.getCurrentSmoothedTickTime();
        double tps = Math.min(20.0, 1000.0 / Math.max(mspt, 0.01));
        Telemetry.sampleTick(tps, mspt);

        long players = server.getPlayerCount();
        long entities = 0;
        long chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            chunks += level.getChunkSource().getLoadedChunksCount();
            for (Entity ignored : level.getAllEntities()) {
                entities++;
            }
        }
        Telemetry.sampleCounts(players, entities, chunks);
    }
}
