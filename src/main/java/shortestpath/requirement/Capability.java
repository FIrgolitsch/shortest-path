package shortestpath.requirement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.runelite.api.Quest;
import shortestpath.requirement.model.SkillLevels;

/**
 * What the player could get to open a locked transport: quests to finish, skill levels to reach
 * and items to carry, in any mix. Bundles combine with {@link #union}. What one is worth depends on the
 * player, so {@link Requirements#effortUnits} weighs them and
 * {@link RequirementContext#granting} applies them.
 */
@Getter
@EqualsAndHashCode
public final class Capability
{
	/**
	 * Coarse effort tiers, cheapest first. Any amount of a harder tier outweighs every amount of
	 * the easier ones; a bundle sits in the tier of its hardest part.
	 */
	public enum Effort
	{
		/** Items the player already has in the bank. */
		OWNED_ITEMS,
		/** Coins, trading sticks and other currencies. */
		CURRENCY,
		ITEMS,
		/** Quests, and skill levels a moderate amount of training away. */
		PROGRESS,
		/**
		 * Long-term goals: levels a lot of experience away, maximum levels such as a max cape's, and
		 * every quest point or music track.
		 */
		MAJOR
	}

	private final Set<Quest> quests;
	/** The levels to reach; zero at every index that needs no change. */
	private final SkillLevels skillLevels;
	/** Item id to quantity. */
	private final Map<Integer, Integer> items;

	private Capability(Set<Quest> quests, SkillLevels skillLevels, Map<Integer, Integer> items)
	{
		this.quests = Set.copyOf(quests);
		this.skillLevels = skillLevels;
		this.items = Map.copyOf(items);
	}

	public static Capability of(Set<Quest> quests, SkillLevels skillLevels, Map<Integer, Integer> items)
	{
		return new Capability(quests, skillLevels, items);
	}

	public static Capability items(Map<Integer, Integer> items)
	{
		return of(Set.of(), SkillLevels.NONE, items);
	}

	public boolean isEmpty()
	{
		return quests.isEmpty() && skillLevels.isEmpty() && items.isEmpty();
	}

	/**
	 * What meets both without counting a requirement twice: every quest of either, the higher of
	 * each level, and the larger amount of each item. Several transports asking for the same fare or
	 * quest along one route ask for it once.
	 */
	public Capability union(Capability other)
	{
		Set<Quest> allQuests = new HashSet<>(quests);
		allQuests.addAll(other.quests);
		Map<Integer, Integer> allItems = new HashMap<>(items);
		other.items.forEach((id, quantity) -> allItems.merge(id, quantity, Math::max));
		return of(allQuests, SkillLevels.merge(skillLevels, other.skillLevels), allItems);
	}
}
