package net.magimrime.villagerai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerCalmDown;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraftforge.eventbus.api.IEventBus;

public class Pathfinding {
    public static void applyVillagerWaterHazard(Mob mob) {
        mob.setPathfindingMalus(PathType.WATER, 2.0f);
        mob.setPathfindingMalus(PathType.WATER_BORDER, 1.0f);
        mob.setPathfindingMalus(PathType.DAMAGE_FIRE, 30.0f);
        mob.setPathfindingMalus(PathType.DANGER_FIRE, 2.0f);
        mob.setPathfindingMalus(PathType.LEAVES, 3.0f);
        // -1.0f = completely forbidden: the pathfinder will never route through lava
        mob.setPathfindingMalus(PathType.LAVA, -1.0f);
    }

    public static void register(IEventBus eventBus) {
    }
}

