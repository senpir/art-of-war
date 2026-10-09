package com.artofwar;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.kit.KitType;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.animsmoothing.AnimationSmoothingPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
        name = "Art of War",
        description = "Replaces supported scythes with custom Theatre of Blood-themed models",
        tags = {"scythe", "weapon", "cosmetic", "tob"},
        internalName = "art-of-war"
)
public class ArtOfWarPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(ArtOfWarPlugin.class);
    private static final String MODEL_RESOURCE_DIRECTORY = "/scythes/";
    private static final int SMOOTH_STEPS = 8;
    private static final int SMOOTH_CACHE_SIZE = 160;
    private static final double NANOS_PER_CLIENT_CYCLE = 20_000_000.0;
    private static final Set<Integer> SUPPORTED_SCYTHE_IDS = Set.of(
            22486,
            22325,
            25736,
            25739
    );
    private static final Set<Integer> SCYTHE_TRAIL_GFX_IDS = Set.of(
            478,
            506,
            1172,
            1231,
            1232,
            1233,
            1234,
            1235,
            1891,
            1892,
            1893,
            1894,
            1895,
            1896,
            1897,
            1898
    );
    private static final int IDLE_ANIMATION_ID = 8057;
    private static final int WALK_ANIMATION_ID = 819;
    private static final int RUN_ANIMATION_ID = 824;
    private static final int ATTACK_ANIMATION_ID = 8056;
    private static final int DEFEND_ANIMATION_ID = 435;


    @Inject
    private ArtOfWarConfig config;

    @Inject
    private Gson gson;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private PluginManager pluginManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ScytheModelBuilder scytheModelBuilder;

    @Inject
    private ScytheModelSpawner scytheModelSpawner;

    private enum FlickerState
    {
        STEADY_ON,
        FIRST_OFF,
        BETWEEN_FLICKERS,
        SECOND_OFF
    }

    private ScytheModelData primaryModel;
    private ScytheModelData alternateModel;
    private Model renderedModel;
    private ScytheRegistry.Definition loadedDefinition;
    private boolean targetWeaponEquipped;
    private boolean originalWeaponHidden;
    private boolean primaryFlickerModelActive = true;
    private boolean doubleFlicker;
    private FlickerState flickerState = FlickerState.STEADY_ON;
    private long nextFlickerChangeAt;
    private int[] lastAppliedTransform;
    private Plugin animationSmoothingPlugin;
    private int[][] trackedAnimation;
    private int trackedAnimationId = -1;
    private int trackedFrame = -1;
    private int trackedStartCycle;
    private boolean trackedLoop;
    private int lastObservedCycle = -1;
    private long lastCycleObservedAt;
    private final Map<Integer, int[]> animationLengths = new HashMap<>();

    private final Map<String, Model> modelCache = newModelCache();
    private final Map<String, Model> alternateModelCache = newModelCache();
    private final Random random = new Random();

    private Map<String, Model> newModelCache()
    {
        return new LinkedHashMap<String, Model>(SMOOTH_CACHE_SIZE, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Model> eldest)
            {
                return size() > SMOOTH_CACHE_SIZE;
            }
        };
    }

    @Override
    protected void startUp()
    {
        resetUnavailableSelection();
        loadSelectedModel();
    }

    private void resetUnavailableSelection()
    {
        String saved = configManager.getConfiguration(ArtOfWarConfig.GROUP, "scytheModel");

        if (saved == null || saved.isEmpty())
        {
            return;
        }

        for (ArtOfWarConfig.ScytheModel available : ArtOfWarConfig.ScytheModel.values())
        {
            if (available.name().equals(saved))
            {
                return;
            }
        }

        configManager.setConfiguration(
                ArtOfWarConfig.GROUP,
                "scytheModel",
                ArtOfWarConfig.ScytheModel.MAIDEN.name()
        );
    }

    @Override
    protected void shutDown()
    {
        clientThread.invokeLater(() ->
        {
            restoreOriginalWeapon();
            scytheModelSpawner.despawn();
            targetWeaponEquipped = false;
            originalWeaponHidden = false;
            primaryModel = null;
            alternateModel = null;
            renderedModel = null;
            loadedDefinition = null;
            lastAppliedTransform = null;
            modelCache.clear();
            alternateModelCache.clear();
            clearTrackedAnimation();
            animationLengths.clear();
        });
    }

    private void loadSelectedModel()
    {
        resetUnavailableSelection();
        ArtOfWarConfig.ScytheModel selection = config.scytheModel();

        if (selection == null)
        {
            return;
        }

        ScytheRegistry.Definition definition = ScytheRegistry.get(selection);

        if (definition == null)
        {
            log.error("No Art of War scythe definition found for: {}", selection);
            return;
        }

        ScytheModelData loadedPrimaryModel = loadModelResource(definition.getPrimaryFileName());

        if (loadedPrimaryModel == null)
        {
            clearLoadedModel(selection);
            return;
        }

        ScytheModelData loadedAlternateModel = null;

        if (definition.hasAlternateModel())
        {
            loadedAlternateModel = loadModelResource(definition.getAlternateFileName());

            if (loadedAlternateModel == null)
            {
                clearLoadedModel(selection);
                return;
            }
        }

        ScytheModelData finalLoadedAlternateModel = loadedAlternateModel;

        clientThread.invokeLater(() ->
        {
            if (config.scytheModel() != selection)
            {
                return;
            }

            primaryModel = loadedPrimaryModel;
            alternateModel = finalLoadedAlternateModel;
            loadedDefinition = definition;
            modelCache.clear();
            alternateModelCache.clear();
            clearTrackedAnimation();
            resetFlicker();

            int[] idle = AnimationData.IDLE[0];
            lastAppliedTransform = idle;
            renderedModel = getModel(idle);

            if (renderedModel == null)
            {
                log.error("Failed to build scythe model: {}", selection);
                return;
            }

            if (targetWeaponEquipped && scytheModelSpawner.hasObject())
            {
                scytheModelSpawner.setModel(renderedModel);
            }

            syncEquipmentState();
        });
    }

    private ScytheModelData loadModelResource(String fileName)
    {
        String resourcePath = MODEL_RESOURCE_DIRECTORY + fileName;

        try (InputStream inputStream = ArtOfWarPlugin.class.getResourceAsStream(resourcePath))
        {
            if (inputStream == null)
            {
                log.error("Bundled scythe model not found: {}", resourcePath);
                return null;
            }

            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8))
            {
                ScytheModelData loadedModel = gson.fromJson(reader, ScytheModelData.class);

                if (loadedModel == null || loadedModel.vertices == null || loadedModel.faces == null)
                {
                    log.error("Invalid bundled scythe model: {}", resourcePath);
                    return null;
                }

                return loadedModel;
            }
        }
        catch (IOException | RuntimeException ex)
        {
            log.error("Failed to load bundled scythe model: {}", resourcePath, ex);
            return null;
        }
    }


    private void clearLoadedModel(ArtOfWarConfig.ScytheModel selection)
    {
        clientThread.invokeLater(() ->
        {
            if (config.scytheModel() != selection)
            {
                return;
            }

            primaryModel = null;
            alternateModel = null;
            renderedModel = null;
            loadedDefinition = null;
            lastAppliedTransform = null;
            clearTrackedAnimation();
            modelCache.clear();
            alternateModelCache.clear();
            resetFlicker();
            scytheModelSpawner.despawn();
            restoreOriginalWeapon();
            syncEquipmentState();
        });
    }


    private Model getModel(int[] transform)
    {
        ScytheModelData sourceModel = primaryModel;
        Map<String, Model> cache = modelCache;

        if (loadedDefinition != null
                && loadedDefinition.usesFlickerAlternate()
                && !primaryFlickerModelActive
                && alternateModel != null)
        {
            sourceModel = alternateModel;
            cache = alternateModelCache;
        }

        if (sourceModel == null)
        {
            return null;
        }

        int side = transform[0];
        int forward = transform[1];
        int height = transform[2];
        int yaw = transform[3];
        int pitch = transform[4];
        int roll = transform[5];

        String key = side + ":" + forward + ":" + height + ":" + yaw + ":" + pitch + ":" + roll;
        Model cached = cache.get(key);

        if (cached != null)
        {
            return cached;
        }

        Model model = scytheModelBuilder.build(sourceModel, pitch, roll);

        if (model != null)
        {
            double yawRadians = Math.toRadians(yaw);
            double cos = Math.cos(yawRadians);
            double sin = Math.sin(yawRadians);

            int localX = (int) Math.round(side * cos - forward * sin);
            int localZ = (int) Math.round(forward * cos + side * sin);

            model.translate(localX, height, localZ);
            cache.put(key, model);
        }

        return model;
    }

    private void resetFlicker()
    {
        primaryFlickerModelActive = true;
        doubleFlicker = false;
        flickerState = FlickerState.STEADY_ON;
        nextFlickerChangeAt = System.currentTimeMillis() + randomBetween(2500, 4500);
    }

    private void updateFlicker()
    {
        if (loadedDefinition == null || !loadedDefinition.usesFlickerAlternate())
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextFlickerChangeAt)
        {
            return;
        }

        switch (flickerState)
        {
            case STEADY_ON:
                primaryFlickerModelActive = false;
                doubleFlicker = random.nextDouble() < 0.35;
                flickerState = FlickerState.FIRST_OFF;
                nextFlickerChangeAt = now + randomBetween(200, 250);
                break;

            case FIRST_OFF:
                primaryFlickerModelActive = true;

                if (doubleFlicker)
                {
                    flickerState = FlickerState.BETWEEN_FLICKERS;
                    nextFlickerChangeAt = now + randomBetween(90, 150);
                }
                else
                {
                    flickerState = FlickerState.STEADY_ON;
                    nextFlickerChangeAt = now + randomBetween(2500, 4500);
                }
                break;

            case BETWEEN_FLICKERS:
                primaryFlickerModelActive = false;
                flickerState = FlickerState.SECOND_OFF;
                nextFlickerChangeAt = now + randomBetween(120, 180);
                break;

            case SECOND_OFF:
                primaryFlickerModelActive = true;
                doubleFlicker = false;
                flickerState = FlickerState.STEADY_ON;
                nextFlickerChangeAt = now + randomBetween(2500, 4500);
                break;
        }
    }

    private int randomBetween(int minimum, int maximum)
    {
        return minimum + random.nextInt(maximum - minimum + 1);
    }


    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (ArtOfWarConfig.GROUP.equals(event.getGroup()) && "scytheModel".equals(event.getKey()))
        {
            loadSelectedModel();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.WORN)
        {
            checkEquipment(event.getItemContainer());
        }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!targetWeaponEquipped || !scytheModelSpawner.hasObject() || lastAppliedTransform == null)
        {
            return;
        }

        observeClientCycle();

        if (isAnimationSmoothingEnabled() && trackedAnimation != null)
        {
            int[] smoothedTransform = interpolateCurrentFrame();

            if (smoothedTransform != null)
            {
                applyTransform(smoothedTransform);
                return;
            }
        }

        scytheModelSpawner.updateTransform(lastAppliedTransform[3]);
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        observeClientCycle();

        if (client.getGameState() == GameState.LOGGED_IN && originalWeaponHidden)
        {
            ItemContainer worn = client.getItemContainer(InventoryID.WORN);
            Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

            if (weapon == null || !SUPPORTED_SCYTHE_IDS.contains(weapon.getId()) || renderedModel == null)
            {
                restoreOriginalWeapon();
            }
        }

        if (!targetWeaponEquipped || !scytheModelSpawner.hasObject())
        {
            return;
        }

        Player player = client.getLocalPlayer();

        if (player == null)
        {
            return;
        }

        recolorScytheTrails();
        hideOriginalWeapon();
        updateFlicker();

        int actionAnimation = player.getAnimation();
        int actionFrame = player.getAnimationFrame();

        if (actionAnimation != -1
                && actionAnimation != ATTACK_ANIMATION_ID
                && actionAnimation != DEFEND_ANIMATION_ID)
        {
            clearTrackedAnimation();
            scytheModelSpawner.setVisible(false);
            return;
        }

        scytheModelSpawner.setVisible(true);

        if (actionAnimation == DEFEND_ANIMATION_ID)
        {
            if (hasIncomingProjectile(player))
            {
                applyCurrentPose(player);
                return;
            }

            if (actionFrame >= 0)
            {
                applyFrame(AnimationData.DEFEND, actionFrame, DEFEND_ANIMATION_ID, false);
            }
            else
            {
                applyCurrentPose(player);
            }
            return;
        }

        if (actionAnimation == ATTACK_ANIMATION_ID && actionFrame >= 0)
        {
            applyFrame(AnimationData.ATTACK, actionFrame, ATTACK_ANIMATION_ID, false);
            return;
        }

        applyCurrentPose(player);
    }

    private void recolorScytheTrails()
    {
        if (loadedDefinition == null)
        {
            return;
        }

        Color startColor = loadedDefinition.getTrailStartColor();
        Color endColor = loadedDefinition.getTrailEndColor();

        if (startColor == null || endColor == null)
        {
            return;
        }

        for (GraphicsObject graphicsObject : client.getTopLevelWorldView().getGraphicsObjects())
        {
            if (SCYTHE_TRAIL_GFX_IDS.contains(graphicsObject.getId()))
            {
                recolorTrailGradient(graphicsObject.getModel(), startColor, endColor);
            }
        }
    }

    private void recolorTrailGradient(Model model, Color startColor, Color endColor)
    {
        if (model == null || startColor == null || endColor == null)
        {
            return;
        }

        int faceCount = model.getFaceCount();

        if (faceCount <= 0)
        {
            return;
        }

        float[] verticesX = model.getVerticesX();
        float[] verticesZ = model.getVerticesZ();
        int[] faceIndices1 = model.getFaceIndices1();
        int[] faceIndices2 = model.getFaceIndices2();
        int[] faceIndices3 = model.getFaceIndices3();
        int[] faceColors1 = model.getFaceColors1();
        int[] faceColors2 = model.getFaceColors2();
        int[] faceColors3 = model.getFaceColors3();

        double[] angles = new double[faceCount];
        double sumSin = 0.0;
        double sumCos = 0.0;

        for (int i = 0; i < faceCount; i++)
        {
            int a = faceIndices1[i];
            int b = faceIndices2[i];
            int c = faceIndices3[i];

            double x = (verticesX[a] + verticesX[b] + verticesX[c]) / 3.0;
            double z = (verticesZ[a] + verticesZ[b] + verticesZ[c]) / 3.0;
            double angle = Math.atan2(z, x);

            angles[i] = angle;
            sumSin += Math.sin(angle);
            sumCos += Math.cos(angle);
        }

        double meanAngle = Math.atan2(sumSin, sumCos);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < faceCount; i++)
        {
            angles[i] = normalizeAngle(angles[i] - meanAngle);
            min = Math.min(min, angles[i]);
            max = Math.max(max, angles[i]);
        }

        double range = max - min;

        for (int i = 0; i < faceCount; i++)
        {
            double amount = range < 0.0001
                    ? (faceCount == 1 ? 0.0 : (double) i / (faceCount - 1))
                    : (angles[i] - min) / range;

            int packedColor = colorToRs2Hsb(blendColor(startColor, endColor, amount));

            faceColors1[i] = packedColor;
            faceColors2[i] = packedColor;
            faceColors3[i] = packedColor;
        }
    }

    private double normalizeAngle(double angle)
    {
        while (angle <= -Math.PI)
        {
            angle += Math.PI * 2.0;
        }

        while (angle > Math.PI)
        {
            angle -= Math.PI * 2.0;
        }

        return angle;
    }

    private Color blendColor(Color start, Color end, double amount)
    {
        double t = Math.max(0.0, Math.min(1.0, amount));

        int red = (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * t);
        int green = (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * t);
        int blue = (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * t);

        return new Color(red, green, blue);
    }

    private int colorToRs2Hsb(Color color)
    {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        hsb[2] -= Math.min(hsb[1], hsb[2] / 2.0f);

        int hue = (int) (hsb[0] * 63);
        int saturation = (int) (hsb[1] * 7);
        int brightness = (int) (hsb[2] * 127);

        return (hue << 10) + (saturation << 7) + brightness;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();

        if (state == GameState.LOADING)
        {
            restoreOriginalWeapon();
            scytheModelSpawner.despawn();
            clearTrackedAnimation();
            return;
        }

        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            restoreOriginalWeapon();
            targetWeaponEquipped = false;
            scytheModelSpawner.despawn();
            clearTrackedAnimation();

            if (state == GameState.LOGIN_SCREEN)
            {
                originalWeaponHidden = false;
            }
            return;
        }

        if (state == GameState.LOGGED_IN)
        {
            clientThread.invokeLater(() ->
            {
                restoreOriginalWeapon();
                syncEquipmentState();
            });
        }
    }

    private void applyCurrentPose(Player player)
    {
        int poseAnimation = player.getPoseAnimation();
        int poseFrame = player.getPoseAnimationFrame();

        if (poseFrame < 0)
        {
            applyLastTransform();
            return;
        }

        if (poseAnimation == RUN_ANIMATION_ID || poseAnimation == player.getRunAnimation())
        {
            applyFrame(AnimationData.RUN, poseFrame, poseAnimation, true);
            return;
        }

        if (poseAnimation == WALK_ANIMATION_ID
                || poseAnimation == player.getWalkAnimation()
                || poseAnimation == player.getWalkRotateLeft()
                || poseAnimation == player.getWalkRotateRight()
                || poseAnimation == player.getWalkRotate180())
        {
            applyFrame(AnimationData.WALK, poseFrame, poseAnimation, true);
            return;
        }

        if (poseAnimation == IDLE_ANIMATION_ID
                || poseAnimation == player.getIdlePoseAnimation()
                || poseAnimation == player.getIdleRotateLeft()
                || poseAnimation == player.getIdleRotateRight())
        {
            applyFrame(AnimationData.IDLE, poseFrame, poseAnimation, true);
            return;
        }

        applyLastTransform();
    }

    private boolean hasIncomingProjectile(Player player)
    {
        for (Projectile projectile : client.getProjectiles())
        {
            if (projectile.getRemainingCycles() <= 0)
            {
                continue;
            }

            if (projectile.getTargetActor() == player)
            {
                return true;
            }

            if (projectile.getTargetActor() == null
                    && projectile.getTargetPoint() != null
                    && projectile.getTargetPoint().equals(player.getWorldLocation()))
            {
                return true;
            }
        }

        return false;
    }

    private void applyFrame(int[][] animation, int frame, int animationId, boolean loop)
    {
        int index = Math.max(0, Math.min(frame, animation.length - 1));

        if (trackedAnimation != animation || trackedFrame != index || trackedAnimationId != animationId)
        {
            trackedAnimation = animation;
            trackedFrame = index;
            trackedAnimationId = animationId;
            trackedStartCycle = client.getGameCycle();
            trackedLoop = loop;
        }

        int[] transform = animation[index];
        lastAppliedTransform = transform;
        applyTransform(transform);
    }

    private void clearTrackedAnimation()
    {
        trackedAnimation = null;
        trackedAnimationId = -1;
        trackedFrame = -1;
    }

    private void observeClientCycle()
    {
        int cycle = client.getGameCycle();

        if (cycle != lastObservedCycle)
        {
            lastObservedCycle = cycle;
            lastCycleObservedAt = System.nanoTime();
        }
    }

    private boolean isAnimationSmoothingEnabled()
    {
        if (animationSmoothingPlugin == null)
        {
            for (Plugin plugin : pluginManager.getPlugins())
            {
                if (plugin instanceof AnimationSmoothingPlugin)
                {
                    animationSmoothingPlugin = plugin;
                    break;
                }
            }
        }

        return animationSmoothingPlugin != null && pluginManager.isPluginActive(animationSmoothingPlugin);
    }

    private int[] getFrameLengths(int animationId)
    {
        if (!animationLengths.containsKey(animationId))
        {
            Animation animation = client.loadAnimation(animationId);
            animationLengths.put(animationId, animation == null ? null : animation.getFrameLengths());
        }

        return animationLengths.get(animationId);
    }

    private int[] interpolateCurrentFrame()
    {
        if (trackedAnimation == null || trackedFrame < 0)
        {
            return null;
        }

        int[][] animation = trackedAnimation;
        int nextFrame = trackedFrame + 1;

        if (nextFrame >= animation.length)
        {
            nextFrame = trackedLoop ? 0 : trackedFrame;
        }

        if (nextFrame == trackedFrame)
        {
            return animation[trackedFrame];
        }

        int duration = 1;
        int[] lengths = getFrameLengths(trackedAnimationId);

        if (lengths != null && trackedFrame < lengths.length)
        {
            duration = Math.max(1, lengths[trackedFrame]);
        }

        double inCycle = Math.max(0.0, Math.min(1.0,
                (System.nanoTime() - lastCycleObservedAt) / NANOS_PER_CLIENT_CYCLE));
        double elapsed = Math.max(0.0, client.getGameCycle() - trackedStartCycle + inCycle);
        double t = Math.min(1.0, elapsed / duration);
        t = Math.round(t * SMOOTH_STEPS) / (double) SMOOTH_STEPS;

        int[] start = animation[trackedFrame];
        int[] end = animation[nextFrame];
        int[] interpolated = new int[6];

        for (int i = 0; i < interpolated.length; i++)
        {
            int difference = end[i] - start[i];

            if (i >= 3)
            {
                difference = ((difference + 540) % 360 + 360) % 360 - 180;
            }

            interpolated[i] = (int) Math.round(start[i] + difference * t);
        }

        return interpolated;
    }

    private void applyLastTransform()
    {
        if (lastAppliedTransform == null)
        {
            lastAppliedTransform = AnimationData.IDLE[0];
        }

        applyTransform(lastAppliedTransform);
    }

    private void applyTransform(int[] transform)
    {
        Model model = getModel(transform);

        if (model != null)
        {
            renderedModel = model;
            scytheModelSpawner.setModel(model);
        }

        scytheModelSpawner.updateTransform(transform[3]);
    }

    private void syncEquipmentState()
    {
        checkEquipment(client.getItemContainer(InventoryID.WORN));
    }

    private void checkEquipment(ItemContainer equipment)
    {
        if (equipment == null)
        {
            return;
        }

        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        boolean equipped = weapon != null && SUPPORTED_SCYTHE_IDS.contains(weapon.getId());

        if (equipped)
        {
            targetWeaponEquipped = true;

            if (renderedModel == null)
            {
                restoreOriginalWeapon();
                scytheModelSpawner.despawn();
                return;
            }

            hideOriginalWeapon();

            if (!scytheModelSpawner.hasObject())
            {
                scytheModelSpawner.spawn(renderedModel);

                if (lastAppliedTransform == null)
                {
                    lastAppliedTransform = AnimationData.IDLE[0];
                }

                scytheModelSpawner.setVisible(true);
                scytheModelSpawner.updateTransform(lastAppliedTransform[3]);
            }

            return;
        }

        restoreOriginalWeapon();
        targetWeaponEquipped = false;
        scytheModelSpawner.despawn();
        clearTrackedAnimation();
    }

    private void hideOriginalWeapon()
    {
        if (renderedModel == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        Item wornWeapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

        if (wornWeapon == null || !SUPPORTED_SCYTHE_IDS.contains(wornWeapon.getId()))
        {
            return;
        }

        Player player = client.getLocalPlayer();

        if (player == null)
        {
            return;
        }

        PlayerComposition composition = player.getPlayerComposition();

        if (composition == null)
        {
            return;
        }

        int[] equipmentIds = composition.getEquipmentIds();
        int weaponIndex = KitType.WEAPON.getIndex();

        if (equipmentIds == null || weaponIndex < 0 || weaponIndex >= equipmentIds.length)
        {
            return;
        }

        if (equipmentIds[weaponIndex] == PlayerComposition.ITEM_OFFSET + wornWeapon.getId())
        {
            equipmentIds[weaponIndex] = 0;
            composition.setHash();
            originalWeaponHidden = true;
        }
    }

    private void restoreOriginalWeapon()
    {
        if (!originalWeaponHidden)
        {
            return;
        }

        ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
        Player player = client.getLocalPlayer();

        if (equipment == null || player == null)
        {
            return;
        }

        PlayerComposition composition = player.getPlayerComposition();

        if (composition == null)
        {
            return;
        }

        int[] equipmentIds = composition.getEquipmentIds();
        int weaponIndex = KitType.WEAPON.getIndex();

        if (equipmentIds == null || weaponIndex < 0 || weaponIndex >= equipmentIds.length)
        {
            return;
        }

        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

        if (equipmentIds[weaponIndex] == 0 && weapon == null)
        {
            if (client.getGameState() == GameState.LOGGED_IN && !targetWeaponEquipped)
            {
                originalWeaponHidden = false;
            }
            return;
        }

        if (equipmentIds[weaponIndex] == 0)
        {
            equipmentIds[weaponIndex] = PlayerComposition.ITEM_OFFSET + weapon.getId();
            composition.setHash();
        }

        originalWeaponHidden = false;
    }

    @Provides
    ArtOfWarConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ArtOfWarConfig.class);
    }
}
