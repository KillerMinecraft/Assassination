package com.ftwinston.KillerMinecraft.Modules.Assassination;

import org.bukkit.Material;

import com.ftwinston.KillerMinecraft.GameMode;
import com.ftwinston.KillerMinecraft.GameModePlugin;

public class Plugin extends GameModePlugin
{
	@Override
	public Material getMenuIcon() { return Material.BOOK_AND_QUILL; }
	
	@Override
	public String[] getDescriptionText() { return new String[] {"Each player tries to kill one other", "player, while being hunted by", "someone else. Kill your target and", "their target becomes yours."}; }
	
	@Override
	public GameMode createInstance()
	{
		return new Assassination();
	}
}