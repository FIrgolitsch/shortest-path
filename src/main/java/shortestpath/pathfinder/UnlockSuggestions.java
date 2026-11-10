package shortestpath.pathfinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.PrimitiveIntList;
import shortestpath.pathfinder.astar.AStarForwardSearch;
import shortestpath.pathfinder.astar.PreparedRoutingAccount;
import shortestpath.pathfinder.astar.PreparedTarget;
import shortestpath.pathfinder.astar.RoutingStatic;
import shortestpath.pathfinder.astar.SearchRestrictions;
import shortestpath.pathfinder.astar.SiteGraph;
import shortestpath.pathfinder.astar.TeleportCapability;
import shortestpath.requirement.Capability;
import shortestpath.requirement.Requirements;
import shortestpath.transport.Transport;

/**
 * What the player could unlock to reach a target the A* search found unreachable. On the graph of
 * routing components joined by every transport no setting or world restriction closes, and by the
 * crossings, one Dijkstra search from the player to the target weighs each locked transport by the
 * effort of what would open it, hardest {@link Capability.Effort} tier first and the journey's
 * duration last, so carrying 20 coins for the boat to Aldarin beats Twilight's Promise for the
 * quetzal however long the boat takes. The locked transports on the cheapest path name the unlocks,
 * each once, and the search is rerun with them granted: only if it then reaches the target are they
 * suggested.
 *
 * <p>The ranking is an approximation. Dijkstra needs additive costs, so a path costs the sum of its
 * locked uses while the suggestion asks for their union: an unlock several hops share counts once
 * per hop, and three hops behind one quest outweigh two hops behind two different quests. Each
 * locked transport also takes its own cheapest capability, regardless of what the rest of the path
 * already unlocks. Ranking by the union exactly would take a search over sets of unlocks; the rerun
 * still checks whatever is suggested.
 */
public final class UnlockSuggestions
{
	/**
	 * Up to one game tick, on top of the search that found the target unreachable. The deadline is
	 * checked inside the searches and between the stages that build their graphs, so a stage that
	 * is already running can still carry the look past it.
	 */
	static final long BUDGET_NANOS = 600_000_000L;
	/** A rerun stopped at the deadline takes a moment to return, so it is stopped this much early. */
	private static final long WIND_DOWN_NANOS = 10_000_000L;
	/** The heuristic weight of the rerun's second, greedier search. */
	private static final double QUICK_WEIGHT = 3.0;

	/** The unlocks that let the route reach the target, and the locked transports they open on the way. */
	@Getter
	public static final class Suggestion
	{
		private final Capability capability;
		/** The locked transports on the way, in the order the route takes them. */
		private final List<Transport> transports;
		/** The tier of its hardest part, for this player. */
		private final Capability.Effort effort;

		Suggestion(Capability capability, List<Transport> transports, Capability.Effort effort)
		{
			this.capability = capability;
			this.transports = List.copyOf(transports);
			this.effort = effort;
		}
	}

	/** Why the look ended as it did. */
	public enum Outcome
	{
		/** Not looked for. */
		NONE,
		/** No path at all, even with every unlock: a setting or world restriction closes the way. */
		CLOSED,
		/**
		 * The component graph joins the player to the target without unlocks, so what blocks the
		 * route is something it can't see, such as a position the route must avoid.
		 */
		ALREADY_CONNECTED,
		/** The rerun with the unlocks granted reached the target: they are suggested. */
		CONFIRMED,
		/** The rerun with the unlocks granted settled that the target is still unreachable. */
		REJECTED,
		/** Not settled within the budget. */
		INCONCLUSIVE,
		CANCELLED
	}

	/** What one look found. */
	@Getter
	@AllArgsConstructor(access = AccessLevel.PACKAGE)
	public static final class Found
	{
		static final Found NONE = new Found(Outcome.NONE, null);

		private final Outcome outcome;
		/** The unlocks the cheapest path names, confirmed or not; {@code null} when there are none. */
		private final Suggestion explanation;
	}

	private final Requirements requirements;
	private final long budgetNanos;
	private final Function<Requirements, PreparedRoutingAccount> accountWith;
	private final Supplier<TransportAvailability> obtainable;

