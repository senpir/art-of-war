package com.artofwar;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ArtOfWarPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ArtOfWarPlugin.class);
		RuneLite.main(args);
	}
}
