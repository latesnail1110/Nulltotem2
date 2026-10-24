package net.nullmod.totem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Right-click = use selected ability. Sneak + right-click = cycle ability. */
public class NullTotemItem extends Item {
    public NullTotemItem(Properties props) { super(props); }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        if (sp.isShiftKeyDown()) NullManager.cycle(sp);
        else NullManager.activate(sp, NullManager.selected(sp));
        return InteractionResult.CONSUME;
    }
}
