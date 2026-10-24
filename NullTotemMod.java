package net.nullmod.totem;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NullTotemMod implements ModInitializer {
    public static final String MOD_ID = "nulltotem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Item NULL_TOTEM;

    @Override
    public void onInitialize() {
        NullConfig.load();

        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "null_totem"));
        NULL_TOTEM = Registry.register(BuiltInRegistries.ITEM, key,
                new NullTotemItem(new Item.Properties().setId(key).stacksTo(1).rarity(Rarity.EPIC)));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(e -> e.accept(NULL_TOTEM));

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> NullCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(NullData::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { NullData.saveNow(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> NullManager.reset());
        ServerTickEvents.END_SERVER_TICK.register(NullManager::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> NullManager.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NullManager.onDisconnect(handler.getPlayer()));

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p) NullManager.onAllowDeath(p);
            return true;                                   // never cancels a death
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) NullManager.onAfterDeath(p);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> NullManager.onAfterRespawn(newPlayer, alive));

        LOGGER.info("Totem of NULL - Ultimate Edition loaded.");
    }
}