	/**
	 * @param budgetNanos how long a look may take, {@link #BUDGET_NANOS} but in tests
	 * @param accountWith the routing account the player would have under other requirements
	 * @param obtainable every transport no setting or world restriction closes, each fairy ring
	 * wrapped once as the requirements see it
	 */
	UnlockSuggestions(Requirements requirements, long budgetNanos,
		Function<Requirements, PreparedRoutingAccount> accountWith, Supplier<TransportAvailability> obtainable)
	{
		this.requirements = requirements;
		this.budgetNanos = budgetNanos;
		this.accountWith = accountWith;
		this.obtainable = obtainable;
	}

	Found find(RoutingStatic stat, CollisionMap collision, int start, GoalStates goals,
		SearchRestrictions restrictions, double heuristicWeight, BooleanSupplier cancelled)
	{
		long deadline = System.nanoTime() + budgetNanos - WIND_DOWN_NANOS;
		BooleanSupplier stop = () -> cancelled.getAsBoolean() || System.nanoTime() > deadline;
		int[] targets = goals.tiles().stream().mapToInt(Integer::intValue).toArray();
		ComponentGraph graph = new ComponentGraph(stat, obtainable.get());
		long[] cost = new long[graph.transports.size()];
		Capability[] opens = new Capability[cost.length];
		Deque<Integer> path = null;
		if (!stop.getAsBoolean())
		{
			weigh(graph, cost, opens);
			path = stop.getAsBoolean() ? null
				: graph.cheapest(graph.components(collision, start), graph.components(collision, targets), cost, stop);
		}
		if (path == null)
		{
			return new Found(stop.getAsBoolean() ? stopped(cancelled) : Outcome.CLOSED, null);
		}

		// The unlocks of the locked transports on the path, each once, in the order the route takes them.
		Capability unlocks = null;
		List<Transport> locked = new ArrayList<>();
		for (int edge : path)
		{
			if (opens[edge] != null)
			{
				unlocks = unlocks == null ? opens[edge] : unlocks.union(opens[edge]);
				locked.add(graph.transports.get(edge));
			}
		}
		if (unlocks == null)
		{
			return new Found(Outcome.ALREADY_CONNECTED, null);
		}
		Suggestion explanation = new Suggestion(unlocks, locked, requirements.effort(unlocks));
		AStarForwardSearch.Result route = rerun(unlocks, stat, collision, targets, start, heuristicWeight, stop,
			deadline, restrictions, goals);
		Outcome outcome = route == null || route.cancelled() ? stopped(cancelled)
			: route.reached() ? Outcome.CONFIRMED : Outcome.REJECTED;
		return new Found(outcome, explanation);
	}

	private static Outcome stopped(BooleanSupplier cancelled)
	{
		return cancelled.getAsBoolean() ? Outcome.CANCELLED : Outcome.INCONCLUSIVE;
	}

	/**
	 * Fills each edge's {@code cost} and, for a locked transport, what it {@code opens} with: the
	 * cheapest of the capabilities that would open it on its own. A locked transport no capability
	 * opens, such as one behind a declared unlock, costs {@link ComponentGraph#CLOSED}.
	 */
	private void weigh(ComponentGraph graph, long[] cost, Capability[] opens)
	{
		Map<Transport, Capability> cheapest = new IdentityHashMap<>();
		Map<Transport, Long> lockCost = new IdentityHashMap<>();
		for (int edge = 0; edge < cost.length; edge++)
		{
			Transport transport = graph.transports.get(edge);
			long duration = graph.durations.get(edge);
			if (transport == null)
			{
				cost[edge] = duration;
				continue;
			}
			if (!lockCost.containsKey(transport))
			{
				// The player can use it already: it costs only its duration.
				long best = requirements.usable(transport) ? 0 : ComponentGraph.CLOSED;
				for (Capability capability : best == 0 ? List.<Capability>of() : requirements.capabilities(transport))
				{
					long weight = cost(requirements.effortUnits(capability));
					if (weight < best)
					{
						best = weight;
						cheapest.put(transport, capability);
					}
				}
				lockCost.put(transport, best);
			}
			long weight = lockCost.get(transport);
			cost[edge] = weight == ComponentGraph.CLOSED ? weight : weight + duration;
			opens[edge] = cheapest.get(transport);
		}
	}

	private static final int TIER_BITS = 9;
	private static final int DURATION_BITS = 63 - TIER_BITS * Capability.Effort.values().length;

