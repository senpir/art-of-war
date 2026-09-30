package com.artofwar;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.io.Reader;
import net.runelite.client.util.Filepath;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
        name = "Art of War",
        description = "scythe plugin",
        tags = {"scythe"},
        internalName = "art-of-war",
        legacyDataDirectory = "custom-weapon-models"
)
public class ArtOfWarPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(ArtOfWarPlugin.class);
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
    private ScytheModelBuilder scytheModelBuilder;

    @Inject
    private ScytheModelSpawner scytheModelSpawner;

    private enum BloatFlickerState
    {
        STEADY_ON,
        FIRST_OFF,
        BETWEEN_FLICKERS,
        SECOND_OFF
    }

    private ScytheModelData customModel;
    private ScytheModelData bloatOffModel;
    private Model renderedModel;
    private ArtOfWarConfig.ScytheModel loadedSelection;
    private boolean targetWeaponEquipped;
    private boolean originalWeaponHidden;
    private boolean bloatLightsOn = true;
    private boolean bloatDoubleFlicker;
    private BloatFlickerState bloatFlickerState = BloatFlickerState.STEADY_ON;
    private long nextBloatChangeAt;
    private int[] lastAppliedTransform;

    private final Map<String, Model> modelCache = new HashMap<>();
    private final Map<String, Model> bloatOffModelCache = new HashMap<>();
    private final Random random = new Random();

    @Override
    protected void startUp()
    {
        loadSelectedModel();
    }

    @Override
    protected void shutDown()
    {
        clientThread.invokeLater(() ->
        {
            restoreOriginalWeaponIfStillEquipped();
            scytheModelSpawner.despawn();
        });

        targetWeaponEquipped = false;
        originalWeaponHidden = false;
        customModel = null;
        bloatOffModel = null;
        renderedModel = null;
        loadedSelection = null;
        lastAppliedTransform = null;
        modelCache.clear();
        bloatOffModelCache.clear();
    }

    private void loadSelectedModel()
    {
        ArtOfWarConfig.ScytheModel selection = config.scytheModel();
        ScytheModelData loadedModel = loadModelFile(selection.getFileName());

        if (loadedModel == null)
        {
            return;
        }

        ScytheModelData loadedBloatOffModel = null;

        if (selection == ArtOfWarConfig.ScytheModel.BLOAT)
        {
            loadedBloatOffModel = loadModelFile(selection.getAlternateFileName());

            if (loadedBloatOffModel == null)
            {
                return;
            }
        }

        ScytheModelData finalLoadedBloatOffModel = loadedBloatOffModel;

        clientThread.invokeLater(() ->
        {
            if (config.scytheModel() != selection)
            {
                return;
            }

            customModel = loadedModel;
            bloatOffModel = finalLoadedBloatOffModel;
            loadedSelection = selection;
            modelCache.clear();
            bloatOffModelCache.clear();
            resetBloatFlicker();

            int[] idle = AnimationData.IDLE[0];
            lastAppliedTransform = idle;
            renderedModel = getModel(idle[4], idle[5]);

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

    private ScytheModelData loadModelFile(String fileName)
    {
        Filepath modelFile;

        try
        {
            modelFile = getPluginDirectory().joinSegment(fileName);
        }
        catch (IOException | RuntimeException ex)
        {
            log.error("Failed to access Art of War model directory.", ex);
            return null;
        }

        if (!modelFile.exists())
        {
            log.error("Scythe model not found: {}", modelFile);
            return null;
        }

        ScytheModelData loadedModel;

        try (Reader reader = modelFile.openReader())
        {
            loadedModel = gson.fromJson(reader, ScytheModelData.class);
        }
        catch (IOException | RuntimeException ex)
        {
            log.error("Failed to load scythe model: {}", fileName, ex);
            return null;
        }

        if (loadedModel == null || loadedModel.vertices == null || loadedModel.faces == null)
        {
            log.error("Invalid scythe model: {}", modelFile);
            return null;
        }

        return loadedModel;
    }


    private Model getModel(int pitch, int roll)
    {
        ScytheModelData sourceModel = customModel;
        Map<String, Model> cache = modelCache;

        if (loadedSelection == ArtOfWarConfig.ScytheModel.BLOAT && !bloatLightsOn && bloatOffModel != null)
        {
            sourceModel = bloatOffModel;
            cache = bloatOffModelCache;
        }

        if (sourceModel == null)
        {
            return null;
        }

        String key = pitch + ":" + roll;
        Model cached = cache.get(key);

        if (cached != null)
        {
            return cached;
        }

        Model model = scytheModelBuilder.build(sourceModel, pitch, roll);

        if (model != null)
        {
            cache.put(key, model);
        }

        return model;
    }

    private void resetBloatFlicker()
    {
        bloatLightsOn = true;
        bloatDoubleFlicker = false;
        bloatFlickerState = BloatFlickerState.STEADY_ON;
        nextBloatChangeAt = System.currentTimeMillis() + randomBetween(2800, 7000);
    }

    private void updateBloatFlicker()
    {
        if (loadedSelection != ArtOfWarConfig.ScytheModel.BLOAT)
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextBloatChangeAt)
        {
            return;
        }

        switch (bloatFlickerState)
        {
            case STEADY_ON:
                bloatLightsOn = false;
                bloatDoubleFlicker = random.nextDouble() < 0.30;
                bloatFlickerState = BloatFlickerState.FIRST_OFF;
                nextBloatChangeAt = now + randomBetween(80, 130);
                break;

            case FIRST_OFF:
                bloatLightsOn = true;

                if (bloatDoubleFlicker)
                {
                    bloatFlickerState = BloatFlickerState.BETWEEN_FLICKERS;
                    nextBloatChangeAt = now + randomBetween(90, 150);
                }
                else
                {
                    bloatFlickerState = BloatFlickerState.STEADY_ON;
                    nextBloatChangeAt = now + randomBetween(2800, 7000);
                }
                break;

            case BETWEEN_FLICKERS:
                bloatLightsOn = false;
                bloatFlickerState = BloatFlickerState.SECOND_OFF;
                nextBloatChangeAt = now + randomBetween(70, 120);
                break;

            case SECOND_OFF:
                bloatLightsOn = true;
                bloatDoubleFlicker = false;
                bloatFlickerState = BloatFlickerState.STEADY_ON;
                nextBloatChangeAt = now + randomBetween(2800, 7000);
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
        if ("artofwar".equals(event.getGroup()) && "scytheModel".equals(event.getKey()))
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
    public void onClientTick(ClientTick event)
    {
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
        updateBloatFlicker();

        int actionAnimation = player.getAnimation();
        int actionFrame = player.getAnimationFrame();

        if (actionAnimation != -1
                && actionAnimation != ATTACK_ANIMATION_ID
                && actionAnimation != DEFEND_ANIMATION_ID)
        {
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
                applyFrame(AnimationData.DEFEND, actionFrame);
            }
            else
            {
                applyCurrentPose(player);
            }
            return;
        }

        if (actionAnimation == ATTACK_ANIMATION_ID && actionFrame >= 0)
        {
            applyFrame(AnimationData.ATTACK, actionFrame);
            return;
        }

        applyCurrentPose(player);
    }

    private void recolorScytheTrails()
    {
        Color[] colors = getSelectedTrailColors();

        for (GraphicsObject graphicsObject : client.getTopLevelWorldView().getGraphicsObjects())
        {
            if (SCYTHE_TRAIL_GFX_IDS.contains(graphicsObject.getId()))
            {
                recolorTrailGradient(graphicsObject.getModel(), colors[0], colors[1]);
            }
        }
    }

    private Color[] getSelectedTrailColors()
    {
        if (loadedSelection == ArtOfWarConfig.ScytheModel.XARPUS)
        {
            return new Color[]{
                    new Color(0, 140, 0),
                    new Color(92, 72, 62)
            };
        }

        if (loadedSelection == ArtOfWarConfig.ScytheModel.BLOAT)
        {
            return new Color[]{
                    new Color(163, 157, 150),
                    new Color(248, 170, 60)
            };
        }

        return new Color[]{
                new Color(245, 105, 80),
                new Color(170, 35, 35)
        };
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
            scytheModelSpawner.despawn();
            originalWeaponHidden = false;
            return;
        }

        if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            targetWeaponEquipped = false;
            originalWeaponHidden = false;
            scytheModelSpawner.despawn();
            return;
        }

        if (state == GameState.LOGGED_IN)
        {
            clientThread.invokeLater(this::syncEquipmentState);
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
            applyFrame(AnimationData.RUN, poseFrame);
            return;
        }

        if (poseAnimation == WALK_ANIMATION_ID
                || poseAnimation == player.getWalkAnimation()
                || poseAnimation == player.getWalkRotateLeft()
                || poseAnimation == player.getWalkRotateRight()
                || poseAnimation == player.getWalkRotate180())
        {
            applyFrame(AnimationData.WALK, poseFrame);
            return;
        }

        if (poseAnimation == IDLE_ANIMATION_ID
                || poseAnimation == player.getIdlePoseAnimation()
                || poseAnimation == player.getIdleRotateLeft()
                || poseAnimation == player.getIdleRotateRight())
        {
            applyFrame(AnimationData.IDLE, poseFrame);
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

    private void applyFrame(int[][] animation, int frame)
    {
        int[] transform = animation[Math.max(0, Math.min(frame, animation.length - 1))];
        lastAppliedTransform = transform;
        applyTransform(transform);
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
        Model model = getModel(transform[4], transform[5]);

        if (model != null)
        {
            renderedModel = model;
            scytheModelSpawner.setModel(model);
        }

        scytheModelSpawner.updateTransform(
                transform[0],
                transform[1],
                transform[2],
                transform[3]
        );
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
            hideOriginalWeapon();

            if (renderedModel != null && !scytheModelSpawner.hasObject())
            {
                scytheModelSpawner.spawn(renderedModel);
            }

            return;
        }

        targetWeaponEquipped = false;
        originalWeaponHidden = false;
        scytheModelSpawner.despawn();
    }

    private void hideOriginalWeapon()
    {
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

        if (equipmentIds[weaponIndex] != 0)
        {
            equipmentIds[weaponIndex] = 0;
            composition.setHash();
            originalWeaponHidden = true;
        }
    }

    private void restoreOriginalWeaponIfStillEquipped()
    {
        if (!originalWeaponHidden)
        {
            return;
        }

        ItemContainer equipment = client.getItemContainer(InventoryID.WORN);

        if (equipment == null)
        {
            return;
        }

        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

        if (weapon == null || !SUPPORTED_SCYTHE_IDS.contains(weapon.getId()))
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

        equipmentIds[weaponIndex] = PlayerComposition.ITEM_OFFSET + weapon.getId();
        composition.setHash();
        originalWeaponHidden = false;
    }

    @Provides
    ArtOfWarConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ArtOfWarConfig.class);
    }
}
