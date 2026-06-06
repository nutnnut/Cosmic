package client.command.commands.gm2;

import client.Client;
import client.command.Command;
import server.Shop;
import server.ShopFactory;

public class ShopCommand extends Command {
    {
        setDescription("Open the GM supply shop.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Shop shop = ShopFactory.getInstance().getShop(9010001);
        if (shop != null) {
            shop.sendShop(c);
        } else {
            c.getPlayer().message("Shop data not found. Please run lumenms-shop.sql.");
        }
    }
}
