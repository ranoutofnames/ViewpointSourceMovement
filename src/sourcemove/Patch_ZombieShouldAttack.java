package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/**
 * IsoZombie.getShouldAttack (the "battack" anim variable) refuses to start an attack when
 * |target.z - zombie.z| >= 0.2 levels, so a zombie never swings at you mid-jump. Same treatment as
 * {@link Patch_ZombieReach}: within grab reach, present the player at the zombie's height while it runs.
 */
@Patch(className = "zombie.characters.IsoZombie", methodName = "getShouldAttack")
public class Patch_ZombieShouldAttack {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoGameCharacter zombie) {
        Mover.onZombieAttackEnter(zombie);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Mover.onZombieAttackExit();
    }
}
