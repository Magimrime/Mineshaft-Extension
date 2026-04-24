package net.magimrime.smartervillagers;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.GameRules;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
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

@Mod(VillagerAI.MODID)
public class VillagerAI {

    public static final String MODID = "smartervillagers";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final GameRules.Key<GameRules.BooleanValue> SHOW_VILLAGER_COURAGE = GameRules.register(
            "showVillagerCourage",
            GameRules.Category.MISC,
            GameRules.BooleanValue.create(false));

    public VillagerAI() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        // Register behavior handlers
        MinecraftForge.EVENT_BUS.register(new VillagerShelterHandler());
        MinecraftForge.EVENT_BUS.register(new VillagerFleeHandler());
        MinecraftForge.EVENT_BUS.register(new VillagerEventHandler());

        Pathfinding.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
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
