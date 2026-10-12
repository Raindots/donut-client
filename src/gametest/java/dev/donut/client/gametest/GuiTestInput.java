package dev.donut.client.gametest;

import dev.donut.client.gui.DonutScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Locates rendered controls, then exercises the same input handlers used by Minecraft. */
final class GuiTestInput {
    private GuiTestInput() { }

    static void click(ClientGameTestContext context, String id) {
        click(context, id, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    static void rightClick(ClientGameTestContext context, String id) {
        click(context, id, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private static void click(ClientGameTestContext context, String id, int button) {
        settle(context);
        reveal(context, id);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            require(hit != null, "Rendered control disappeared before clicking: " + id);
            // The upper name area avoids the binding/favorite/toggle controls in a card header.
            double fraction = id.startsWith("module") ? .2 : .5;
            clickLocal(screen, hit.x + hit.w * fraction, hit.y + hit.h * fraction, button);
        });
        settle(context);
    }

    static void type(ClientGameTestContext context, String value) {
        context.runOnClient(client -> value.codePoints().forEach(codepoint ->
                require(screen(client.screen).charTyped(new CharacterEvent(codepoint, 0)),
                        "The GUI did not consume search text")));
        settle(context);
    }

    /** Wait for rendered positions, rather than assuming two game ticks finish a wall-clock animation. */
    static void settle(ClientGameTestContext context) {
        context.waitTick();
        context.waitFor(client -> {
            DonutScreen screen = screen(client.screen);
            return number(screen, "screenProgress") == 1 && number(screen, "contentProgress") == 1
                    && settled(screen, "expanded", "expansion")
                    && settled(screen, "colorsExpanded", "colorsExpansion")
                    && settled(screen, "filtersExpanded", "filtersExpansion")
                    && (field(screen, "colorEditor") == null || number(screen, "modalProgress") == 1);
        });
    }

    static void openAnimated(ClientGameTestContext context, Supplier<DonutScreen> factory) {
        context.setScreen(() -> {
            DonutScreen screen = factory.get();
            require(number(screen, "screenProgress") == 0, "A new GUI must begin transparent");
            return screen;
        });
        context.waitForScreen(DonutScreen.class);
        context.waitFor(client -> {
            double progress = number(screen(client.screen), "screenProgress");
            require(progress < 1, "Opening the GUI skipped its fade transition");
            return progress > 0;
        });
        settle(context);
    }

    /** Exercise reversing the slide while it is in progress, without changing any module settings. */
    static void assertExpansionReverses(ClientGameTestContext context, int module) {
        settle(context);
        String id = "module" + module;
        reveal(context, id);
        int collapsedHeight = context.computeOnClient(client -> cardHeight(screen(client.screen), module));
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            clickLocal(screen, hit.x + 8, hit.y + 8, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            require(((boolean[]) field(screen, "expanded"))[module], "Right-click did not request expansion");
            require(((double[]) field(screen, "expansion"))[module] == 0,
                    "Right-click snapped the settings open without sliding");
            require(cardHeight(screen, module) == collapsedHeight, "The card height jumped on right-click");
        });
        context.waitFor(client -> {
            DonutScreen screen = screen(client.screen);
            double progress = ((double[]) field(screen, "expansion"))[module];
            require(progress < 1, "The settings slide skipped intermediate frames");
            return progress > .1 && cardHeight(screen, module) > collapsedHeight;
        });
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            double progress = ((double[]) field(screen, "expansion"))[module];
            int partialHeight = cardHeight(screen, module);
            Bounds hit = find(screen, id);
            clickLocal(screen, hit.x + 8, hit.y + 8, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            require(!((boolean[]) field(screen, "expanded"))[module], "Second right-click did not reverse expansion");
            require(((double[]) field(screen, "expansion"))[module] == progress
                            && cardHeight(screen, module) == partialHeight,
                    "Reversing an in-progress slide changed its position abruptly");
        });
        settle(context);
        context.runOnClient(client -> require(cardHeight(screen(client.screen), module) == collapsedHeight,
                "Reversed settings did not return to their collapsed height"));
    }

