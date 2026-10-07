package sourcemove;

import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.random.Rand;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/** Coming down on barbed wire cuts you, snags you and trips you when you land. */
final class Barbs {
    private Barbs() {}

    /** Speed kept when the wire catches you. */
    private static final double SNAG_KEEP = 0.25;

    private static double lastZ = Double.NaN;
    static boolean snagged;

    /** After each falling update. */
    static void update(IsoGameCharacter c, boolean air, boolean grounded, boolean landed) {
        double z = c.getZ(), prev = lastZ;
        lastZ = z;
        if (!Cfg.barbedWire()) {
            snagged = false;
            return;
        }
        if (landed && snagged) trip(c);
        if (grounded) {
            snagged = false;
            return;
        }
        if (snagged || !air || Double.isNaN(prev) || z >= prev) return;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return;
        double top = Ledges.barbedTopUnder(cell, c.getX(), c.getY(), (int) Math.floor(z), Cfg.fenceFooting());
        if (top < 0 || prev < top || z >= top) return;
        snagged = true;
        Mover.vel.x *= SNAG_KEEP;
        Mover.vel.y *= SNAG_KEEP;
        cut(c);
        if (Cfg.wireHud) Wire.log(String.format("came down on barbed wire at %.2f", z));
    }

    /** One laceration, legs more often than hands, clothing may stop it. */
    private static void cut(IsoGameCharacter c) {
        if (c.isGodMod()) return;
        BloodBodyPartType part = Rand.Next(3) == 0
                ? BloodBodyPartType.FromIndex(Rand.Next(BloodBodyPartType.Hand_L.index(), BloodBodyPartType.UpperArm_L.index()))
                : BloodBodyPartType.FromIndex(Rand.Next(BloodBodyPartType.UpperLeg_L.index(), BloodBodyPartType.Back.index()));
        c.addHole(part);
        if (Rand.Next(100) >= c.getBodyPartClothingDefense(part.index(), false, false)) {
            c.addBlood(part, true, false, false);
            BodyPart bp = c.getBodyDamage().getBodyPart(BodyPartType.FromIndex(part.index()));
            bp.setCut(true);
            if (c instanceof IsoPlayer p) p.playerVoiceSound("PainFromLacerate");
        }
        if (c instanceof IsoPlayer p) p.syncVisuals();
    }

    /** Vanilla's sprint trip. */
    private static void trip(IsoGameCharacter c) {
        Mover.vel.x = Mover.vel.y = 0;
        c.setVariable("BumpDone", false);
        c.clearVariable("BumpFallType");
        c.setBumpType("trippingFromSprint");
        c.setBumpFall(true);
        c.setBumpFallType("pushedFront");
        c.getActionContext().reportEvent("wasBumped");
    }
}
