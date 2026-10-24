package net.nullmod.totem.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.phys.HitResult;
import net.nullmod.totem.NullManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets NULL cannon skulls (vanilla blue WitherSkull entities, tagged) use our controlled impact instead of the vanilla explosion. */
@Mixin(WitherSkull.class)
public abstract class WitherSkullMixin {
    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void nulltotem$onHit(HitResult hit, CallbackInfo ci) {
        if (NullManager.handleSkullHit((WitherSkull) (Object) this, hit)) ci.cancel();
    }
}
