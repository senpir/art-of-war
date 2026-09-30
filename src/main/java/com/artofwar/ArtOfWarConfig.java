package com.artofwar;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("artofwar")
public interface ArtOfWarConfig extends Config
{
	enum ScytheModel
	{
		MAIDEN("Maiden", "maiden_scythe.json", null),
		XARPUS("Xarpus", "xarpus_scythe.json", null),
		BLOAT("Bloat", "bloat_scythe_on.json", "bloat_scythe_off.json");

		private final String label;
		private final String fileName;
		private final String alternateFileName;

		ScytheModel(String label, String fileName, String alternateFileName)
		{
			this.label = label;
			this.fileName = fileName;
			this.alternateFileName = alternateFileName;
		}

		String getFileName()
		{
			return fileName;
		}

		String getAlternateFileName()
		{
			return alternateFileName;
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
