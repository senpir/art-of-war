package com.example;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.kit.KitType;
import net.runelite.client.RuneLite;
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
		tags = {"scythe"}
)
public class ExamplePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(ExamplePlugin.class);
	private static final int TARGET_WEAPON_ID = 25739;
	private static final int IDLE_ANIMATION_ID = 8057;
	private static final int WALK_ANIMATION_ID = 819;
	private static final int RUN_ANIMATION_ID = 824;
	private static final int ATTACK_ANIMATION_ID = 8056;
	private static final int DEFEND_ANIMATION_ID = 435;
	private static final String MODEL_FOLDER = "custom-weapon-models";

	@Inject
	private ExampleConfig config;

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CustomModelBuilder customModelBuilder;

	@Inject
	private CustomModelSpawner customModelSpawner;

	private BlenderModelData customModel;
	private Model renderedModel;
	private boolean targetWeaponEquipped;
	private boolean originalWeaponHidden;

	private final Map<String, Model> modelCache = new HashMap<>();

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
			customModelSpawner.despawn();
		});

		targetWeaponEquipped = false;
		originalWeaponHidden = false;
		customModel = null;
		renderedModel = null;
		modelCache.clear();
	}

	private void loadSelectedModel()
	{
		ExampleConfig.ScytheModel selection = config.scytheModel();
		File modelFile = new File(new File(RuneLite.RUNELITE_DIR, MODEL_FOLDER), selection.getFileName());

		if (!modelFile.exists())
		{
			log.error("Scythe model not found: {}", modelFile.getAbsolutePath());
			return;
		}

		BlenderModelData loadedModel;

		try (FileReader reader = new FileReader(modelFile))
		{
			loadedModel = gson.fromJson(reader, BlenderModelData.class);
		}
		catch (IOException | RuntimeException ex)
		{
			log.error("Failed to load scythe model: {}", selection, ex);
			return;
		}

		if (loadedModel == null || loadedModel.vertices == null || loadedModel.faces == null)
		{
			log.error("Invalid scythe model: {}", modelFile.getAbsolutePath());
			return;
		}

		clientThread.invokeLater(() ->
		{
			if (config.scytheModel() != selection)
			{
				return;
			}

			customModel = loadedModel;
			modelCache.clear();

			int[] idle = AnimationData.IDLE[0];
			renderedModel = getModel(idle[4], idle[5]);

			if (renderedModel == null)
			{
				log.error("Failed to build scythe model: {}", selection);
				return;
			}

			if (targetWeaponEquipped && customModelSpawner.isSpawned())
			{
				customModelSpawner.setModel(renderedModel);
			}

			syncEquipmentState();
		});
	}

	private Model getModel(int pitch, int roll)
	{
		if (customModel == null)
		{
			return null;
		}

		String key = pitch + ":" + roll;
		Model cached = modelCache.get(key);

		if (cached != null)
		{
			return cached;
		}

		Model model = customModelBuilder.build(customModel, pitch, roll);

		if (model != null)
		{
			modelCache.put(key, model);
		}

		return model;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("customweaponmodels".equals(event.getGroup()) && "scytheModel".equals(event.getKey()))
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
		if (!targetWeaponEquipped || !customModelSpawner.isSpawned())
		{
			return;
		}

		Player player = client.getLocalPlayer();

		if (player == null)
		{
			return;
		}

		hideOriginalWeapon();

		int actionAnimation = player.getAnimation();
		int actionFrame = player.getAnimationFrame();

		if (actionAnimation == DEFEND_ANIMATION_ID && actionFrame >= 0)
		{
			applyFrame(AnimationData.DEFEND, actionFrame);
			return;
		}

		if (actionAnimation == ATTACK_ANIMATION_ID && actionFrame >= 0)
		{
			applyFrame(AnimationData.ATTACK, actionFrame);
			return;
		}

		int poseAnimation = player.getPoseAnimation();
		int poseFrame = player.getPoseAnimationFrame();

		if (poseAnimation == RUN_ANIMATION_ID && poseFrame >= 0)
		{
			applyFrame(AnimationData.RUN, poseFrame);
			return;
		}

		if (poseAnimation == WALK_ANIMATION_ID && poseFrame >= 0)
		{
			applyFrame(AnimationData.WALK, poseFrame);
			return;
		}

		if (poseAnimation == IDLE_ANIMATION_ID && poseFrame >= 0)
		{
			applyFrame(AnimationData.IDLE, poseFrame);
			return;
		}

		applyFrame(AnimationData.IDLE, 0);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOADING)
		{
			customModelSpawner.despawn();
			originalWeaponHidden = false;
			return;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			targetWeaponEquipped = false;
			originalWeaponHidden = false;
			customModelSpawner.despawn();
			return;
		}

		if (state == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::syncEquipmentState);
		}
	}

	private void applyFrame(int[][] animation, int frame)
	{
		int[] transform = animation[Math.max(0, Math.min(frame, animation.length - 1))];
		Model model = getModel(transform[4], transform[5]);

		if (model != null)
		{
			renderedModel = model;
			customModelSpawner.setModel(model);
		}

		customModelSpawner.updateTransform(
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
		boolean equipped = weapon != null && weapon.getId() == TARGET_WEAPON_ID;

		if (equipped)
		{
			targetWeaponEquipped = true;
			hideOriginalWeapon();

			if (renderedModel != null && !customModelSpawner.isSpawned())
			{
				customModelSpawner.spawn(renderedModel);
			}

			return;
		}

		targetWeaponEquipped = false;
		originalWeaponHidden = false;
		customModelSpawner.despawn();
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

		if (weapon == null || weapon.getId() != TARGET_WEAPON_ID)
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

		equipmentIds[weaponIndex] = PlayerComposition.ITEM_OFFSET + TARGET_WEAPON_ID;
		composition.setHash();
		originalWeaponHidden = false;
	}

	@Provides
	ExampleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExampleConfig.class);
	}
}
