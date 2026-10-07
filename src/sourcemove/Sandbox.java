package sourcemove;

import zombie.SandboxOptions;
import zombie.config.BooleanConfigOption;
import zombie.config.ConfigOption;
import zombie.config.DoubleConfigOption;
import zombie.config.IntegerConfigOption;

/** Our Sandbox options, read live from the game's own option objects so admin edits apply at once. */
final class Sandbox {
    private Sandbox() {}

    static final String P = "ViewpointSourceMovement.";

    static boolean bool(String name, boolean def) {
        return option(name) instanceof BooleanConfigOption o ? o.getValue() : def;
    }

    static double num(String name, double def) {
        ConfigOption o = option(name);
        if (o instanceof DoubleConfigOption d) return d.getValue();
        // Enums are integer options too.
        if (o instanceof IntegerConfigOption i) return i.getValue();
        return def;
    }

    private static ConfigOption option(String name) {
        SandboxOptions.SandboxOption o = SandboxOptions.instance.getOptionByName(name);
        return o == null ? null : o.asConfigOption();
    }
}