	/**
	 * The cost of opening with these {@link Requirements#effortUnits units}, without duration. An
	 * edge's cost is packed into one long so that comparing two compares them lexicographically: the
	 * units of each effort tier, hardest first, above the duration in ticks. Repeated uses of a locked
	 * transport add up, keeping the costs additive for Dijkstra; units overflowing their field carry
	 * into the harder tier above, which only makes a pathological path dearer.
	 */
	static long cost(int[] units)
	{
		long packed = 0;
		for (int tier = units.length - 1; tier >= 0; tier--)
		{
			packed = (packed << TIER_BITS) | Math.min(units[tier], (1 << TIER_BITS) - 1);
		}
		return packed << DURATION_BITS;
	}

	/**
	 * The search rerun with {@code capability} granted, or {@code null} when {@code stop} came first.
	 * It only has to show the target can be reached: the plain search gets half the time left, and
	 * one still going after that is usually a long way round, which a greedier search finds quickly.
	 * A result still {@link AStarForwardSearch.Result#cancelled() cancelled} settled nothing in time.
	 */
	private AStarForwardSearch.Result rerun(Capability capability, RoutingStatic stat, CollisionMap collision,
		int[] targets, int start, double heuristicWeight, BooleanSupplier stop, long deadline,
		SearchRestrictions restrictions, GoalStates goals)
	{
		PreparedRoutingAccount account = accountWith.apply(requirements.granting(capability));
		if (stop.getAsBoolean())
		{
			return null;
		}
		SiteGraph granted = SiteGraph.compile(stat, account);
		if (stop.getAsBoolean())
		{
			return null;
		}
		PreparedTarget prepared = PreparedTarget.prepare(granted, collision, targets);
		long sliceEnd = System.nanoTime() + (deadline - System.nanoTime()) / 2;
		AStarForwardSearch.Result route = prepared.search(start, () -> stop.getAsBoolean() || System.nanoTime() > sliceEnd,
			heuristicWeight, restrictions, AStarForwardSearch.NO_PROGRESS, goals::accepts);
		if (route.cancelled() && !stop.getAsBoolean())
		{
			route = prepared.search(start, stop, Math.max(heuristicWeight, QUICK_WEIGHT), restrictions,
				AStarForwardSearch.NO_PROGRESS, goals::accepts);
		}
		return route;
	}

	/**
	 * Routing components joined by the obtainable transports and by the static crossings. Walking
	 * within a component is free and positional restrictions such as avoiding the Wilderness are
	 * ignored, so reachability here is optimistic; the search rerun has the last word. Built and
	 * searched once per look.
	 * ponytail: free walking leaves only the transports' durations to break ties between unlocks of
	 * equal effort; weigh walks between a component's sites if those ties pick badly.
	 */
	private static final class ComponentGraph
	{
		/** The cost of an edge nothing opens. */
		static final long CLOSED = Long.MAX_VALUE;

