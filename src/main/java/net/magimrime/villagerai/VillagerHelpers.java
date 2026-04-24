package net.magimrime.villagerai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

public class VillagerHelpers {

    /** True if this villager was born with the scared personality (25 % chance). */
    public static boolean isScared(Villager v) {
        return v.getPersistentData().getInt(VillagerState.COURAGE_KEY) == 2;
    }

    /**
     * Spawns dripping-water "sweat" particles while a villager is cornered.
     * <ul>
     * <li>Brave villagers: one particle every <b>40 ticks</b> (2 s)</li>
     * <li>Scared villagers: one particle every <b>32 ticks</b> (~1.6 s, 20 %
     * more)</li>
     * </ul>
     */
    public static void playCornerEffects(Villager villager) {
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
}
