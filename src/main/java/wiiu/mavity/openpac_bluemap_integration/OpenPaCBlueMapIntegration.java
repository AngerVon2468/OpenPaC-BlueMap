package wiiu.mavity.openpac_bluemap_integration;

import com.google.gson.stream.*;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.bluecolored.bluemap.api.*;
import de.bluecolored.bluemap.api.markers.*;
import de.bluecolored.bluemap.api.math.Color;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.apache.commons.lang3.StringUtils;

import org.slf4j.*;

import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.io.*;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(OpenPaCBlueMapIntegration.MOD_ID)
public class OpenPaCBlueMapIntegration {

    public static final String MOD_ID = "openpac_bluemap_integration";

    public static final Logger LOGGER = LoggerFactory.getLogger("OpenPaCBlueMapIntegration");

    private static final String MARKER_SET_KEY = "opac-bluemap-integration";
    private static final File CONFIG_FILE = new File(System.getProperty("user.dir") + FileSystems.getDefault().getSeparator() + "config", "opac-bluemap.json");

    public static final OpacBluemapConfig CONFIG = new OpacBluemapConfig();

    private static int updateIn;

    private static MinecraftServer server;

    public OpenPaCBlueMapIntegration() {
        loadConfig();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        BlueMapAPI.onEnable(OpenPaCBlueMapIntegration::updateClaims);

        modEventBus.addListener(this::commonSetup);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("openpac-bluemap")
                .requires(s -> s.hasPermission(2))
                .then(literal("refresh-now")
                        .requires(s -> BlueMapAPI.getInstance().isPresent())
                        .executes(ctx -> {
                            final BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
                            if (api == null) {
                                ctx.getSource().sendFailure(Component.literal("BlueMap not loaded").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            updateClaims(api);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("BlueMap OpenPaC claims refreshed").withStyle(ChatFormatting.GREEN),
                                    true
                            );
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(literal("refresh-in")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                            Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                                    ),
                                    true
                            );
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(argument("time", TimeArgument.time())
                                .executes(ctx -> {
                                    updateIn = IntegerArgumentType.getInteger(ctx, "time");
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                                    Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                                            ),
                                            true
                                    );
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(literal("refresh-every")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("OpenPaC BlueMap auto refreshes every ").append(
                                            Component.literal((CONFIG.getUpdateInterval() / 20) + "s").withStyle(ChatFormatting.GREEN)
                                    ),
                                    true
                            );
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(argument("interval", TimeArgument.time())
                                .executes(ctx -> {
                                    final int interval = IntegerArgumentType.getInteger(ctx, "interval");
                                    CONFIG.setUpdateInterval(interval);
                                    if (interval < updateIn) {
                                        updateIn = interval;
                                    }
                                    saveConfig();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("OpenPaC BlueMap will auto refresh every ").append(
                                                    Component.literal((interval / 20) + "s").withStyle(ChatFormatting.GREEN)
                                            ),
                                            true
                                    );
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(literal("reload")
                        .executes(ctx -> {
                            loadConfig();
                            if (CONFIG.getUpdateInterval() < updateIn) {
                                updateIn = CONFIG.getUpdateInterval();
                            }
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("Reloaded OpenPaC BlueMap config").withStyle(ChatFormatting.GREEN),
                                    true
                            );
                            return Command.SINGLE_SUCCESS;
                        })
                )
        ));

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {}

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (updateIn <= 0) return;
            if (--updateIn <= 0) {
                BlueMapAPI.getInstance().ifPresent(OpenPaCBlueMapIntegration::updateClaims);
            }
        }
    }

    public static void loadConfig() {
        try (JsonReader reader = new JsonReader(new FileReader(CONFIG_FILE))) {
            CONFIG.read(reader);
        } catch (Exception e) {
            LOGGER.warn("Failed to read {}.", CONFIG_FILE, e);
        }
        saveConfig();
    }

    public static void saveConfig() {
        try (JsonWriter writer = new JsonWriter(new FileWriter(CONFIG_FILE))) {
            CONFIG.write(writer);
        } catch (Exception e) {
            LOGGER.error("Failed to write {}.", CONFIG_FILE, e);
        }
        LOGGER.info("Saved OpenPaC BlueMap config");
    }

    public static List<ShapeHolder> createShapes(Set<ChunkPos> chunks) {
        return createChunkGroups(chunks)
                .stream()
                .map(ShapeHolder::create)
                .toList();
    }

    public static List<Set<ChunkPos>> createChunkGroups(Set<ChunkPos> chunks) {
        final List<Set<ChunkPos>> result = new ArrayList<>();
        final Set<ChunkPos> visited = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            final Set<ChunkPos> neighbors = findNeighbors(chunk, chunks);
            result.add(neighbors);
            visited.addAll(neighbors);
        }
        return result;
    }

    public static Set<ChunkPos> findNeighbors(ChunkPos chunk, Set<ChunkPos> chunks) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("chunks must contain chunk to find neighbors!");
        }
        final Set<ChunkPos> visited = new HashSet<>();
        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(chunk);
        toVisit.add(chunk);
        while (!toVisit.isEmpty()) {
            final ChunkPos visiting = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(visiting);
                if (!chunks.contains(offsetPos) || !visited.add(offsetPos)) continue;
                toVisit.add(offsetPos);
            }
        }
        return visited;
    }

    public static void updateClaims(BlueMapAPI blueMap) {
        if (server == null) {
            LOGGER.warn("updateClaims called with minecraftServer == null!");
            return;
        }
        LOGGER.info("Refreshing OpenPaC BlueMap markers");
        OpenPACServerAPI.get(server)
                .getServerClaimsManager()
                .getPlayerInfoStream()
                .forEach(playerClaimInfo -> {
                    String name = playerClaimInfo.getClaimsName();
                    final String idName;
                    if (StringUtils.isBlank(name)) {
                        idName = name = playerClaimInfo.getPlayerUsername();
                        if (name.length() > 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
                            name = name.substring(1, name.length() - 1) + " claim";
                        } else {
                            name += "'s claim";
                        }
                    } else {
                        idName = name + " - " + playerClaimInfo.getPlayerUsername();
                    }
                    final String displayName = name;
                    playerClaimInfo.getStream().forEach(entry -> {
                        final BlueMapWorld world = blueMap.getWorld(ResourceKey.create(Registries.DIMENSION, entry.getKey())).orElse(null);
                        if (world == null) return;
                        final List<ShapeHolder> shapes = createShapes(
                                entry.getValue()
                                        .getStream()
                                        .flatMap(IPlayerClaimPosListAPI::getStream)
                                        .collect(Collectors.toSet())
                        );
                        world.getMaps().forEach(map -> {
                            final Map<String, de.bluecolored.bluemap.api.markers.Marker> markers = map
                                    .getMarkerSets()
                                    .computeIfAbsent(MARKER_SET_KEY, k ->
                                            MarkerSet.builder()
                                                    .toggleable(true)
                                                    .label("Open Parties and Claims")
                                                    .build()
                                    )
                                    .getMarkers();
                            final float minY = CONFIG.getMarkerMinY();
                            final float maxY = CONFIG.getMarkerMaxY();
                            //noinspection SuspiciousNameCombination
                            final boolean flatPlane = Mth.equal(minY, maxY);
                            markers.keySet().removeIf(k -> k.startsWith(idName + "---"));
                            for (int i = 0; i < shapes.size(); i++) {
                                final ShapeHolder shape = shapes.get(i);
                                markers.put(idName + "---" + i,
                                        // Yes these builders are the same. No they don't share a superclass (except for label).
                                        flatPlane
                                                ? ShapeMarker.builder()
                                                .label(displayName)
                                                .fillColor(new Color(playerClaimInfo.getClaimsColor(), 150))
                                                .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                                .shape(shape.baseShape(), minY)
                                                .holes(shape.holes())
                                                .depthTestEnabled(CONFIG.isDepthTest())
                                                .build()
                                                : ExtrudeMarker.builder()
                                                .label(displayName)
                                                .fillColor(new Color(playerClaimInfo.getClaimsColor(), 150))
                                                .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                                .shape(shape.baseShape(), minY, maxY)
                                                .holes(shape.holes())
                                                .depthTestEnabled(CONFIG.isDepthTest())
                                                .build()
                                );
                            }
                        });
                    });
                });
        LOGGER.info("Refreshed OpenPaC BlueMap markers");
        updateIn = CONFIG.getUpdateInterval();
    }
}