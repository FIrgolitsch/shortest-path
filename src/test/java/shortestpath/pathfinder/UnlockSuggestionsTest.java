package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.items.*;
import shortestpath.pathfinder.astar.RoutingStatic;
import shortestpath.presentation.*;
import shortestpath.requirement.Capability;
import shortestpath.requirement.model.SkillLevels;
import shortestpath.settings.TeleportationItem;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

/** What an A* search that can't reach its target suggests unlocking, on the real world data. */
@RunWith(MockitoJUnitRunner.class)
public class UnlockSuggestionsTest
{
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int SUNSET_COAST = WorldPointUtil.packWorldPoint(1495, 2985, 0);
	private static final int ALDARIN = WorldPointUtil.packWorldPoint(1432, 2960, 0);

	private static RoutingStatic routingStatic;

	@Mock
	Client client;
	@Mock
	ItemContainer inventory;
	private final TestShortestPathConfig config = new TestShortestPathConfig();
	private PathfinderConfig pathfinderConfig;
	/** What is found shouldn't depend on how busy the machine is; the budget test sets it back. */
	private long budget = 10 * UnlockSuggestions.BUDGET_NANOS;

	@BeforeClass
	public static void buildRoutingStatic()
	{
		routingStatic = TestWorldData.routingStatic();
	}

	@Before
	public void before()
	{
		config.set("calculationCutoff", 30);
		config.set("currencyThreshold", 10000000);
		config.set("useTeleportationItems", TeleportationItem.NONE);
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		doReturn(inventory).when(client).getItemContainer(InventoryID.INV);
	}

	/** A config whose look for unlocks may take {@link #budget}. */
	private PathfinderConfig newConfig()
	{
		return new TestPathfinderConfig(client, config, QuestState.NOT_STARTED, true, true)
		{
			@Override
			long unlockBudgetNanos()
			{
				return budget;
			}
		};
	}

	/** An account with no quests done, every skill at {@code level}, carrying {@code items}. */
	private void setup(int level, Item... items)
	{
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(level);
		doReturn(items).when(inventory).getItems();
		pathfinderConfig = newConfig();
		pathfinderConfig.refresh();
	}

	private AStarPathfinder search(int start, int target)
	{
		AStarPathfinder search = new AStarPathfinder(pathfinderConfig, routingStatic, start, Set.of(target), null);
		search.run();
		search.findUnlockSuggestions();
		return search;
	}

	@Test
	public void carryingCoinsForTheBoatToAldarinComesBeforeTwilightsPromise()
	{
		// On foot Aldarin is an island: from the Sunset Coast, Antonia's boat takes 20 coins and
		// the quetzal needs Twilight's Promise.
		config.disableTransportToggles();
		config.set("useShips", true);
		config.set("useQuetzals", true);
		setup(99);

		AStarPathfinder search = search(SUNSET_COAST, ALDARIN);

		assertFalse(search.getResult().isReached());
		UnlockSuggestions.Suggestion first = search.getUnlockSuggestion().get();
		assertEquals(Capability.items(Map.of(ItemID.COINS, 20)), first.getCapability());
		assertEquals(1, first.getTransports().size());
		assertEquals(TransportType.SHIP, first.getTransports().get(0).getType());
		assertEquals("Aldarin", first.getTransports().get(0).getDisplayInfo());

		// And following the suggestion does get there.
		setup(99, new Item(ItemID.COINS, 20));
		assertTrue(search(SUNSET_COAST, ALDARIN).getResult().isReached());
	}

	@Test
	public void coinsInTheBankReadAsAReminderToBringThem()
	{
		// An ironman with the fare in the bank but not on them, and routes through a bank turned off.
		ItemContainer bank = mock(ItemContainer.class);
		doReturn(new Item[]{new Item(ItemID.COINS, 1000)}).when(bank).getItems();
		ItemStateService itemState = ItemStateTestServices.forTesting();
		itemState.onContainerChanged(InventoryID.BANK, bank);
		config.disableTransportToggles();
		config.set("useShips", true);
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(new Item[0]).when(inventory).getItems();
		pathfinderConfig = newConfig();
		pathfinderConfig.setItemStateService(itemState);
		pathfinderConfig.refresh();

		AStarPathfinder search = search(SUNSET_COAST, ALDARIN);

		UnlockSuggestions.Suggestion first = search.getUnlockSuggestion().get();
		assertEquals(Capability.Effort.OWNED_ITEMS, first.getEffort());
		ItemComposition coins = mock(ItemComposition.class);
		when(coins.getName()).thenReturn("Coins");
		when(client.getItemDefinition(ItemID.COINS)).thenReturn(coins);
		PathAnnotator annotator = PathAnnotatorTestServices.forTesting(client, null, null);
		String text = annotator.getUnlockSuggestionText(search);
		assertEquals("Bring 20 Coins from your bank to use: Ship Aldarin", text);
		// Built once, not every frame.
		assertSame(text, annotator.getUnlockSuggestionText(search));
	}

