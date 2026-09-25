package com.artofwar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("artofwar")
public interface ArtOfWarConfig extends Config
{
	enum ScytheModel
	{
		MAIDEN("Maiden", "maiden_scythe.json"),
		XARPUS("Xarpus", "xarpus_scythe.json");

		private final String label;
		private final String fileName;

		ScytheModel(String label, String fileName)
		{
			this.label = label;
			this.fileName = fileName;
		}

		String getFileName()
		{
			return fileName;
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
			description = "Choose a scythe model"
	)
	default ScytheModel scytheModel()
	{
		return ScytheModel.MAIDEN;
	}
}