    static void closeAnimated(ClientGameTestContext context) {
        settle(context);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, "close");
            require(hit != null, "The GUI close button is missing");
            clickLocal(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            require(client.screen == screen, "Closing the GUI removed it before fading out");
            require((boolean) field(screen, "closing"), "Closing the GUI did not start its exit animation");
            require(number(screen, "screenProgress") == 1, "Closing the GUI snapped its opacity");
        });
        context.waitFor(client -> {
            require(client.screen instanceof DonutScreen, "Closing the GUI skipped its fade transition");
            double progress = number(client.screen, "screenProgress");
            return progress > 0 && progress < 1;
        });
        context.waitForScreen(null);
    }

    static void clickWithFade(ClientGameTestContext context, String id, String progressField) {
        settle(context);
        reveal(context, id);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            clickLocal(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            require(number(screen, progressField) == 0, "The new content appeared without beginning a fade: " + id);
        });
        context.waitFor(client -> {
            double progress = number(screen(client.screen), progressField);
            require(progress < 1, "The content fade skipped intermediate frames: " + id);
            return progress > 0;
        });
        settle(context);
    }

    private static boolean settled(DonutScreen screen, String targetsName, String progressName) {
        boolean[] targets = (boolean[]) field(screen, targetsName);
        double[] progress = (double[]) field(screen, progressName);
        for (int index = 0; index < targets.length; index++) {
            if (progress[index] != (targets[index] ? 1 : 0)) return false;
        }
        return true;
    }

    static boolean visible(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> find(screen(client.screen), id) != null);
    }

    static void scrollTop(ClientGameTestContext context) {
        scroll(context, 1000);
        context.waitTicks(8);
    }

    static void assertContentClipped(ClientGameTestContext context) {
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            double top = number(screen, "TOP"), bottom = number(screen, "BOTTOM");
            for (Object raw : hits(screen)) {
                String id = (String) field(raw, "id");
                if (!id.startsWith("module") && !id.matches("m\\d+\\..*")) continue;
                Bounds hit = bounds(raw);
                require(hit.y >= top && hit.y + hit.h <= bottom,
                        "Offscreen content accepted mouse input: " + id);
            }
        });
    }

    static void clickBelowContent(ClientGameTestContext context) {
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            clickLocal(screen, number(screen, "CX") + number(screen, "CW") / 4,
                    number(screen, "BOTTOM") + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        });
        context.waitTicks(2);
    }

    private static void reveal(ClientGameTestContext context, String id) {
        if (visible(context, id)) return;
        scrollTop(context);
        for (int attempt = 0; attempt < 40 && !visible(context, id); attempt++) {
            scroll(context, -2);
            context.waitTicks(4);
        }
        require(visible(context, id), "Cannot find visible GUI control: " + id);
    }

    private static void scroll(ClientGameTestContext context, double amount) {
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            double localX = number(screen, "CX") + number(screen, "CW") / 2;
            double localY = (number(screen, "TOP") + number(screen, "BOTTOM")) / 2;
            screen.mouseScrolled(number(screen, "originX") + localX * number(screen, "scale"),
                    number(screen, "originY") + localY * number(screen, "scale"), 0, amount);
        });
    }

    private static void clickLocal(DonutScreen screen, double x, double y, int button) {
        double actualX = number(screen, "originX") + x * number(screen, "scale");
        double actualY = number(screen, "originY") + y * number(screen, "scale");
        var event = new MouseButtonEvent(actualX, actualY, new MouseButtonInfo(button, 0));
        require(screen.mouseClicked(event, false), "The GUI did not consume mouse input");
        screen.mouseReleased(event);
    }

    private static DonutScreen screen(Object screen) {
        require(screen instanceof DonutScreen, "Donut screen is not open");
        return (DonutScreen) screen;
    }

    private static Bounds find(DonutScreen screen, String id) {
        for (Object hit : hits(screen)) {
            if (id.equals(field(hit, "id"))) return bounds(hit);
        }
        return null;
    }

    private static Bounds bounds(Object hit) {
        return new Bounds(number(hit, "x"), number(hit, "y"), number(hit, "w"), number(hit, "h"));
    }

    private static List<?> hits(DonutScreen screen) {
        return (List<?>) field(screen, "hits");
    }

    private static double number(Object owner, String name) {
        return ((Number) field(owner, name)).doubleValue();
    }

    private static int cardHeight(DonutScreen screen, int module) {
        try {
            Method method = DonutScreen.class.getDeclaredMethod("cardHeight", int.class);
            method.setAccessible(true);
            return (int) method.invoke(screen, module);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect animated card height", error);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect rendered GUI field " + name, error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Bounds(double x, double y, double w, double h) { }
}
