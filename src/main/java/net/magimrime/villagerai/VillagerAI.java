package net.magimrime.villagerai;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Mod(VillagerAI.MODID)
public class VillagerAI {

    public static final String MODID = "villagerai";
    private static final Logger LOGGER = LogUtils.getLogger();

    // =========================================================================
    // Courage / Scared trait
    // =========================================================================
    // Stored in the entity's persistent NBT data (key "courage") which Forge
    // automatically saves and loads with the entity — no registration needed.
    // absent / 0 = uninitialized (assigned on first load)
    // 1 = brave (normal)
    // 2 = scared (25 % chance at birth)
    private static final String COURAGE_KEY = "courage";

    // =========================================================================
    // Debug game rules (temporary — remove before release)
    // =========================================================================
    // GameRules.register() is public-static and called at class-load time, which
    // is before any world or server starts, so the keys are safely available
    // everywhere.

    /**
     * When true, villagers emit particles every 2 s showing their courage trait:
     * soul particles (blue) = scared │ happy-villager sparkles (green) = brave.
     */
    public static final GameRules.Key<GameRules.BooleanValue> SHOW_VILLAGER_COURAGE = GameRules.register(
            "showVillagerCourage",
            GameRules.Category.MISC,
            GameRules.BooleanValue.create(false));

    // =========================================================================
    // Panic-suppression state (environmental damage)
    // =========================================================================
    // Maps villager UUID → game-tick of the most recent block-damage event
    private static final Map<UUID, Long> blockHurtTime = new HashMap<>();
    // How many ticks (5 s) to suppress IS_PANICKING after block-damage while
    // pathing
    private static final int PANIC_SUPPRESS_TICKS = 100;

    // =========================================================================
    // Flee / shelter state
    // =========================================================================
    // Villagers sandwiched between a threat and a lethal hazard (play sweat anim)
    private static final Set<UUID> corneredVillagers = new HashSet<>();
    // Where each sheltering villager is hiding (null / absent = not sheltering)
    private static final Map<UUID, BlockPos> shelterPos = new HashMap<>();
    // Last game-tick at which the villager *directly saw* (line-of-sight) a threat
    private static final Map<UUID, Long> lastThreatSeen = new HashMap<>();
    // Original nav destination when a path was cancelled to attempt a bypass
    private static final Map<UUID, BlockPos> pendingDestination = new HashMap<>();
    // Monsters that were seen targeting this villager; shelter holds until they die
    private static final Map<UUID, Set<UUID>> chasingMonsters = new HashMap<>();
    // Villagers that are currently confirmed to be indoors (used for immediate
    // door-close on entry)
    private static final Set<UUID> wasInsideShelter = new HashSet<>();
    // Beds that the villager could not path to (blocked by lava, etc.)
    private static final Map<UUID, Set<BlockPos>> unreachableBeds = new HashMap<>();

    // =========================================================================
    // Tuning constants
    // =========================================================================
    /**
     * Ticks after last-seen-threat before a brave villager leaves shelter (~1 s to
     * bridge ticks).
     */
    private static final int SHELTER_EXIT_NORMAL = 25;
    /**
     * Ticks after last-seen-threat before a scared villager leaves shelter (~60 s).
     */
    private static final int SHELTER_EXIT_SCARED = 1200;
    /**
     * Sq-distance (blocks²) of the close-range flee trigger (any visible monster).
     */
    private static final double FLEE_RADIUS_SQ = 23.0 * 23.0;
    /**
     * Sq-distance (blocks²) below which the villager RUNS; above it they WALK (up
     * to FLEE_RADIUS).
     */
    private static final double FLEE_WALK_RADIUS_SQ = 30.0 * 30.0; // flee_radius − 5
    /** Radius (blocks) within which a chasing (targeting) monster triggers flee. */
    private static final double CHASE_DETECT_RADIUS = 48.0;
    /** Radius (blocks) for the shelter-trigger threat scan. */
    private static final double SHELTER_SCAN_RADIUS = 32.0;
    /** Radius (blocks) for the path-threat scan. */
    private static final double PATH_SCAN_RADIUS = 36.0;
    /** Sq-distance (blocks²) within which a path node is considered "blocked". */
    private static final double PATH_THREAT_RADIUS_SQ = 4.5 * 4.5;
    /** How many upcoming path nodes to inspect for threats. */
    private static final int PATH_LOOKAHEAD = 14;
    /** Lateral offset (blocks) when computing bypass waypoints around a threat. */
    private static final double BYPASS_DIST = 7.0;