	/** The Sunset Coast to Aldarin search with ships and quetzals only, running {@code completed} once it's done. */
	private AStarPathfinder aldarinSearch(Consumer<AStarPathfinder> completed)
	{
		config.disableTransportToggles();
		config.set("useShips", true);
		config.set("useQuetzals", true);
		setup(99);
		AStarPathfinder[] search = new AStarPathfinder[1];
		search[0] = new AStarPathfinder(pathfinderConfig, routingStatic, SUNSET_COAST, Set.of(ALDARIN),
			() -> completed.accept(search[0]));
		return search[0];
	}

	@Test
	public void theSearchPublishesItsResultWithoutLookingForUnlocks()
	{
		// So the unreachable text shows at once, and the search's own time is only the search.
		boolean[] doneWithoutSuggestions = new boolean[1];
		AStarPathfinder search = aldarinSearch(done -> doneWithoutSuggestions[0] = done.isDone()
			&& !done.getResult().isReached() && done.getUnlockSuggestion().isEmpty());

		search.run();

		assertTrue("unreachable is out before the suggestions", doneWithoutSuggestions[0]);
		assertEquals("run() didn't look", 0, search.getUnlockSearchNanos());
		search.findUnlockSuggestions();
		assertFalse(search.getUnlockSuggestion().isEmpty());
	}

	@Test
	public void aCancelledSearchDropsItsSuggestions()
	{
		// Cancelled as soon as its result is out, as when a new search replaces it: it doesn't look.
		AStarPathfinder replaced = aldarinSearch(AStarPathfinder::cancel);
		replaced.run();
		replaced.findUnlockSuggestions();
		assertTrue(replaced.getUnlockSuggestion().isEmpty());
		assertEquals(0, replaced.getUnlockSearchNanos());

		// Cancelled after they're found: they're dropped.
		AStarPathfinder later = aldarinSearch(done -> assertTrue(done.isDone()));
		later.run();
		later.findUnlockSuggestions();
		assertFalse(later.getUnlockSuggestion().isEmpty());
		later.cancel();
		assertTrue(later.getUnlockSuggestion().isEmpty());
	}

	@Test
	public void aLookOutOfTimeStopsBetweenStages()
	{
		// With no budget at all it stops once the component graph is built, before weighing anything.
		budget = 0;
		AStarPathfinder search = aldarinSearch(done -> { });
		search.run();
		search.findUnlockSuggestions();

		assertEquals(UnlockSuggestions.Outcome.INCONCLUSIVE, search.getUnlockSearch().getOutcome());
		assertTrue(search.getUnlockSuggestion().isEmpty());
		assertNull("nothing to rerun", search.getUnlockSearch().getExplanation());
	}

	@Test
	public void withoutTheBoatTheQuetzalNeedsTwilightsPromise()
	{
		config.disableTransportToggles();
		config.set("useQuetzals", true);
		setup(99);

		UnlockSuggestions.Suggestion suggestion = search(SUNSET_COAST, ALDARIN).getUnlockSuggestion().get();

		assertEquals(Capability.of(Set.of(Quest.TWILIGHTS_PROMISE), SkillLevels.NONE, Map.of()), suggestion.getCapability());
		assertEquals(Capability.Effort.PROGRESS, suggestion.getEffort());
		assertTrue(suggestion.getTransports().stream().allMatch(transport -> transport.getType() == TransportType.QUETZAL));
	}

	@Test
	public void aTargetOnlyASettingClosesSuggestsNothingAtOnce()
	{
		// With neither the boat nor the quetzal on, no unlock gets the player onto Aldarin.
		config.disableTransportToggles();
		setup(99);

		AStarPathfinder search = search(SUNSET_COAST, ALDARIN);

		assertFalse(search.getResult().isReached());
		assertEquals(UnlockSuggestions.Outcome.CLOSED, search.getUnlockSearch().getOutcome());
		assertTrue(search.getUnlockSuggestion().isEmpty());
		assertNull("nothing to rerun", search.getUnlockSearch().getExplanation());
	}

	@Test
	public void unlocksAlongOnePathAreSuggestedTogether()
	{
		// Just off Aldarin: Antonia's boat lands on the island, and the way on from there is behind
		// Death on the Isle. Neither the fare nor the quest gets there alone; both together do.
		config.disableTransportToggles();
		config.set("useShips", true);
		config.set("useQuetzals", true);
		setup(99);
		int target = WorldPointUtil.packWorldPoint(1455, 2934, 0);

		AStarPathfinder search = search(SUNSET_COAST, target);

		assertFalse(search.getResult().isReached());
		UnlockSuggestions.Suggestion suggestion = search.getUnlockSuggestion().get();
		assertEquals(Capability.of(Set.of(Quest.DEATH_ON_THE_ISLE), SkillLevels.NONE, Map.of(ItemID.COINS, 20)),
			suggestion.getCapability());
		assertEquals(TransportType.SHIP, suggestion.getTransports().get(0).getType());
		assertTrue(suggestion.getTransports().size() > 1);
		setup(99, new Item(ItemID.COINS, 20));
		assertFalse("the fare alone doesn't help", search(SUNSET_COAST, target).getResult().isReached());
	}

