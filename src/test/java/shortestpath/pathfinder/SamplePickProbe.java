package shortestpath.pathfinder;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

@RunWith(MockitoJUnitRunner.class)
public class SamplePickProbe
{
	private static final Map<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();
	@Mock Client client;
	@Mock ItemContainer inventory;
	@Mock ItemContainer equipment;
	@Mock ItemContainer bank;
	@Mock ShortestPathConfig config;

	@Test
	public void probeFairyRingUsability() throws Exception
	{
		// Replicate testFairyRingsUsedWithDramenStaffWornInHand setup
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useFairyRings()).thenReturn(true);
		doReturn(inventory).when(client).getItemContainer(InventoryID.INV);
		doReturn(new Item[0]).when(inventory).getItems();
		doReturn(equipment).when(client).getItemContainer(InventoryID.WORN);
		doReturn(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)}).when(equipment).getItems();
		when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);

		TestPathfinderConfig pfc = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
		pfc.refresh();

		// Find the sample like findSampleTransport does
		Transport sample = null;
		for (int origin : transports.keySet())
		{
			for (Transport t : transports.get(origin))
			{
				if (TransportType.FAIRY_RING.equals(t.getType()) && !shortestpath.ShortestPathPlugin.isInsidePoh(
					t.getOrigin() >> 14 & 0x3FFF, t.getOrigin() & 0x3FFF))
				{
					sample = t;
					break;
				}
			}
			if (sample != null) break;
		}
		System.out.println("PROBE sample=" + sample);

		// Is it in the usable maps?
		PrimitiveIntHashMap<Transport[]> packed = pfc.getTransportsPacked(false);
		Transport[] atOrigin = packed.get(sample.getOrigin());
		System.out.println("PROBE packedAtOrigin=" + java.util.Arrays.toString(atOrigin));

		// Dump private state
		Field vb = PathfinderConfig.class.getDeclaredField("varbitValues");
		vb.setAccessible(true);
		Map<Integer, Integer> varbitValues = (Map<Integer, Integer>) vb.get(pfc);
		System.out.println("PROBE varbit4498=" + varbitValues.get(4498) + " varbitValuesSize=" + varbitValues.size());

		Field iq = PathfinderConfig.class.getDeclaredField("itemsAndQuantities");
		iq.setAccessible(true);
		// call hasRequiredItems indirectly: check if any fairy ring at all is usable
		int usableRings = 0;
		for (int o : packed.keys())
			for (Transport t : packed.get(o))
				if (TransportType.FAIRY_RING.equals(t.getType())) usableRings++;
		System.out.println("PROBE usableRings=" + usableRings + " packedKeys=" + packed.size());
	}
}
