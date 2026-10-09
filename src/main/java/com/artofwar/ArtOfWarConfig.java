package com.artofwar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ArtOfWarConfig.GROUP)
public interface ArtOfWarConfig extends Config
{
	String GROUP = "artofwar";

	enum ScytheModel
	{
		MAIDEN("Maiden"),
		BLOAT("Bloat"),
		NYLO_MAGE("Nylo Mage"),
		SOTETSEG("Sotetseg"),
		XARPUS("Xarpus"),
		VERZIK("Verzik");

		private final String label;

		ScytheModel(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
			keyName = "scytheModel",
			name = "Scythe",
			description = "Select the custom scythe model",
			position = 0
	)
	default ScytheModel scytheModel()
	{
		return ScytheModel.MAIDEN;
	}
}
