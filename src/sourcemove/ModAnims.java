package sourcemove;

import java.io.File;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import zombie.ZomboidFileSystem;
import zombie.asset.Asset;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimNode;
import zombie.core.skinnedmodel.advancedanimation.AnimNodeAsset;
import zombie.core.skinnedmodel.advancedanimation.AnimNodeAssetManager;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;

/** Tells animation nodes added by other mods (bikes, skateboards) from the game's own. */
final class ModAnims {
    private ModAnims() {}

    private static final Map<AnimNode, Boolean> modded = new IdentityHashMap<>();
    private static int tableSize = -1;
    private static String media;

    /** The strongest node on the root layer came from a mod. */
    static boolean playing(IsoGameCharacter c) {
        AdvancedAnimator aa = c.getAdvancedAnimator();
        if (aa == null || aa.getRootLayer() == null) return false;
        List<LiveAnimNode> live = aa.getRootLayer().getLiveAnimNodes();
        LiveAnimNode top = null;
        for (int i = 0; i < live.size(); i++) {
            LiveAnimNode n = live.get(i);
            if (n != null && n.isActive() && (top == null || n.getWeight() > top.getWeight())) top = n;
        }
        return top != null && isModded(top.getSourceNode());
    }

    private static boolean isModded(AnimNode node) {
        if (node == null) return false;
        Boolean m = modded.get(node);
        if (m == null) {
            refresh();
            m = modded.get(node);
        }
        return m != null && m;
    }

    /** Rescans the loaded node files when their count changes. */
    private static void refresh() {
        Map<String, Asset> table = AnimNodeAssetManager.instance.getAssetTable();
        if (table.size() == tableSize) return;
        tableSize = table.size();
        if (media == null) media = canonical(ZomboidFileSystem.instance.getMediaRootFile());
        for (Asset a : table.values()) {
            if (a instanceof AnimNodeAsset na && na.animNode != null) modded.put(na.animNode, fromMod(a.getPath().getPath()));
        }
    }

    private static boolean fromMod(String path) {
        String p = canonical(new File(path));
        if (media != null && p != null) return !p.startsWith(media);
        return path.replace('\\', '/').toLowerCase().contains("/mods/");
    }

    private static String canonical(File f) {
        try {
            return f == null ? null : f.getCanonicalPath().replace('\\', '/').toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }
}
