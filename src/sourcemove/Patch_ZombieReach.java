package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Vanilla only lands hits within 0.2 levels; count you at the zombie's height while in grab reach. */
@Patch(className = "zombie.ai.states.AttackState", methodName = "triggerPlayerReaction")
public class Patch_ZombieReach {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(1) IsoGameCharacter owner) {
        Zombies.onZombieAttackEnter(owner);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Zombies.onZombieAttackExit();
    }
}
