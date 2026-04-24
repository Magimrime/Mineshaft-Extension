package net.magimrime.villagerai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class VillagerFleeHandler {

    // =========================================================================
    // Every 10 ticks: flee from close threats and actively chasing monsters
    // =========================================================================
    @SubscribeEvent
    public void onVillagerFlee(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (villager.level().isClientSide())
            return;

        // Play cornered animation every tick while sandwiched
        if (VillagerState.corneredVillagers.contains(villager.getUUID()))
            VillagerHelpers.playCornerEffects(villager);

        if (villager.tickCount % 10 != 0)
            return;

        // If the villager is actively sheltering, trust the Shelter+PathThreatCheck
        // combo to get them to the door and safely bypass any enemies in the way!
        if (VillagerState.shelterPos.containsKey(villager.getUUID()))
            return;

        // Collect all monsters in the broader chase-detection radius that the
        // villager can actually see (line-of-sight through blocks).
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(VillagerState.CHASE_DETECT_RADIUS))
                .stream()
                .filter(m -> villager.hasLineOfSight(m))
                .toList();

        if (threats.isEmpty()) {
            VillagerState.corneredVillagers.remove(villager.getUUID());
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
                    .forEach(m -> VillagerState.chasingMonsters
                            .computeIfAbsent(id, k -> new HashSet<>()).add(m.getUUID()));
        }

        // Only trigger flee if the threat is close enough or we're being hunted
        if (nearest.distanceToSqr(villager) > VillagerState.FLEE_RADIUS_SQ && !beingChased) {
            VillagerState.corneredVillagers.remove(villager.getUUID());
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
            VillagerState.corneredVillagers.add(villager.getUUID());
            villager.getNavigation().stop();
        } else {
            VillagerState.corneredVillagers.remove(villager.getUUID());
            // Clear cached shelter so the villager re-seeks once they escape
            VillagerState.shelterPos.remove(villager.getUUID());
            // Run when close or being chased; walk in the outer ring (18–23 blocks)
            double distSq = nearest.distanceToSqr(villager);
            double speed = (distSq < VillagerState.FLEE_WALK_RADIUS_SQ || beingChased) ? 1.0 : 0.5;
            villager.getNavigation().moveTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, speed);
        }
    }

    // =========================================================================
    // Every second (offset 7): check active path for threat intersection
    // =========================================================================
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
            VillagerState.pendingDestination.remove(id);
            return;
        }

        // Collect visible threats in the path-scan radius
        List<Monster> threats = villager.level()
                .getEntitiesOfClass(Monster.class,
                        villager.getBoundingBox().inflate(VillagerState.PATH_SCAN_RADIUS))
                .stream()
                .filter(m -> villager.hasLineOfSight(m))
                .toList();

        if (threats.isEmpty()) {
            VillagerState.pendingDestination.remove(id);
            return;
        }

        // Close-range flee overrides path decisions — skip to avoid jitter
        boolean tooClose = threats.stream()
                .anyMatch(m -> m.distanceToSqr(villager) < VillagerState.FLEE_RADIUS_SQ);
        if (tooClose)
            return;

        Monster blocker = findPathBlockingThreat(currentPath, threats);
        if (blocker == null) {
            VillagerState.pendingDestination.remove(id);
            return;
        }

        // Save the intended destination before cancelling so the bypass can aim for it
        int nodeCount = currentPath.getNodeCount();
        if (nodeCount > 0) {
            Node end = currentPath.getNode(nodeCount - 1);
            VillagerState.pendingDestination.putIfAbsent(id, new BlockPos(end.x, end.y, end.z));
        }
        villager.getNavigation().stop();

        BlockPos dest = VillagerState.pendingDestination.get(id);
        if (dest == null || !navigateAroundThreat(villager, blocker, threats, dest)) {
            // Both bypass routes are blocked — stay put until the threat moves
            VillagerState.pendingDestination.remove(id);
        }
    }

    // =========================================================================
    // Path-threat helpers
    // =========================================================================
    private static Monster findPathBlockingThreat(Path path, List<Monster> threats) {
        int start = path.getNextNodeIndex();
        int end = Math.min(start + VillagerState.PATH_LOOKAHEAD, path.getNodeCount());
        for (int i = start; i < end; i++) {
            Node node = path.getNode(i);
            BlockPos nodePos = new BlockPos(node.x, node.y, node.z);
            for (Monster threat : threats) {
                if (threat.blockPosition().distSqr(nodePos) < VillagerState.PATH_THREAT_RADIUS_SQ)
                    return threat;
            }
        }
        return null;
    }

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
                new Vec3(threatPos.x + perpX * VillagerState.BYPASS_DIST, villagerPos.y,
                        threatPos.z + perpZ * VillagerState.BYPASS_DIST),
                new Vec3(threatPos.x - perpX * VillagerState.BYPASS_DIST, villagerPos.y,
                        threatPos.z - perpZ * VillagerState.BYPASS_DIST)
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
}
