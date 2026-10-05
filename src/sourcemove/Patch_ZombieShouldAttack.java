package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Vanilla won't start an attack beyond 0.2 levels; same reach rule as Patch_ZombieReach. */
@Patch(className = "zombie.characters.IsoZombie", methodName = "getShouldAttack")
public class Patch_ZombieShouldAttack {
    @Patch.OnEnter
    public static void enter(@Patch.This IsoGameCharacter zombie) {
        Zombies.onZombieAttackEnter(zombie);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Zombies.onZombieAttackExit();
    }
}
