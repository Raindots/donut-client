package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FinderSettings storage = new FinderSettings();
    public FinderSettings spawner = spawnerDefaults();
    public FreecamSettings freecam = new FreecamSettings();
    public AutoBuilderSettings autoBuilder = new AutoBuilderSettings();
    public ChunkFinderSettings chunkFinder = new ChunkFinderSettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public FlySettings fly = new FlySettings();
    public int accentColor = 0xC447FF;
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
        if (autoBuilder == null) autoBuilder = new AutoBuilderSettings();
        if (chunkFinder == null) chunkFinder = new ChunkFinderSettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (fly == null) fly = new FlySettings();
        storage.sanitize();
        spawner.sanitize();
        freecam.sanitize();
        autoBuilder.sanitize();
        chunkFinder.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
