package constants.inventory;

import java.util.Map;

public class EquipStats {
    int itemId;
    Map<String, Integer> stats;

    public EquipStats(int itemId, Map<String, Integer> stats) {
        this.itemId = itemId;
        this.stats = stats;
    }

    public int getItemId() {
        return itemId;
    }

    public Map<String, Integer> getStats() {
        return stats;
    }
}
