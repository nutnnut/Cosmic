package constants.inventory;

/**
 * @author The Spookster (The Real Spookster)
 */
public enum EquipSlot {

    HAT("Cp", -1),
    SPECIAL_HAT("HrCp", -1),
    FACE_ACCESSORY("Af", -2),
    EYE_ACCESSORY("Ay", -3),
    EARRINGS("Ae", -4),
    TOP("Ma", -5),
    OVERALL("MaPn", -5),
    PANTS("Pn", -6),
    SHOES("So", -7),
    GLOVES("GlGw", -8),
    CASH_GLOVES("Gv", -8),
    CAPE("Sr", -9),
    SHIELD("Si", -10),
    WEAPON("Wp", -11),
    WEAPON_2("WpSi", -11),
    LOW_WEAPON("WpSp", -11),
    RING("Ri", -12, -13, -15, -16),
    PENDANT("Pe", -17),
    TAMED_MOB("Tm", -18),
    SADDLE("Sd", -19),
    MEDAL("Me", -49),
    BELT("Be", -50),
    PET_EQUIP;

    private String name;
    private int[] allowed;

    EquipSlot() {
    }

    EquipSlot(String wz, int... in) {
        name = wz;
        allowed = in;
    }

    public String getName() {
        return name;
    }

    /** Returns the primary (first) allowed slot number, or 0 if none. */
    public int getPrimarySlot() {
        return (allowed != null && allowed.length > 0) ? allowed[0] : 0;
    }

    /** Number of distinct slots this kind can occupy at once (e.g. 4 for rings, 1 for most). */
    public int getSlotCount() {
        return allowed != null ? allowed.length : 0;
    }

    public boolean isAllowed(int slot, boolean cash) {
        if (slot < 0) {
            if (allowed != null) {
                for (Integer allow : allowed) {
                    int condition = cash ? allow - 100 : allow;
                    if (slot == condition) {
                        return true;
                    }
                }
            }
        }
        return cash && slot < 0;
    }

    public static EquipSlot getFromTextSlot(String slot) {
        if (slot != null && !slot.isEmpty()) {
            for (EquipSlot c : values()) {
                if (c.getName() != null) {
                    if (c.getName().equals(slot)) {
                        return c;
                    }
                }
            }
        }
        return PET_EQUIP;
    }
}
