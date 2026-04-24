package net.magimrime.smartervillagers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class VillagerEventHandler {

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
        if (!event.getLevel().isClientSide() && !villager.getPersistentData().contains(VillagerState.COURAGE_KEY)) {
            // Do NOT use villager.getRandom() here — entities spawned in the same tick
            // (e.g. during village world-gen) share nearly identical random seeds, causing
            // all of them to draw the same value and all ending up brave or all scared.
            //
            // Instead, derive the trait from the villager's UUID, which is unique for every
            // entity regardless of when it was created.
            // (uuid.hashCode() & 0xFF) produces a value 0–255, uniformly distributed.
            // Values 0–63 ( = 64/256 = 25%) → scared. 64–255 → brave.
            boolean scared = (villager.getUUID().hashCode() & 0xFF) < 64;
            villager.getPersistentData().putInt(VillagerState.COURAGE_KEY, scared ? 2 : 1);
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
                VillagerState.blockHurtTime.put(villager.getUUID(), villager.level().getGameTime());
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
    // Debug: visualize courage trait with particles (showVillagerCourage rule)
    // =========================================================================
    @SubscribeEvent
    public void onVillagerCourageDisplay(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (!(villager.level() instanceof ServerLevel serverLevel))
            return;
        if (!serverLevel.getGameRules().getBoolean(VillagerAI.SHOW_VILLAGER_COURAGE))
            return;
        if (villager.tickCount % 40 != 0)
            return; // burst every 2 s

        double x = villager.getX();
        double y = villager.getY() + 2.2; // float above the head
        double z = villager.getZ();

        if (VillagerHelpers.isScared(villager)) {
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
}