    // =========================================================================
    // Constructor
    // =========================================================================
    public VillagerAI() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        Pathfinding.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** True if this villager was born with the scared personality (25 % chance). */
    private static boolean isScared(Villager v) {
        return v.getPersistentData().getInt(COURAGE_KEY) == 2;
    }

    /**
     * Returns {@code true} if the villager is under any roof — slab, stair, plank,
     * stone, glass, etc. Uses {@link net.minecraft.world.level.Level#canSeeSky}
     * which
     * performs Minecraft's own sky-visibility ray-cast, making it reliable for
     * every
     * vanilla house type. The old {@code isSolid()} loop missed half-slabs and
     * stairs,
     * so the "first tick inside" door-close transition never fired for most roofs.
     */
    private static boolean isIndoors(Villager villager) {
        // canSeeSky() returns false the moment ANY block (slab, stair, glass, plank …)
        // obstructs the sky directly above — exactly what "under a roof" means.
        return !villager.level().canSeeSky(villager.blockPosition().above());
    }

    /**
     * Checks if the villager is currently standing in a door, or if their active
     * path requires them to walk through a door ahead of them.
     */
    private static boolean hasDoorAhead(Villager villager) {
        if (villager.level().getBlockState(villager.blockPosition()).getBlock() instanceof DoorBlock) {
            return true;
        }
        Path path = villager.getNavigation().getPath();
        if (path != null) {
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                BlockPos pos = new BlockPos(node.x, node.y, node.z);
                if (villager.level().getBlockState(pos).getBlock() instanceof DoorBlock) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // Entity join: assign courage trait + water hazard + fire-flee goal
    // =========================================================================
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {

        if (!(event.getEntity() instanceof Villager villager))
            return;

        // Assign the courage trait exactly once per villager.
        // getPersistentData() returns a CompoundTag saved with the entity's NBT —
        // the value survives server restarts without any Forge registration.
        if (!event.getLevel().isClientSide() && !villager.getPersistentData().contains(COURAGE_KEY)) {
            // Do NOT use villager.getRandom() here — entities spawned in the same tick
            // (e.g. during village world-gen) share nearly identical random seeds, causing
            // all of them to draw the same value and all ending up brave or all scared.
            //
            // Instead, derive the trait from the villager's UUID, which is unique for every
            // entity regardless of when it was created.
            // (uuid.hashCode() & 0xFF) produces a value 0–255, uniformly distributed.
            // Values 0–63 ( = 64/256 = 25%) → scared. 64–255 → brave.
            boolean scared = (villager.getUUID().hashCode() & 0xFF) < 64;
            villager.getPersistentData().putInt(COURAGE_KEY, scared ? 2 : 1);
        }

        // Assign the nearest unclaimed bed as this villager's HOME on first spawn.
        // MemoryModuleType.HOME is persisted with the villager's NBT, so this only
        // runs once. findShelter() reads HOME first, so this gives every villager a
        // concrete shelter destination immediately.
        if (!event.getLevel().isClientSide()
                && villager.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                && event.getLevel() instanceof ServerLevel serverLevel) {

            BlockPos origin = villager.blockPosition();
            int radius = 48; // blocks to search for a bed

            // Build a fast-lookup set of beds already claimed by nearby villagers
            Set<BlockPos> claimedBeds = new HashSet<>();
            serverLevel.getEntitiesOfClass(Villager.class,
                    new net.minecraft.world.phys.AABB(origin).inflate(radius))
                    .forEach(other -> {
                        if (other != villager)
                            other.getBrain().getMemory(MemoryModuleType.HOME)
                                    .ifPresent(gp -> claimedBeds.add(gp.pos()));
                    });

            // Stream all HOME POIs in range, sort nearest-first, claim the first
            // unclaimed one (skip any bed another villager has already taken).
            serverLevel.getPoiManager()
                    .findAll(holder -> holder.is(PoiTypes.HOME),
                            pos -> pos.distSqr(origin) <= (double) (radius * radius),
                            origin, radius, PoiManager.Occupancy.ANY)
                    .sorted(java.util.Comparator.comparingDouble(
                            pos -> pos.distSqr(origin)))
                    .filter(bed -> !claimedBeds.contains(bed))
                    .findFirst()
                    .ifPresent(bed -> villager.getBrain().setMemory(
                            MemoryModuleType.HOME,
                            net.minecraft.core.GlobalPos.of(
                                    serverLevel.dimension(), bed.immutable())));
        }

        Pathfinding.applyVillagerWaterHazard(villager);

        // Custom goal: seek water when on fire (priority 1, independent of PanicGoal).
        villager.goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.Goal() {
            private BlockPos targetWater = null;
            {
                setFlags(java.util.EnumSet.of(Flag.MOVE));
            }

            @Override
            public boolean canUse() {
                if (!villager.isOnFire())
                    return false;
                targetWater = findNearestWater();
                return targetWater != null;
            }

            @Override
            public boolean canContinueToUse() {
                return villager.isOnFire() && villager.getNavigation().isInProgress();
            }

            @Override
            public void start() {
                if (targetWater != null)
                    villager.getNavigation().moveTo(
                            targetWater.getX(), targetWater.getY(), targetWater.getZ(), 2.0);
            }

            private BlockPos findNearestWater() {
                BlockPos origin = villager.blockPosition();
                BlockPos nearest = null;
                double bestSq = Double.MAX_VALUE;
                for (BlockPos pos : BlockPos.betweenClosed(
                        origin.offset(-16, -4, -16), origin.offset(16, 4, 16))) {
                    if (villager.level().getBlockState(pos)
                            .is(net.minecraft.world.level.block.Blocks.WATER)) {
                        double d = pos.distSqr(origin);
                        if (d < bestSq) {
                            bestSq = d;
                            nearest = pos.immutable();
                        }
                    }
                }
                return nearest;
            }
        });
    }

    // =========================================================================
    // Hurt event: record block-damage time for panic suppression
    // =========================================================================
    @SubscribeEvent
    public void onVillagerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        // directEntity == null → damage from a block/environment, not a mob or player
        if (event.getSource().getDirectEntity() == null) {
            if (villager.getNavigation().getPath() != null) {
                blockHurtTime.put(villager.getUUID(), villager.level().getGameTime());
            }
        }
    }