		private final RoutingStatic stat;
		private final int[][] siteComponents;
		private final int componentCount;
		// Per edge: the site it lands on, its transport (null for a crossing) and its duration. The
		// edges leaving a component are in outgoing; those cast anywhere are in anywhere.
		private final PrimitiveIntList to = new PrimitiveIntList();
		private final List<Transport> transports = new ArrayList<>();
		private final List<PrimitiveIntList> outgoing = new ArrayList<>();
		private final PrimitiveIntList anywhere = new PrimitiveIntList();
		private final PrimitiveIntList durations = new PrimitiveIntList();
		// The search: each edge's cost, each component's distance, the edge it was reached by and the
		// component that edge left (-1 at the start), and the components still to settle.
		private long[] cost, distance;
		private int[] via, previous;
		private final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));

		ComponentGraph(RoutingStatic stat, TransportAvailability obtainable)
		{
			this.stat = stat;
			siteComponents = new int[stat.siteCount()][];
			int count = stat.routingComponentCount();
			for (int site = 0; site < siteComponents.length; site++)
			{
				siteComponents[site] = stat.siteComponents(site);
				// A site off the walkable tiles, such as a landing that is only the start of another
				// transport, is a component of its own, so the transports through it still join.
				if (siteComponents[site].length == 0)
				{
					siteComponents[site] = new int[] {count++};
				}
			}
			componentCount = count;
			for (int component = 0; component < componentCount; component++)
			{
				outgoing.add(new PrimitiveIntList());
			}
			// ponytail: one view for both bank layers, as if every route could visit a bank; split
			// them if banked-only transports start producing suggestions the rerun rejects.
			PrimitiveIntHashMap<Transport[]> local = obtainable.getTransportsPacked();
			int[] origins = local.keys();
			Arrays.sort(origins);
			for (int origin : origins)
			{
				// A site index below zero means anywhere, so an origin the static data doesn't know must
				// fail loudly, as it does in SiteGraph, rather than turn the transport into a teleport.
				if (stat.siteIndex(origin) < 0)
				{
					throw new IllegalStateException("local origin is not a static site: " + Integer.toUnsignedString(origin));
				}
				for (Transport transport : local.get(origin))
				{
					add(stat.siteIndex(origin), stat.siteIndex(transport.getDestination()), transport,
						transport.getDuration());
				}
			}
			for (Transport transport : obtainable.getUsableTeleports())
			{
				if (TeleportCapability.ALL.canCast(transport))
				{
					add(-1, stat.siteIndex(transport.getDestination()), transport, transport.getDuration());
				}
			}
			for (int i = 0; i < stat.crossingCount(); i++)
			{
				add(stat.crossingFromSite(i), stat.crossingToSite(i), null, stat.crossingCost(i));
				add(stat.crossingToSite(i), stat.crossingFromSite(i), null, stat.crossingCost(i));
			}
		}

		private void add(int fromSite, int toSite, Transport transport, int duration)
		{
			int edge = transports.size();
			durations.add(Math.max(0, duration));
			to.add(toSite);
			transports.add(transport);
			if (fromSite < 0)
			{
				anywhere.add(edge);
			}
			else
			{
				for (int component : siteComponents[fromSite])
				{
					outgoing.get(component).add(edge);
				}
			}
		}

		boolean[] components(CollisionMap collision, int... tiles)
		{
			boolean[] components = new boolean[componentCount];
			for (int tile : tiles)
			{
				int[] attached = stat.attachments(tile, collision);
				int site = stat.siteIndex(tile);
				for (int component : attached.length == 0 && site >= 0 ? siteComponents[site] : attached)
				{
					components[component] = true;
				}
			}
			return components;
		}

		/**
		 * The edges of the cheapest path by {@code cost} from a {@code starts} component, or from
		 * anywhere through a teleport, to a {@code targets} component, in order; {@code null} when
		 * every way there is {@link #CLOSED}, or once {@code stop} says so.
		 */
		Deque<Integer> cheapest(boolean[] starts, boolean[] targets, long[] cost, BooleanSupplier stop)
		{
			this.cost = cost;
			distance = new long[componentCount];
			Arrays.fill(distance, Long.MAX_VALUE);
			via = new int[componentCount];
			previous = new int[componentCount];
			Arrays.fill(via, -1);
			Arrays.fill(previous, -1);
			for (int component = 0; component < componentCount; component++)
			{
				if (starts[component])
				{
					distance[component] = 0;
					queue.add(new long[] {0, component});
				}
			}
			for (int i = 0; i < anywhere.size(); i++)
			{
				relax(anywhere.get(i), -1, 0);
			}
			while (!queue.isEmpty() && !stop.getAsBoolean())
			{
				long[] entry = queue.poll();
				int component = (int) entry[1];
				if (entry[0] != distance[component])
				{
					continue;
				}
				if (targets[component])
				{
					Deque<Integer> path = new ArrayDeque<>();
					for (int at = component; at >= 0 && via[at] >= 0; at = previous[at])
					{
						path.addFirst(via[at]);
					}
					return path;
				}
				PrimitiveIntList edges = outgoing.get(component);
				for (int i = 0; i < edges.size(); i++)
				{
					relax(edges.get(i), component, entry[0]);
				}
			}
			return null;
		}

		private void relax(int edge, int fromComponent, long at)
		{
			if (cost[edge] == CLOSED)
			{
				return;
			}
			long reached = at + cost[edge];
			if (reached < 0)
			{
				reached = CLOSED - 1;
			}
			for (int component : siteComponents[to.get(edge)])
			{
				if (reached < distance[component])
				{
					distance[component] = reached;
					via[component] = edge;
					previous[component] = fromComponent;
					queue.add(new long[] {reached, component});
				}
			}
		}
	}
}
