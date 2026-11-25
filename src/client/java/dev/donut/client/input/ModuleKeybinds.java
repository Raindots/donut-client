package dev.donut.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The click GUI edits the same mappings exposed in Minecraft's Controls screen. */
public final class ModuleKeybinds {
    public static final int FIRST_MODULE = 2;
    public static final int CRYSTAL_AURA = 15;
    public static final int AUTO_TOTEM = 16;
    public static final int ANCHOR_MACRO = 17;
    public static final int TRIGGERBOT = 18;
    public static final int AIM_ASSIST = 19;
    public static final int PLAYER_ESP = 20;
    public static final int TIME_CHANGER = 21;
    public static final int MODULE_COUNT = TIME_CHANGER - FIRST_MODULE + 1;
    public static final int MODULE_LIMIT = FIRST_MODULE + MODULE_COUNT;
    private static final KeyMapping[] BINDINGS = new KeyMapping[MODULE_LIMIT];
    private static final boolean[] pressed = new boolean[BINDINGS.length];
    private static final Set<InputConstants.Key> heldInputs = new HashSet<>();
    private static boolean discardNextTick;

    private ModuleKeybinds() { }

    public static void initialize(KeyMapping freecam, KeyMapping autoBuilder, KeyMapping chunkFinder,
                                  KeyMapping fullbright, KeyMapping fly, KeyMapping progressBar,
                                  KeyMapping nameHider, KeyMapping hotbar, KeyMapping amethyst, KeyMapping lightDebug,
                                  KeyMapping soundDetector, KeyMapping blockEsp, KeyMapping staffDetector,
                                  KeyMapping crystalAura, KeyMapping autoTotem, KeyMapping anchorMacro,
                                  KeyMapping triggerbot, KeyMapping aimAssist, KeyMapping playerEsp, KeyMapping timeChanger) {
        Arrays.fill(BINDINGS, null);
        BINDINGS[2] = freecam;
        BINDINGS[3] = autoBuilder;
        BINDINGS[4] = chunkFinder;
        BINDINGS[5] = fullbright;
        BINDINGS[6] = fly;
        BINDINGS[7] = progressBar;
        BINDINGS[8] = nameHider;
        BINDINGS[9] = hotbar;
        BINDINGS[10] = amethyst;
        BINDINGS[11] = lightDebug;
        BINDINGS[12] = soundDetector;
        BINDINGS[13] = blockEsp;
        BINDINGS[14] = staffDetector;
        BINDINGS[CRYSTAL_AURA] = crystalAura;
        BINDINGS[AUTO_TOTEM] = autoTotem;
        BINDINGS[ANCHOR_MACRO] = anchorMacro;
        BINDINGS[TRIGGERBOT] = triggerbot;
        BINDINGS[AIM_ASSIST] = aimAssist;
        BINDINGS[PLAYER_ESP] = playerEsp;
        BINDINGS[TIME_CHANGER] = timeChanger;
        Arrays.fill(pressed, false);
        heldInputs.clear();
        discardNextTick = false;
    }

    public static KeyMapping mapping(int index) { return BINDINGS[index]; }

    public static Map<String, String> profileSnapshot() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (KeyMapping mapping : BINDINGS) {
            if (mapping != null) keys.put(mapping.getName(), mapping.saveString());
        }
        return keys;
    }

    public static Map<KeyMapping, InputConstants.Key> resolveProfile(Map<String, String> savedKeys) {
        Map<KeyMapping, InputConstants.Key> keys = new LinkedHashMap<>();
        for (KeyMapping mapping : BINDINGS) {
            if (mapping == null || !savedKeys.containsKey(mapping.getName())) continue;
            InputConstants.Key key;
            try {
                key = InputConstants.getKey(savedKeys.get(mapping.getName()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid saved keybind for " + mapping.getName() + ".", e);
            }
            if (!key.equals(InputConstants.UNKNOWN) && switch (key.getType()) {
                case KEYSYM -> !validKeySymbol(key.getValue());
                case MOUSE -> key.getValue() < 0 || key.getValue() > GLFW.GLFW_MOUSE_BUTTON_LAST;
                case SCANCODE -> key.getValue() < 0;
            }) {
                throw new IllegalArgumentException("Invalid saved keybind for " + mapping.getName() + ".");
            }
            keys.put(mapping, key);
        }
        return keys;
    }

    public static void applyProfile(Minecraft client, Map<KeyMapping, InputConstants.Key> keys) {
        if (keys.isEmpty()) return;
        suppressUntilReleased(client);
        keys.forEach(KeyMapping::setKey);
        Arrays.fill(pressed, false);
        KeyMapping.resetMapping();
        suppressUntilReleased(client);
        client.options.save();
    }

    public static void setKey(Minecraft client, int index, InputConstants.Key key) {
        KeyMapping mapping = mapping(index);
        if (mapping == null) return;
        mapping.setKey(key);
        pressed[index] = false;
        mapping.setDown(false);
        while (mapping.consumeClick()) { }
        KeyMapping.resetMapping();
        client.options.save();
        if (!key.equals(InputConstants.UNKNOWN)) heldInputs.add(key);
        discardNextTick = true;
    }

    /** Drain every tick, even in menus; native key repeat must not toggle a module repeatedly. */
    public static boolean consumePress(Minecraft client, int index) {
        KeyMapping mapping = mapping(index);
        if (mapping == null) return false;
        boolean clicked = false;
        while (mapping.consumeClick()) clicked = true;
        boolean firstPress = clicked && !pressed[index];
        // A short press released between ticks still has a queued click and fires once.
        pressed[index] = isHeld(client, InputConstants.getKey(mapping.saveString())) && (pressed[index] || clicked);
        return firstPress;
    }

    /** Keep the input that assigned a binding or closed the menu from toggling a module. */
    public static void suppressUntilReleased(Minecraft client) {
        for (KeyMapping mapping : BINDINGS) {
            if (mapping == null) continue;
            rememberHeld(client, mapping);
            mapping.setDown(false);
            while (mapping.consumeClick()) { }
        }
        KeyMapping open = KeyMapping.get("key.donut.open_gui");
        if (open != null) rememberHeld(client, open);
        discardNextTick = true;
    }

    private static void rememberHeld(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        if (isHeld(client, key)) heldInputs.add(key);
    }

    /** Call once each client tick, and consume queued mapping clicks even when blocked. */
    public static boolean inputBlocked(Minecraft client) {
        boolean blocked = client.screen != null || discardNextTick || !heldInputs.isEmpty();
        discardNextTick = false;
        heldInputs.removeIf(key -> !isHeld(client, key));
        return blocked;
    }

    private static boolean isHeld(Minecraft client, InputConstants.Key key) {
        if (key.equals(InputConstants.UNKNOWN)) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow(), key.getValue());
        }
        // GLFW exposes polling by key symbol, so resolve unusual scancode-only keys first.
        for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
            if (validKeySymbol(code) && GLFW.glfwGetKeyScancode(code) == key.getValue()
                    && InputConstants.isKeyDown(client.getWindow(), code)) return true;
        }
        return false;
    }

    private static boolean validKeySymbol(int code) {
        return code == 32 || code == 39 || code >= 44 && code <= 57 || code == 59 || code == 61
                || code >= 65 && code <= 93 || code == 96 || code >= 161 && code <= 162
                || code >= 256 && code <= 269 || code >= 280 && code <= 284
                || code >= 290 && code <= 314 || code >= 320 && code <= 336 || code >= 340 && code <= 348;
    }
}
