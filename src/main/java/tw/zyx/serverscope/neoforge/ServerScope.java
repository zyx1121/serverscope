package tw.zyx.serverscope.neoforge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
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

    public ServerScope(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[serverscope] loaded");
    }

    // ---- server lifecycle (discrete) ----
    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        Telemetry.init(); // emits server.starting
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        Telemetry.event("server.started");
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        Telemetry.event("server.stopping");
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent e) {
        Telemetry.event("server.stopped");
    }

    // ---- player lifecycle (discrete) ----
    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        Telemetry.event("player.join", "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        Telemetry.event("player.leave", "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        Telemetry.event("player.respawn", "player.name", e.getEntity().getName().getString());
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        Telemetry.event("player.dimension", "to", e.getTo().location().toString());
    }

    // ---- activity (discrete) ----
    @SubscribeEvent
    public void onChat(ServerChatEvent e) {
        Telemetry.event("chat", "player.name", e.getPlayer().getName().getString());
    }

    @SubscribeEvent
    public void onCommand(CommandEvent e) {
        Telemetry.event("command", "command", e.getParseResults().getReader().getString());
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        Telemetry.event("advancement", "player.name", e.getEntity().getName().getString());
    }

    // ---- death (discrete) ----
    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof Player p) {
            Telemetry.event("player.death", "player.name", p.getName().getString());
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
