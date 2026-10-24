package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FreecamSettings freecam = new FreecamSettings();
    public FullBrightSettings fullBright = new FullBrightSettings();
    public int accentColor = 0x4C86FF;
    public boolean showHud = true;

    private static FinderSettings spawnerDefaults() {
        FinderSettings settings = new FinderSettings();
        settings.color = 0xFF779C;
        settings.perTypeColors = false;
        return settings;
    }

    public void sanitize() {
        if (storage == null) storage = new FinderSettings();
        if (spawner == null) spawner = spawnerDefaults();
        if (freecam == null) freecam = new FreecamSettings();
        if (fullBright == null) fullBright = new FullBrightSettings();
        storage.sanitize();
        spawner.sanitize();
        freecam.sanitize();
        fullBright.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