	@Test
	public void itemsComeBeforeAMajorSkillGoal()
	{
		// By Tai Bwo Wannai on a level 1 account, carrying an axe and a machete to cut through the
		// jungle gets there, and is far less than the 79 Agility of the vine shortcut, which also does.
		int target = WorldPointUtil.packWorldPoint(2795, 2940, 0);
		setup(1);

		AStarPathfinder search = search(LUMBRIDGE, target);

		assertFalse(search.getResult().isReached());
		assertTrue(search.debugStats().toString(), search.getUnlockSuggestion().isPresent());
		Capability capability = search.getUnlockSuggestion().get().getCapability();
		assertEquals(Map.of(ItemID.BRONZE_AXE, 1, ItemID.MACHETTE, 1), capability.getItems());
		int[] agility = new int[SkillLevels.LENGTH];
		agility[Skill.AGILITY.ordinal()] = 79;
		assertNotEquals(SkillLevels.of(agility), capability.getSkillLevels());
		when(client.getBoostedSkillLevel(Skill.AGILITY)).thenReturn(79);
		pathfinderConfig.refresh();
		assertTrue("79 Agility also gets there", search(LUMBRIDGE, target).getResult().isReached());
	}

	@Test
	public void fairyRingsThePlayerCanUseCarryTheWayToALockedTransport()
	{
		// On the island south-east of Ardougne the only way off is the fairy ring. Without the
		// Lumbridge Elite diary the rings need a staff, which the player carries.
		config.disableTransportToggles();
		config.set("useShips", true);
		config.set("useFairyRings", true);
		when(client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST)).thenReturn(100);
		when(client.getVarbitValue(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE)).thenReturn(0);
		setup(99, new Item(ItemID.DRAMEN_STAFF, 1));

		AStarPathfinder search = search(WorldPointUtil.packWorldPoint(2700, 3246, 0), ALDARIN);

		assertFalse(search.getResult().isReached());
		UnlockSuggestions.Suggestion suggestion = search.getUnlockSuggestion().get();
		assertEquals(Capability.items(Map.of(ItemID.COINS, 20)), suggestion.getCapability());
		assertEquals(List.of(TransportType.SHIP),
			suggestion.getTransports().stream().map(Transport::getType).collect(Collectors.toList()));
	}

	@Test
	public void aPathPaysForAnUnlockOncePerUse()
	{
		// The documented approximation: costs add up per locked use, so three hops behind one quest
		// weigh more than two hops behind two different quests, though the first asks for less.
		int[] oneQuest = new int[Capability.Effort.values().length];
		oneQuest[Capability.Effort.PROGRESS.ordinal()] = 1;
		long oneQuestThrice = 3 * UnlockSuggestions.Cost.of(oneQuest);
		long twoQuests = UnlockSuggestions.Cost.of(oneQuest) + UnlockSuggestions.Cost.of(oneQuest);

		assertTrue(oneQuestThrice > twoQuests);
	}

	/** Targets a level 1 account, which has the most locked transports to weigh, can't reach from Lumbridge. */
	private static final int[] LONG_ROUTE_TARGETS = {ALDARIN, WorldPointUtil.packWorldPoint(2932, 2936, 0),
		WorldPointUtil.packWorldPoint(2795, 2940, 0), WorldPointUtil.packWorldPoint(1388, 9313, 0)};

	@Test
	public void longRoutesFindASuggestion()
	{
		setup(1);
		for (int target : LONG_ROUTE_TARGETS)
		{
			AStarPathfinder search = search(LUMBRIDGE, target);

			assertFalse(search.getResult().isReached());
			assertTrue(search.debugStats().toString(), search.getUnlockSuggestion().isPresent());
		}
	}

	@Test
	public void longRoutesStayNearTheBudget()
	{
		// With the real budget, what is found depends on the machine, so only the time is checked, and
		// coarsely: a stage already running can carry past the deadline, and a busy machine slows all.
		budget = UnlockSuggestions.BUDGET_NANOS;
		setup(1);
		for (int target : LONG_ROUTE_TARGETS)
		{
			AStarPathfinder search = search(LUMBRIDGE, target);

			assertTrue(search.debugStats().toString(), search.getUnlockSearchNanos() < 2 * UnlockSuggestions.BUDGET_NANOS);
		}
	}
}
