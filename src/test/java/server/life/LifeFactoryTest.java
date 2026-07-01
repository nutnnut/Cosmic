package server.life;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LifeFactoryTest {
    @Test
    void shouldTreatMissingFlatDefenseAsZeroForRateDefenseMob() {
        Data monsterData = DataProviderFactory.getDataProvider(WZFiles.MOB)
                .getData(StringUtil.getLeftPaddedStr(8641013 + ".img", '0', 11));
        assertNotNull(monsterData);

        Data info = monsterData.getChildByPath("info");
        assertEquals(209, DataTool.getIntConvert("level", info));
        assertNotNull(info.getChildByPath("PDRate"));
        assertNotNull(info.getChildByPath("MDRate"));
        assertNull(info.getChildByPath("PDDamage"));
        assertNull(info.getChildByPath("MDDamage"));
        assertEquals(0, DataTool.getIntConvert("PDDamage", info, 0));
        assertEquals(0, DataTool.getIntConvert("MDDamage", info, 0));
    }
}