    // =========================================================================
    // Per-tick: panic suppression + shelter maintenance
    // =========================================================================
    // Fires BEFORE the Brain runs each tick.
    @SubscribeEvent
    public void onVillagerTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;

        UUID id = villager.getUUID();
        long now = villager.level().getGameTime();

        // ── Panic suppression (block-damage) ─────────────────────────────────
        Long hurtAt = blockHurtTime.get(id);
        if (hurtAt != null) {
            if (now - hurtAt <= PANIC_SUPPRESS_TICKS) {
                // Erase the trigger so VillagerPanicTrigger cannot fire this tick
                villager.getBrain().eraseMemory(MemoryModuleType.HURT_BY);
                villager.getBrain().eraseMemory(MemoryModuleType.IS_PANICKING);
            } else {
                blockHurtTime.remove(id);
            }
        }

        // ── Shelter maintenance ────────────────────────────────────────────
        if (shelterPos.containsKey(id)) {
            long seen = lastThreatSeen.getOrDefault(id, 0L);
            int exitDelay = isScared(villager) ? SHELTER_EXIT_SCARED : SHELTER_EXIT_NORMAL;

            // Poll whether any monster that targeted this villager is still alive
            Set<UUID> chasers = chasingMonsters.get(id);
            boolean chaserAlive = false;
            if (chasers != null && !chasers.isEmpty()
                    && villager.level() instanceof ServerLevel sl) {
                chasers.removeIf(mUUID -> {
                    net.minecraft.world.entity.Entity e = sl.getEntity(mUUID);
                    return e == null || !e.isAlive();
                });
                chaserAlive = !chasers.isEmpty();
            }

            // Stay sheltered while inside the time window OR while a chaser lives
            boolean inDanger = (now - seen < exitDelay) || chaserAlive;

            if (inDanger) {
                if (isIndoors(villager) && !hasDoorAhead(villager)) {
                    // ─ Fully inside ─────────────────────────────────────────────────────
                    // Disable AI entirely. This is the ONLY reliable fix:
                    // LivingTickEvent fires BEFORE aiStep(), so any navigation/memory
                    // suppression we do gets overwritten by Brain.tick() later every tick.
                    // setNoAi(true) skips aiStep() completely — Brain, GoalSelector, and
                    // navigation.tick() all stop running, so the villager cannot walk out.
                    // Unfreeze all villagers so they aren't stuck like statues
                    if (villager.isNoAi())
                        villager.setNoAi(false);

                    // Prevent the Brain from overriding our pacing
                    villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

                    if (villager.tickCount % 20 == 0) {
                        BlockPos sp = shelterPos.get(id);
                        if (sp != null) {
                            // Random offset within ~2 blocks of the bed
                            double dx = (villager.getRandom().nextDouble() - 0.5) * 4.0;
                            double dz = (villager.getRandom().nextDouble() - 0.5) * 4.0;
                            villager.getNavigation().moveTo(sp.getX() + dx, sp.getY(), sp.getZ() + dz, 0.6);
                        }
                    }

                    if (isScared(villager)) {
                        // Only scared villagers play the sweating animation while hiding
                        playCornerEffects(villager);
                    }

                    if (wasInsideShelter.add(id)) {
                        // First tick under a roof — close the door immediately
                        closeShelterDoor(villager);
                    } else if (villager.tickCount % 5 == 0) {
                        // Fallback: close within 0.25 s even if the transition was delayed
                        closeShelterDoor(villager);
                    }
                } else {
                    // ─ Approaching shelter ───────────────────────────────────────────────
                    // AI must be ON so navigation.tick() can actually move the villager.
                    // Re-issue moveTo() every 5 ticks to partially fight Brain redirection.
                    if (villager.isNoAi())
                        villager.setNoAi(false);
                    wasInsideShelter.remove(id);
                    if (villager.tickCount % 5 == 0 && !pendingDestination.containsKey(id)) {
                        BlockPos sp = shelterPos.get(id);
                        if (sp != null)
                            villager.getNavigation().moveTo(
                                    sp.getX(), sp.getY(), sp.getZ(), 0.8);
                    }
                }
            } else {
                // Safe — re-enable AI and clear all shelter tracking
                if (villager.isNoAi())
                    villager.setNoAi(false);
                shelterPos.remove(id);
                chasingMonsters.remove(id);
                wasInsideShelter.remove(id);
                unreachableBeds.remove(id);
            }
        }
    }

    // =========================================================================
    // Once per second: closest villager wins contested job sites
    // =========================================================================
    @SubscribeEvent
    public void onVillagerJobTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (villager.tickCount % 20 != 0)
            return;

        Optional<net.minecraft.core.GlobalPos> potentialSite = villager.getBrain()
                .getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        if (potentialSite.isEmpty())
            return;

        BlockPos sitePos = potentialSite.get().pos();
        double myDistSq = villager.blockPosition().distSqr(sitePos);

        boolean closerExists = villager.level()
                .getEntitiesOfClass(Villager.class,
                        new net.minecraft.world.phys.AABB(sitePos).inflate(64))
                .stream()
                .filter(v -> v != villager)
                .filter(v -> v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE)
                        .map(s -> s.pos().equals(sitePos)).orElse(false))
                .anyMatch(v -> v.blockPosition().distSqr(sitePos) < myDistSq);

        if (closerExists)
            villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
    }

    // =========================================================================
    // Every 10 ticks: flee from close threats and actively chasing monsters
    // =========================================================================
    // Flee is the highest-priority movement override (below fire).
    // A villager reacts when:
    // (a) any visible monster is within FLEE_RADIUS (23 blocks), OR
    // (b) any visible monster up to CHASE_DETECT_RADIUS (48 blocks) is targeting
    // them.
    // Speed tiers:
    // • RUN (speed 1.0) — monster within 18 blocks, OR being chased at any distance
    // • WALK (speed 0.5) — monster 18–23 blocks away, not actively targeting
    // villager
    @SubscribeEvent
    public void onVillagerFlee(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (villager.level().isClientSide())
            return;

        // Play cornered animation every tick while sandwiched
        if (corneredVillagers.contains(villager.getUUID()))
            playCornerEffects(villager);

        if (villager.tickCount % 10 != 0)
            return;

        // If the villager is actively sheltering, trust the Shelter+PathThreatCheck
        // combo
        // to get them to the door and safely bypass any enemies in the way!
        if (shelterPos.containsKey(villager.getUUID()))
            return;

        // Collect all monsters in the broader chase-detection radius that the
        // villager can actually see (line-of-sight through blocks).
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(CHASE_DETECT_RADIUS))
                .stream()
                .filter(m -> villager.hasLineOfSight(m))
                .toList();

        if (threats.isEmpty()) {
            corneredVillagers.remove(villager.getUUID());
            return;
        }

        Monster nearest = threats.stream()
                .min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(villager)))
                .orElse(null);
        if (nearest == null)
            return;

        // Detect whether any threat is actively targeting this villager
        boolean beingChased = threats.stream().anyMatch(m -> m.getTarget() == villager);

        // Record every chasing monster so the shelter won't release until they die
        if (beingChased) {
            UUID id = villager.getUUID();
            threats.stream()
                    .filter(m -> m.getTarget() == villager)
                    .forEach(m -> chasingMonsters
                            .computeIfAbsent(id, k -> new HashSet<>()).add(m.getUUID()));
        }

        // Only trigger flee if the threat is close enough or we're being hunted
        if (nearest.distanceToSqr(villager) > FLEE_RADIUS_SQ && !beingChased) {
            corneredVillagers.remove(villager.getUUID());
            return;
        }

        // When being chased, prioritise distance from the closest pursuer
        Monster fleeFrom = nearest;
        if (beingChased) {
            fleeFrom = threats.stream()
                    .filter(m -> m.getTarget() == villager)
                    .min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(villager)))
                    .orElse(nearest);
        }

        Vec3 fleeDir = villager.position().subtract(fleeFrom.position()).normalize();
        Vec3 fleeTarget = villager.position().add(fleeDir.scale(12));
        BlockPos fleeBlock = BlockPos.containing(fleeTarget);

        if (isLethalHazard(villager.level(), fleeBlock)) {
            // Sandwiched between threat and lethal drop/lava — freeze and sweat
            corneredVillagers.add(villager.getUUID());
            villager.getNavigation().stop();
        } else {
            corneredVillagers.remove(villager.getUUID());
            // Clear cached shelter so the villager re-seeks once they escape
            shelterPos.remove(villager.getUUID());
            // Run when close or being chased; walk in the outer ring (18–23 blocks)
            double distSq = nearest.distanceToSqr(villager);
            double speed = (distSq < FLEE_WALK_RADIUS_SQ || beingChased) ? 1.0 : 0.5;
            villager.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, speed);
        }
    }

    // =========================================================================
    // Every second (offset 3): seek and maintain shelter when threats are visible
    // =========================================================================
    @SubscribeEvent
    public void onVillagerShelterTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (villager.level().isClientSide())
            return;
        if (villager.tickCount % 20 != 3)
            return; // offset avoids bunching

        UUID id = villager.getUUID();
        long now = villager.level().getGameTime();

        // Collect visible threats in the shelter-trigger radius, plus ANY threat within
        // the blind radius.
        double scanRadius = isScared(villager) ? SHELTER_SCAN_RADIUS : 15.0;
        double blindRadiusSq = isScared(villager) ? 100.0 : 225.0; // 10 blocks for scared, 15 for brave
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(scanRadius))
                .stream()
                .filter(m -> villager.hasLineOfSight(m) || m.distanceToSqr(villager) <= blindRadiusSq)
                .toList();

        // Update last-seen timestamp and record chasers whenever threats are visible
        if (!threats.isEmpty()) {
            lastThreatSeen.put(id, now);
            // Track any monster that is actively targeting this villager;
            // the shelter will be held until each tracked monster is confirmed dead.
            threats.stream()
                    .filter(m -> m.getTarget() == villager)
                    .forEach(m -> chasingMonsters
                            .computeIfAbsent(id, k -> new HashSet<>()).add(m.getUUID()));
        }

        int exitDelay = isScared(villager) ? SHELTER_EXIT_SCARED : SHELTER_EXIT_NORMAL;

        // No visible threats — onVillagerTick owns the shelter-exit gate
        // (requires the time delay to elapse AND all chasers to be confirmed dead).
        if (threats.isEmpty())
            return;

        // Already sheltered and indoors — nothing more to do here
        if (shelterPos.containsKey(id) && isIndoors(villager))
            return;

        // Find and navigate to the nearest available shelter
        BlockPos shelter = null;
        Path path = null;
        boolean canReach = false;
        int tries = 0;

        while ((shelter = findShelter(villager)) != null && tries < 3) {
            path = villager.getNavigation().createPath(shelter, 0);
            if (path != null && path.getNodeCount() > 0) {
                net.minecraft.world.level.pathfinder.Node endNode = path.getNode(path.getNodeCount() - 1);
                if (new BlockPos(endNode.x, endNode.y, endNode.z).distSqr(shelter) <= 4.0) {
                    canReach = true;
                    break;
                }
            }
            // Cannot reach, blacklist and try another
            unreachableBeds.computeIfAbsent(id, k -> new HashSet<>()).add(shelter);
            tries++;
        }

        if (canReach && shelter != null) {
            shelterPos.put(id, shelter);
            villager.getNavigation().moveTo(path, 0.5);
        } else {
            shelterPos.remove(id);
        }
        // No reachable shelter found -> onVillagerFlee handles raw escape movement
    }

    // =========================================================================
    // Every second (offset 7): check active path for threat intersection
    // =========================================================================
    // Villagers should never willingly path through a threat zone.
    // When the upcoming path nodes pass near a monster the villager can see,
    // the path is cancelled and a perpendicular bypass is attempted.
    @SubscribeEvent
    public void onVillagerPathThreatCheck(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (villager.level().isClientSide())
            return;
        if (villager.tickCount % 20 != 7)
            return; // offset avoids bunching

        UUID id = villager.getUUID();

        Path currentPath = villager.getNavigation().getPath();
        if (currentPath == null) {
            pendingDestination.remove(id);
            return;
        }

        // Collect visible threats in the path-scan radius
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(PATH_SCAN_RADIUS))
                .stream()
                .filter(m -> villager.hasLineOfSight(m))
                .toList();

        if (threats.isEmpty()) {
            pendingDestination.remove(id);
            return;
        }

        // Close-range flee overrides path decisions — skip to avoid jitter
        boolean tooClose = threats.stream()
                .anyMatch(m -> m.distanceToSqr(villager) < FLEE_RADIUS_SQ);
        if (tooClose)
            return;

        Monster blocker = findPathBlockingThreat(currentPath, threats);
        if (blocker == null) {
            pendingDestination.remove(id);
            return;
        }

        // Save the intended destination before cancelling so the bypass can aim for it
        int nodeCount = currentPath.getNodeCount();
        if (nodeCount > 0) {
            Node end = currentPath.getNode(nodeCount - 1);
            pendingDestination.putIfAbsent(id, new BlockPos(end.x, end.y, end.z));
        }
        villager.getNavigation().stop();

        BlockPos dest = pendingDestination.get(id);
        if (dest == null || !navigateAroundThreat(villager, blocker, threats, dest)) {
            // Both bypass routes are blocked — stay put until the threat moves
            pendingDestination.remove(id);
        }
    }

    // =========================================================================
    // Shelter finding
    // =========================================================================

    /**
     * Returns the position of this villager's own bed (from the {@code HOME} brain
     * memory), or the nearest unclaimed bed POI within 30 blocks for homeless
     * villagers. Returns {@code null} if no bed exists nearby, in which case the
     * villager falls back to raw flee movement.
     */
    private static BlockPos findShelter(Villager villager) {
        Set<BlockPos> unreachable = unreachableBeds.getOrDefault(villager.getUUID(), java.util.Collections.emptySet());

        // Priority 1: the villager's own registered bed — always their true home
        Optional<net.minecraft.core.GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
        if (home.isPresent() && !unreachable.contains(home.get().pos()))
            return home.get().pos();

        BlockPos origin = villager.blockPosition();
        if (villager.level() instanceof ServerLevel serverLevel) {
            // Priority 2: random bed within a 20x20 area (10 block radius)
            List<BlockPos> closeBeds = serverLevel.getPoiManager().findAll(
                    holder -> holder.is(PoiTypes.HOME),
                    pos -> Math.abs(pos.getX() - origin.getX()) <= 10 && Math.abs(pos.getZ() - origin.getZ()) <= 10
                            && !unreachable.contains(pos),
                    origin, 10,
                    PoiManager.Occupancy.ANY)
                    .toList();

            if (!closeBeds.isEmpty()) {
                return closeBeds.get(villager.getRandom().nextInt(closeBeds.size()));
            }

            // Priority 3: nearest bed within a 50 block radius
            Optional<BlockPos> nearestBed = serverLevel.getPoiManager().find(
                    holder -> holder.is(PoiTypes.HOME),
                    pos -> pos.distSqr(origin) <= 50 * 50 && !unreachable.contains(pos),
                    origin, 50,
                    PoiManager.Occupancy.ANY);

            if (nearestBed.isPresent())
                return nearestBed.get();
        }

        return null; // No bed found — falls back to raw flee via onVillagerFlee
    }

    // =========================================================================
    // Path-threat helpers
    // =========================================================================

    /**
     * Returns the first monster whose block position falls within
     * {@link #PATH_THREAT_RADIUS_SQ} of any of the next {@link #PATH_LOOKAHEAD}
     * nodes in the path, or {@code null} if the path is clear.
     */
    private static Monster findPathBlockingThreat(Path path, List<Monster> threats) {
        int start = path.getNextNodeIndex();
        int end = Math.min(start + PATH_LOOKAHEAD, path.getNodeCount());
        for (int i = start; i < end; i++) {
            Node node = path.getNode(i);
            BlockPos nodePos = new BlockPos(node.x, node.y, node.z);
            for (Monster threat : threats) {
                if (threat.blockPosition().distSqr(nodePos) < PATH_THREAT_RADIUS_SQ)
                    return threat;
            }
        }
        return null;
    }

    /**
     * Attempts to navigate around {@code blocker} by trying two perpendicular
     * bypass waypoints (left and right of the threat, {@link #BYPASS_DIST} blocks
     * out). Prefers the waypoint that is best aligned toward the original
     * {@code dest}. Verifies the bypass path is also clear before committing.
     *
     * @return {@code true} if a safe bypass was found and navigation started
     */
    private static boolean navigateAroundThreat(Villager villager, Monster blocker,
            List<Monster> threats, BlockPos dest) {
        Vec3 villagerPos = villager.position();
        Vec3 threatPos = blocker.position();
        double dx = threatPos.x - villagerPos.x;
        double dz = threatPos.z - villagerPos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001)
            return false;

        // XZ-plane perpendicular unit vector (90° rotation)
        double perpX = -dz / len;
        double perpZ = dx / len;

        // Two candidate bypass waypoints, one on each side of the threat
        Vec3[] candidates = {
                new Vec3(threatPos.x + perpX * BYPASS_DIST, villagerPos.y,
                        threatPos.z + perpZ * BYPASS_DIST),
                new Vec3(threatPos.x - perpX * BYPASS_DIST, villagerPos.y,
                        threatPos.z - perpZ * BYPASS_DIST)
        };

        // Rank candidates: prefer the one whose direction best aligns with the
        // destination
        Vec3 toDest = Vec3.atCenterOf(dest).subtract(villagerPos).normalize();
        Vec3 best = null;
        Vec3 second = null;
        double bestDot = -Double.MAX_VALUE;
        for (Vec3 c : candidates) {
            double dot = c.subtract(villagerPos).normalize().dot(toDest);
            if (dot > bestDot) {
                second = best;
                best = c;
                bestDot = dot;
            } else {
                second = c;
            }
        }

        // Try candidates in preference order; verify each bypass path is also clear
        for (Vec3 candidate : new Vec3[] { best, second }) {
            if (candidate == null)
                continue;
            Path bypassPath = villager.getNavigation()
                    .createPath(candidate.x, candidate.y, candidate.z, 0);
            if (bypassPath != null && bypassPath.getNodeCount() > 0
                    && findPathBlockingThreat(bypassPath, threats) == null) {
                villager.getNavigation().moveTo(candidate.x, candidate.y, candidate.z, 0.5);
                return true;
            }
        }
        return false; // Both bypass routes also pass through threats
    }

    // =========================================================================
    // Lethal-hazard detection (lava / fatal fall)
    // =========================================================================
    private static boolean isLethalHazard(net.minecraft.world.level.Level level, BlockPos pos) {
        for (int dy = -1; dy <= 0; dy++) {
            if (level.getBlockState(pos.offset(0, dy, 0))
                    .is(net.minecraft.world.level.block.Blocks.LAVA))
                return true;
        }
        int fallDepth = 0;
        BlockPos check = pos.below();
        while (fallDepth < 5) {
            if (!level.getBlockState(check).isSolid()) {
                fallDepth++;
                check = check.below();
            } else
                break;
        }
        return fallDepth >= 4;
    }

    // =========================================================================
    // Corner effects (sweat particles)
    // =========================================================================
    /**
     * Spawns dripping-water "sweat" particles while a villager is cornered.
     * <ul>
     * <li>Brave villagers: one particle every <b>40 ticks</b> (2 s)</li>
     * <li>Scared villagers: one particle every <b>32 ticks</b> (~1.6 s, 20 %
     * more)</li>
     * </ul>
     */
    private static void playCornerEffects(Villager villager) {
        int sweatInterval = isScared(villager) ? 32 : 40;
        if (villager.tickCount % sweatInterval == 0
                && villager.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.DRIPPING_WATER,
                    villager.getX() + (villager.getRandom().nextDouble() - 0.5) * 0.4,
                    villager.getY() + 1.9,
                    villager.getZ() + (villager.getRandom().nextDouble() - 0.5) * 0.4,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // =========================================================================
    // Door management
    // =========================================================================
    /**
     * Closes any open {@link DoorBlock} within 4 blocks of the villager.
     * Both the lower and upper halves are closed so the door is fully shut.
     * Called every second while the villager is sheltering to keep monsters
     * outside.
     */
    private static void closeShelterDoor(Villager villager) {
        net.minecraft.world.level.Level level = villager.level();
        BlockPos origin = villager.blockPosition();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-4, -1, -4), origin.offset(4, 3, 4))) {

            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock))
                continue;
            if (!state.hasProperty(BlockStateProperties.OPEN))
                continue;
            if (!state.getValue(BlockStateProperties.OPEN))
                continue; // already closed

            // Normalise to the lower half so each physical door is processed once
            BlockPos lowerPos = (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                    ? pos.below()
                    : pos.immutable();

            // Close lower half
            BlockState lower = level.getBlockState(lowerPos);
            if (lower.getBlock() instanceof DoorBlock
                    && lower.hasProperty(BlockStateProperties.OPEN)
                    && lower.getValue(BlockStateProperties.OPEN)) {
                level.setBlock(lowerPos,
                        lower.setValue(BlockStateProperties.OPEN, false), 10);
            }

            // Close upper half
            BlockPos upperPos = lowerPos.above();
            BlockState upper = level.getBlockState(upperPos);
            if (upper.getBlock() instanceof DoorBlock
                    && upper.hasProperty(BlockStateProperties.OPEN)
                    && upper.getValue(BlockStateProperties.OPEN)) {
                level.setBlock(upperPos,
                        upper.setValue(BlockStateProperties.OPEN, false), 10);
            }

            // Play the vanilla door-close sound at server level
            // 1012 = wooden door close, 1011 = iron door close
            boolean isIron = state.is(net.minecraft.world.level.block.Blocks.IRON_DOOR);
            level.levelEvent(null, isIron ? 1011 : 1012, lowerPos, 0);

            break; // close at most one door per call
        }
    }

    // =========================================================================
    // Server start / Client events
    // =========================================================================
    // =========================================================================
    // Debug: visualize courage trait with particles (showVillagerCourage rule)
    // =========================================================================
    @SubscribeEvent
    public void onVillagerCourageDisplay(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (!(villager.level() instanceof ServerLevel serverLevel))
            return;
        if (!serverLevel.getGameRules().getBoolean(SHOW_VILLAGER_COURAGE))
            return;
        if (villager.tickCount % 40 != 0)
            return; // burst every 2 s

        double x = villager.getX();
        double y = villager.getY() + 2.2; // float above the head
        double z = villager.getZ();

        if (isScared(villager)) {
            // Scared → soul particles (pale blue, spooky)
            serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.SOUL,
                    x, y, z, 4, 0.2, 0.1, 0.2, 0.0);
        } else {
            // Brave → happy-villager sparkles (bright green)
            serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    x, y, z, 4, 0.2, 0.1, 0.2, 0.0);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }
}
