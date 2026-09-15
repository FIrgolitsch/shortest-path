package shortestpath.pathfinder;

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
import shortestpath.ShortestPathPlugin;
import shortestpath.TeleportationItem;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;
import shortestpath.WorldPointUtil;

@RunWith(MockitoJUnitRunner.class)
public class SamplePickProbe
{
	private static final Map<Integer, Set<Transport>> transports = TransportLoader.loadAllFromResources();
	@Mock Client client;
	@Mock ItemContainer inventory;
	@Mock ItemContainer equipment;
	@Mock ItemContainer bank;
	@Mock ShortestPathConfig config;

	private void setup()
	{
		when(config.calculationCutoff()).thenReturn(30);
		when(config.currencyThreshold()).thenReturn(10000000);
		when(config.useFairyRings()).thenReturn(true);
		doReturn(inventory).when(client).getItemContainer(InventoryID.INV);
		doReturn(new Item[0]).when(inventory).getItems();
		doReturn(equipment).when(client).getItemContainer(InventoryID.WORN);
		doReturn(new Item[]{new Item(ItemID.DRAMEN_STAFF, 1)}).when(equipment).getItems();
		when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.NONE);
	}

	@Test
	public void probeAllFirstBucketRings()
	{
		TestPathfinderConfig pfc = new TestPathfinderConfig(client, config, QuestState.FINISHED, true, true);
		setup();
		pfc.refresh();

		PrimitiveIntHashMap<Transport[]> packed = pfc.getTransportsPacked(false);

		// first bucket containing a fairy ring — same as findSampleTransport scan
		int bucketIdx = -1;
		Set<Transport> bucket = null;
		for (int origin : transports.keySet())
		{
			boolean has = false;
			for (Transport t : transports.get(origin))
				if (TransportType.FAIRY_RING.equals(t.getType())
					&& !ShortestPathPlugin.isInsidePoh(WorldPointUtil.unpackWorldX(t.getOrigin()), WorldPointUtil.unpackWorldY(t.getOrigin())))
					has = true;
			if (has) { bucketIdx = origin; bucket = transports.get(origin); break; }
		}
		System.out.println("PROBE firstBucketOrigin=" + WorldPointUtil.unpackWorldX(bucketIdx) + "," + WorldPointUtil.unpackWorldY(bucketIdx));

		for (Transport t : bucket)
		{
			if (!TransportType.FAIRY_RING.equals(t.getType())) continue;
			if (ShortestPathPlugin.isInsidePoh(WorldPointUtil.unpackWorldX(t.getOrigin()), WorldPointUtil.unpackWorldY(t.getOrigin()))) continue;
			Transport[] usable = packed.getOrDefault(t.getOrigin(), new Transport[0]);
			boolean inUsable = false;
			for (Transport u : usable) if (u == t) inUsable = true;
			Pathfinder pf = new Pathfinder(pfc, t.getOrigin(), Set.of(t.getDestination()));
			pf.run();
			System.out.println("PROBE ring dest=" + WorldPointUtil.unpackWorldX(t.getDestination()) + "," + WorldPointUtil.unpackWorldY(t.getDestination())
				+ " usable=" + inUsable + " pathLen=" + pf.getPath().size()
				+ " term=" + (pf.getResult() != null ? pf.getResult().getTerminationReason() : "null"));
		}
	}
}
