package sourcemove;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/**
 * AttackState.triggerPlayerReaction only lands a zombie hit when |zombie.z - target.z| < 0.2 levels, so any
 * jump made you untouchable. While it runs, present the player at the zombie's height if their feet are
 * within grab reach above it; restored on exit, nothing else sees the change.
 */
@Patch(className = "zombie.ai.states.AttackState", methodName = "triggerPlayerReaction")
public class Patch_ZombieReach {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(1) IsoGameCharacter owner) {
        Mover.onZombieAttackEnter(owner);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Mover.onZombieAttackExit();
    }
}
