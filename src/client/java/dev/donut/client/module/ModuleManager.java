package dev.donut.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

public final class ModuleManager {
    private final Map<String, Module> modulesById = new LinkedHashMap<>();
    private final List<Module> modules = new ArrayList<>();
    private ModuleContext context;

    public void init(ModuleContext context) {
        this.context = context;
        for (Module module : modules) {
            module.init(context);
        }
    }

    public void register(Module module) {
        modulesById.put(module.id(), module);
        modules.add(module);
        if (context != null) {
            module.init(context);
        }
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModule(Class<T> clazz) {
        for (Module module : modules) {
            if (clazz.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }

    public Module getModule(String id) {
        return modulesById.get(id);
    }

    public List<Module> getByCategory(ModuleCategory category) {
        List<Module> list = new ArrayList<>();
        for (Module module : modules) {
            if (module.category() == category) {
                list.add(module);
            }
        }
        return list;
    }

    public void tick(Minecraft client) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.tick(client);
            }
        }
    }

    public void close() {
        for (Module module : modules) {
            module.close();
        }
    }
}
