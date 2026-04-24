package net.magimrime.smartervillagers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VillagerState {
    public static final String COURAGE_KEY = "courage";

    public static final Map<UUID, Long> blockHurtTime = new HashMap<>();
    public static final int PANIC_SUPPRESS_TICKS = 100;

    public static final Set<UUID> corneredVillagers = new HashSet<>();
    public static final Map<UUID, BlockPos> shelterPos = new HashMap<>();
    public static final Map<UUID, Long> lastThreatSeen = new HashMap<>();
    public static final Map<UUID, BlockPos> pendingDestination = new HashMap<>();
    public static final Map<UUID, Set<UUID>> chasingMonsters = new HashMap<>();
    public static final Set<UUID> wasInsideShelter = new HashSet<>();
    public static final Map<UUID, Set<BlockPos>> unreachableBeds = new HashMap<>();

    public static final int SHELTER_EXIT_NORMAL = 25;
    public static final int SHELTER_EXIT_SCARED = 1200;
    public static final double FLEE_RADIUS_SQ = 23.0 * 23.0;
    public static final double FLEE_WALK_RADIUS_SQ = 30.0 * 30.0;
    public static final double CHASE_DETECT_RADIUS = 48.0;
    public static final double SHELTER_SCAN_RADIUS = 32.0;
    public static final double PATH_SCAN_RADIUS = 36.0;
    public static final double PATH_THREAT_RADIUS_SQ = 4.5 * 4.5;
    public static final int PATH_LOOKAHEAD = 14;
    public static final double BYPASS_DIST = 7.0;
}
