package net.magimrime.villagerai;

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
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class VillagerShelterHandler {

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
    public static boolean isIndoors(Villager villager) {
        // canSeeSky() returns false the moment ANY block (slab, stair, glass, plank …)
        // obstructs the sky directly above — exactly what "under a roof" means.
        return !villager.level().canSeeSky(villager.blockPosition().above());
    }

    /**
     * Checks if the villager is currently standing in a door, or if their active
     * path requires them to walk through a door ahead of them.
     */
    public static boolean hasDoorAhead(Villager villager) {
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

    /**
     * Closes any open {@link DoorBlock} within 4 blocks of the villager.
     * Both the lower and upper halves are closed so the door is fully shut.
     * Called every second while the villager is sheltering to keep monsters
     * outside.
     */
    public static void closeShelterDoor(Villager villager) {
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

    /**
     * Returns the position of this villager's own bed (from the {@code HOME} brain
     * memory), or the nearest unclaimed bed POI within 50 blocks for homeless
     * villagers. Returns {@code null} if no bed exists nearby, in which case the
     * villager falls back to raw flee movement.
     */
    public static BlockPos findShelter(Villager villager) {
        Set<BlockPos> unreachable = VillagerState.unreachableBeds.getOrDefault(villager.getUUID(), java.util.Collections.emptySet());

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
        Long hurtAt = VillagerState.blockHurtTime.get(id);
        if (hurtAt != null) {
            if (now - hurtAt <= VillagerState.PANIC_SUPPRESS_TICKS) {
                // Erase the trigger so VillagerPanicTrigger cannot fire this tick
                villager.getBrain().eraseMemory(MemoryModuleType.HURT_BY);
                villager.getBrain().eraseMemory(MemoryModuleType.IS_PANICKING);
            } else {
                VillagerState.blockHurtTime.remove(id);
            }
        }

        // ── Shelter maintenance ────────────────────────────────────────────
        if (VillagerState.shelterPos.containsKey(id)) {
            long seen = VillagerState.lastThreatSeen.getOrDefault(id, 0L);
            int exitDelay = VillagerHelpers.isScared(villager) ? VillagerState.SHELTER_EXIT_SCARED : VillagerState.SHELTER_EXIT_NORMAL;

            // Poll whether any monster that targeted this villager is still alive
            Set<UUID> chasers = VillagerState.chasingMonsters.get(id);
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
            boolean inDanger = (now - seen <= exitDelay) || chaserAlive;

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
                        BlockPos sp = VillagerState.shelterPos.get(id);
                        if (sp != null) {
                            // Random offset within ~2 blocks of the bed
                            double dx = (villager.getRandom().nextDouble() - 0.5) * 4.0;
                            double dz = (villager.getRandom().nextDouble() - 0.5) * 4.0;
                            villager.getNavigation().moveTo(sp.getX() + dx, sp.getY(), sp.getZ() + dz, 0.6);
                        }
                    }

                    if (VillagerHelpers.isScared(villager)) {
                        // Only scared villagers play the sweating animation while hiding
                        VillagerHelpers.playCornerEffects(villager);
                    }

                    if (VillagerState.wasInsideShelter.add(id)) {
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
                    VillagerState.wasInsideShelter.remove(id);
                    if (villager.tickCount % 5 == 0 && !VillagerState.pendingDestination.containsKey(id)) {
                        BlockPos sp = VillagerState.shelterPos.get(id);
                        if (sp != null)
                            villager.getNavigation().moveTo(
                                    sp.getX(), sp.getY(), sp.getZ(), 0.8);
                    }
                }
            } else {
                // Safe — re-enable AI and clear all shelter tracking
                if (villager.isNoAi())
                    villager.setNoAi(false);
                VillagerState.shelterPos.remove(id);
                VillagerState.chasingMonsters.remove(id);
                VillagerState.wasInsideShelter.remove(id);
                VillagerState.unreachableBeds.remove(id);
            }
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
        double scanRadius = VillagerHelpers.isScared(villager) ? VillagerState.SHELTER_SCAN_RADIUS : 15.0;
        double blindRadiusSq = VillagerHelpers.isScared(villager) ? 100.0 : 225.0; // 10 blocks for scared, 15 for brave
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(scanRadius))
                .stream()
                .filter(m -> villager.hasLineOfSight(m) || m.distanceToSqr(villager) <= blindRadiusSq)
                .toList();

        // Update last-seen timestamp and record chasers whenever threats are visible
        if (!threats.isEmpty()) {
            VillagerState.lastThreatSeen.put(id, now);
            // Track any monster that is actively targeting this villager;
            // the shelter will be held until each tracked monster is confirmed dead.
            threats.stream()
                    .filter(m -> m.getTarget() == villager)
                    .forEach(m -> VillagerState.chasingMonsters
                            .computeIfAbsent(id, k -> new HashSet<>()).add(m.getUUID()));
        }

        int exitDelay = VillagerHelpers.isScared(villager) ? VillagerState.SHELTER_EXIT_SCARED : VillagerState.SHELTER_EXIT_NORMAL;

        // No visible threats — onVillagerTick owns the shelter-exit gate
        // (requires the time delay to elapse AND all chasers to be confirmed dead).
        if (threats.isEmpty())
            return;

        // Already sheltered and indoors — nothing more to do here
        if (VillagerState.shelterPos.containsKey(id) && isIndoors(villager))
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
            VillagerState.unreachableBeds.computeIfAbsent(id, k -> new HashSet<>()).add(shelter);
            tries++;
        }

        if (canReach && shelter != null) {
            VillagerState.shelterPos.put(id, shelter);
            villager.getNavigation().moveTo(path, 0.5);
        } else {
            VillagerState.shelterPos.remove(id);
        }
        // No reachable shelter found -> onVillagerFlee handles raw escape movement
    }
}
