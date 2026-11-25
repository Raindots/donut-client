package dev.donut.client.config;

public final class ClientConfig {
    public int schemaVersion = 1;
    public FreecamSettings freecam = new FreecamSettings();
    public AutoBuilderSettings autoBuilder = new AutoBuilderSettings();
    public ChunkFinderSettings chunkFinder = new ChunkFinderSettings();
    public LightDebugSettings lightDebug = new LightDebugSettings();
    public SoundDetectorSettings soundDetector = new SoundDetectorSettings();
    public StaffDetectorSettings staffDetector = new StaffDetectorSettings();
    public BlockEspSettings blockEsp = new BlockEspSettings();
    public PlayerEspSettings playerEsp = new PlayerEspSettings();
    public TimeChangerSettings timeChanger = new TimeChangerSettings();
    public AmethystSettings amethyst = new AmethystSettings();
    public FullbrightSettings fullbright = new FullbrightSettings();
    public FlySettings fly = new FlySettings();
    public CrystalAuraSettings crystalAura = new CrystalAuraSettings();
    public AutoTotemSettings autoTotem = new AutoTotemSettings();
    public AnchorMacroSettings anchorMacro = new AnchorMacroSettings();
    public TriggerbotSettings triggerbot = new TriggerbotSettings();
    public AimAssistSettings aimAssist = new AimAssistSettings();
    public ProgressBarSettings progressBar = new ProgressBarSettings();
    public NameHiderSettings nameHider = new NameHiderSettings();
    public HotbarSettings hotbar = new HotbarSettings();
    public int accentColor = 0x56DCCB;
    public ClickGuiStyle clickGuiStyle = new ClickGuiStyle();
    public boolean clickGuiParticles = true;
    public boolean showHud = true;

    /** Replace the settings while retaining the configuration object shared by the client. */
    public void applyProfile(ClientConfig snapshot) {
        schemaVersion = snapshot.schemaVersion;
        freecam = snapshot.freecam;
        autoBuilder = snapshot.autoBuilder;
        chunkFinder = snapshot.chunkFinder;
        lightDebug = snapshot.lightDebug;
        soundDetector = snapshot.soundDetector;
        staffDetector = snapshot.staffDetector;
        blockEsp = snapshot.blockEsp;
        playerEsp = snapshot.playerEsp;
        timeChanger = snapshot.timeChanger;
        amethyst = snapshot.amethyst;
        fullbright = snapshot.fullbright;
        fly = snapshot.fly;
        crystalAura = snapshot.crystalAura;
        autoTotem = snapshot.autoTotem;
        anchorMacro = snapshot.anchorMacro;
        triggerbot = snapshot.triggerbot;
        aimAssist = snapshot.aimAssist;
        progressBar = snapshot.progressBar;
        nameHider = snapshot.nameHider;
        hotbar = snapshot.hotbar;
        accentColor = snapshot.accentColor;
        clickGuiStyle = snapshot.clickGuiStyle;
        clickGuiParticles = snapshot.clickGuiParticles;
        showHud = snapshot.showHud;
    }

    public void sanitize() {
        if (clickGuiStyle == null) clickGuiStyle = new ClickGuiStyle();
        clickGuiStyle.sanitize();
        if (freecam == null) freecam = new FreecamSettings();
        if (autoBuilder == null) autoBuilder = new AutoBuilderSettings();
        if (chunkFinder == null) chunkFinder = new ChunkFinderSettings();
        if (amethyst == null) amethyst = new AmethystSettings();
        if (fullbright == null) fullbright = new FullbrightSettings();
        if (fly == null) fly = new FlySettings();
        if (progressBar == null) progressBar = new ProgressBarSettings();
        if (nameHider == null) nameHider = new NameHiderSettings();
        if (hotbar == null) hotbar = new HotbarSettings();
        freecam.sanitize();
        autoBuilder.sanitize();
        chunkFinder.sanitize();
        amethyst.sanitize();
        if (lightDebug == null) lightDebug = new LightDebugSettings();
        lightDebug.sanitize();
        if (soundDetector == null) soundDetector = new SoundDetectorSettings();
        soundDetector.sanitize();
        if (staffDetector == null) staffDetector = new StaffDetectorSettings();
        staffDetector.sanitize();
        if (blockEsp == null) blockEsp = new BlockEspSettings();
        blockEsp.sanitize();
        if (playerEsp == null) playerEsp = new PlayerEspSettings();
        playerEsp.sanitize();
        if (timeChanger == null) timeChanger = new TimeChangerSettings();
        timeChanger.sanitize();
        if (crystalAura == null) crystalAura = new CrystalAuraSettings();
        crystalAura.sanitize();
        if (autoTotem == null) autoTotem = new AutoTotemSettings();
        autoTotem.sanitize();
        if (anchorMacro == null) anchorMacro = new AnchorMacroSettings();
        anchorMacro.sanitize();
        if (triggerbot == null) triggerbot = new TriggerbotSettings();
        triggerbot.sanitize();
        if (aimAssist == null) aimAssist = new AimAssistSettings();
        aimAssist.sanitize();
        progressBar.sanitize();
        hotbar.sanitize();
        accentColor &= 0xFFFFFF;
    }
}
