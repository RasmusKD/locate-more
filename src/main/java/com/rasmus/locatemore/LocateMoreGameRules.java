package com.rasmus.locatemore;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * The one knob that changes observable gameplay, as a per-world, live,
 * tab-completed gamerule. The JSON key stays as the operator's hard kill
 * switch: the effective value is the AND of both, so a server that must be
 * byte-vanilla everywhere sets the config once and no in-game admin can
 * override it. Flipping either restores vanilla parity.
 *
 * This is the mod's single deliberate exception to zero persistent state:
 * a gamerule records user intent in level.dat. Removing the mod leaves an
 * unknown gamerule behind, which vanilla ignores harmlessly.
 */
public final class LocateMoreGameRules {

    public static final GameRule<Boolean> EXACT_LOCATE = GameRuleBuilder.forBoolean(true)
            .category(GameRuleCategory.MISC)
            .buildAndRegister(Identifier.fromNamespaceAndPath("locatemore", "exact_locate"));

    private LocateMoreGameRules() {
    }

    /** Effective switch for the vanilla call sites (mixin path). */
    public static boolean enabled(ServerLevel level) {
        return Config.improveVanillaLocate && level.getGameRules().get(EXACT_LOCATE);
    }

    /** Class-load trigger so registration happens during mod init. */
    static void init() {
    }
}
