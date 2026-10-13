package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FinderSettings debris = debrisDefaults();
    public FreecamSettings freecam = new FreecamSettings();
    public int accentColor = 0x4C86FF;
    public boolean showHud = true;

    private static FinderSettings spawnerDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF779C;
        settings.perTypeColors = false;
        return settings;
    }

    private static FinderSettings debrisDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xA855F7;
        settings.perTypeColors = false;
        return settings;
    }

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (debris == null) debris = debrisDefaults();
        if (freecam == null) freecam = new FreecamSettings();
        storage.sanitize();
        spawner.sanitize();
        debris.sanitize();
        freecam.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
