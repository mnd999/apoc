package apoc.path;

import apoc.algo.Cover;
import apoc.result.GraphResult;
import apoc.result.NodeResult;
import apoc.result.PathResult;
import apoc.util.Util;
import apoc.util.collection.Iterables;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.*;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static apoc.path.PathExplorer.NodeFilter.*;
import static apoc.util.Util.getNodeElementId;

public class PathExplorer {
	public static final Uniqueness UNIQUENESS = Uniqueness.RELATIONSHIP_PATH;
	public static final boolean BFS = true;
	@Context
    public Transaction tx;

	@Procedure("apoc.path.expand")
	@Description("Returns paths expanded from the start node following the given relationship types from min-depth to max-depth.")
	public Stream<PathResult> explorePath(@Name("startNode") Object start
			                   , @Name("relFilter") String pathFilter
			                   , @Name("labelFilter") String labelFilter
			                   , @Name("minDepth") long minLevel
			                   , @Name("maxDepth") long maxLevel ) throws Exception {
		List<Node> nodes = startToNodes(start);
		return explorePathPrivate(nodes, pathFilter, labelFilter, minLevel, maxLevel, BFS, UNIQUENESS, false, -1, null, null, true).map( PathResult::new );
	}

	//
	@Procedure("apoc.path.expandConfig")
	@Description("Returns paths expanded from the start node the given relationship types from min-depth to max-depth.")
	public Stream<PathResult> expandConfig(@Name("startNode") Object start, @Name("config") Map<String,Object> config) throws Exception {
		return expandConfigPrivate(start, config).map( PathResult::new );
	}

	@Procedure("apoc.path.subgraphNodes")
	@Description("Returns the nodes in the sub-graph reachable from the start node following the given relationship types to max-depth.")
	public Stream<NodeResult> subgraphNodes(@Name("startNode") Object start, @Name("config") Map<String,Object> config) throws Exception {
		Map<String, Object> configMap = new HashMap<>(config);
		configMap.put("uniqueness", "NODE_GLOBAL");

		if (config.containsKey("minLevel") && !config.get("minLevel").equals(0l) && !config.get("minLevel").equals(1l)) {
			throw new IllegalArgumentException("minLevel can only be 0 or 1 in subgraphNodes()");
		}

		return expandConfigPrivate(start, configMap).map( path -> path == null ? new NodeResult(null) : new NodeResult(path.endNode()) );
	}

	@Procedure("apoc.path.subgraphAll")
	@Description("Returns the sub-graph reachable from the start node following the given relationship types to max-depth.")
	public Stream<GraphResult> subgraphAll(@Name("startNode") Object start, @Name("config") Map<String,Object> config) throws Exception {
		Map<String, Object> configMap = new HashMap<>(config);
		configMap.remove("optional"); // not needed, will return empty collections anyway if no results
		configMap.put("uniqueness", "NODE_GLOBAL");

		if (config.containsKey("minLevel") && !config.get("minLevel").equals(0l) && !config.get("minLevel").equals(1l)) {
			throw new IllegalArgumentException("minLevel can only be 0 or 1 in subgraphAll()");
		}

		List<Node> subgraphNodes = expandConfigPrivate(start, configMap).map( Path::endNode ).collect(Collectors.toList());
		List<Relationship> subgraphRels = Cover.coverNodes(subgraphNodes).collect(Collectors.toList());

		return Stream.of(new GraphResult(subgraphNodes, subgraphRels));
	}

	@Procedure("apoc.path.spanningTree")
	@Description("Returns spanning tree paths expanded from the start node following the given relationship types to max-depth.")
	public Stream<PathResult> spanningTree(@Name("startNode") Object start, @Name("config") Map<String,Object> config) throws Exception {
		Map<String, Object> configMap = new HashMap<>(config);
		configMap.put("uniqueness", "NODE_GLOBAL");

		if (config.containsKey("minLevel") && !config.get("minLevel").equals(0l) && !config.get("minLevel").equals(1l)) {
			throw new IllegalArgumentException("minLevel can only be 0 or 1 in spanningTree()");
		}

		return expandConfigPrivate(start, configMap).map( PathResult::new );
	}

	private Uniqueness getUniqueness(String uniqueness) {
		for (Uniqueness u : Uniqueness.values()) {
			if (u.name().equalsIgnoreCase(uniqueness)) return u;
		}
		return UNIQUENESS;
	}

	/*
    , @Name("relationshipFilter") String pathFilter
    , @Name("labelFilter") String labelFilter
    , @Name("minLevel") long minLevel
    , @Name("maxLevel") long maxLevel ) throws Exception {
     */
	@SuppressWarnings("unchecked")
	private List<Node> startToNodes(Object start) throws Exception {
		if (start == null) return Collections.emptyList();
		if (start instanceof Node) {
			return Collections.singletonList((Node) start);
		}
		if (start instanceof Number) {
			return Collections.singletonList(tx.getNodeByElementId(getNodeElementId((InternalTransaction) tx, ((Number) start).longValue())));
		}
		if (start instanceof List) {
			List list = (List) start;
			if (list.isEmpty()) return Collections.emptyList();

			Object first = list.get(0);
			if (first instanceof Node) return (List<Node>)list;
			if (first instanceof Number) {
                List<Node> nodes = new ArrayList<>();
                for (Number n : ((List<Number>)list)) nodes.add(tx.getNodeByElementId(getNodeElementId((InternalTransaction) tx, n.longValue())));
                return nodes;
            }
		}
		throw new Exception("Unsupported data type for start parameter a Node or an Identifier (long) of a Node must be given!");
	}

	private Stream<Path> expandConfigPrivate(@Name("start") Object start, @Name("config") Map<String,Object> config) throws Exception {
		List<Node> nodes = startToNodes(start);

		String uniqueness = (String) config.getOrDefault("uniqueness", UNIQUENESS.name());
		String relationshipFilter = (String) config.getOrDefault("relationshipFilter", null);
		String labelFilter = (String) config.getOrDefault("labelFilter", null);
		long minLevel = Util.toLong(config.getOrDefault("minLevel", "-1"));
		long maxLevel = Util.toLong(config.getOrDefault("maxLevel", "-1"));
		boolean bfs = Util.toBoolean(config.getOrDefault("bfs",true));
		boolean filterStartNode = Util.toBoolean(config.getOrDefault("filterStartNode", false));
		long limit = Util.toLong(config.getOrDefault("limit", "-1"));
		boolean optional = Util.toBoolean(config.getOrDefault("optional", false));
		String sequence = (String) config.getOrDefault("sequence", null);
		boolean beginSequenceAtStart = Util.toBoolean(config.getOrDefault("beginSequenceAtStart", true));

		List<Node> endNodes = startToNodes(config.get("endNodes"));
		List<Node> terminatorNodes = startToNodes(config.get("terminatorNodes"));
		List<Node> whitelistNodes = startToNodes(config.get("whitelistNodes")); // DEPRECATED REMOVE 6.0
		List<Node> blacklistNodes = startToNodes(config.get("blacklistNodes")); // DEPRECATED REMOVE 6.0
		List<Node> allowlistNodes = startToNodes(config.get("allowlistNodes"));
		List<Node> denylistNodes = startToNodes(config.get("denylistNodes"));
		EnumMap<NodeFilter, List<Node>> nodeFilter = new EnumMap<>(NodeFilter.class);

		if (endNodes != null && !endNodes.isEmpty()) {
			nodeFilter.put(END_NODES, endNodes);
		}

		if (terminatorNodes != null && !terminatorNodes.isEmpty()) {
			nodeFilter.put(TERMINATOR_NODES, terminatorNodes);
		}

		// If allowlist/denylist is specified use that (and only that)
		// Else check for the *deprecated* config items
		if (allowlistNodes != null && !allowlistNodes.isEmpty()) {
			nodeFilter.put(ALLOWLIST_NODES, allowlistNodes);
		} else if (whitelistNodes != null && !whitelistNodes.isEmpty()) {
			nodeFilter.put(ALLOWLIST_NODES, whitelistNodes);
		}

		if (denylistNodes != null && !denylistNodes.isEmpty()) {
			nodeFilter.put(DENYLIST_NODES, denylistNodes);
		} else if (blacklistNodes != null && !blacklistNodes.isEmpty()) {
			nodeFilter.put(DENYLIST_NODES, blacklistNodes);
		}

		Stream<Path> results = explorePathPrivate(nodes, relationshipFilter, labelFilter, minLevel, maxLevel, bfs, getUniqueness(uniqueness), filterStartNode, limit, nodeFilter, sequence, beginSequenceAtStart);

		if (optional) {
			return optionalStream(results);
		} else {
			return results;
		}
	}

	private Stream<Path> explorePathPrivate(Iterable<Node> startNodes,
											String pathFilter,
											String labelFilter,
											long minLevel,
											long maxLevel,
											boolean bfs,
											Uniqueness uniqueness,
											boolean filterStartNode,
											long limit,
											EnumMap<NodeFilter, List<Node>> nodeFilter,
											String sequence,
											boolean beginSequenceAtStart) {

		Traverser traverser = traverse(tx.traversalDescription(), startNodes, pathFilter, labelFilter, minLevel, maxLevel, uniqueness,bfs,filterStartNode, nodeFilter, sequence, beginSequenceAtStart);

		if (limit == -1) {
			return Iterables.stream(traverser);
		} else {
			return Iterables.stream(traverser).limit(limit);
		}
	}

	/**
	 * If the stream is empty, returns a stream of a single null value, otherwise returns the equivalent of the input stream
	 * @param stream the input stream
	 * @return a stream of a single null value if the input stream is empty, otherwise returns the equivalent of the input stream
	 */
	private Stream<Path> optionalStream(Stream<Path> stream) {
		Stream<Path> optionalStream;
		Iterator<Path> itr = stream.iterator();
		if (itr.hasNext()) {
			optionalStream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(itr, 0), false);
		} else {
			List<Path> listOfNull = new ArrayList<>();
			listOfNull.add(null);
			optionalStream = listOfNull.stream();
		}

		return optionalStream;
	}

	public static Traverser traverse(TraversalDescription td,
									 Iterable<Node> startNodes,
									 String pathFilter,
									 String labelFilter,
									 long minLevel,
									 long maxLevel,
									 Uniqueness uniqueness,
									 boolean bfs,
									 boolean filterStartNode,
									 EnumMap<NodeFilter, List<Node>> nodeFilter,
									 String sequence,
									 boolean beginSequenceAtStart) {
		// based on the pathFilter definition now the possible relationships and directions must be shown

		td = bfs ? td.breadthFirst() : td.depthFirst();

		// if `sequence` is present, it overrides `labelFilter` and `relationshipFilter`
		if (sequence != null && !sequence.trim().isEmpty())	{
			String[] sequenceSteps = sequence.split(",");
			List<String> labelSequenceList = new ArrayList<>();
			List<String> relSequenceList = new ArrayList<>();

			for (int index = 0; index < sequenceSteps.length; index++) {
				List<String> seq = (beginSequenceAtStart ? index : index - 1) % 2 == 0 ? labelSequenceList : relSequenceList;
				seq.add(sequenceSteps[index]);
			}

			td = td.expand(new RelationshipSequenceExpander(relSequenceList, beginSequenceAtStart));
			td = td.evaluator(new LabelSequenceEvaluator(labelSequenceList, filterStartNode, beginSequenceAtStart, (int) minLevel));
		} else {
			if (pathFilter != null && !pathFilter.trim().isEmpty()) {
				td = td.expand(new RelationshipSequenceExpander(pathFilter.trim(), beginSequenceAtStart));
			}

			if (labelFilter != null && sequence == null && !labelFilter.trim().isEmpty()) {
				td = td.evaluator(new LabelSequenceEvaluator(labelFilter.trim(), filterStartNode, beginSequenceAtStart, (int) minLevel));
			}
		}

		if (minLevel != -1) td = td.evaluator(Evaluators.fromDepth((int) minLevel));
		if (maxLevel != -1) td = td.evaluator(Evaluators.toDepth((int) maxLevel));


		if (nodeFilter != null && !nodeFilter.isEmpty()) {
			List<Node> endNodes = nodeFilter.getOrDefault(END_NODES, Collections.EMPTY_LIST);
			List<Node> terminatorNodes = nodeFilter.getOrDefault(TERMINATOR_NODES, Collections.EMPTY_LIST);
			List<Node> denylistNodes = nodeFilter.getOrDefault(DENYLIST_NODES, Collections.EMPTY_LIST);
			List<Node> allowlistNodes;

			if (nodeFilter.containsKey(ALLOWLIST_NODES)) {
				// need to add to new list since we may need to add to it later
				// encounter "can't add to abstractList" error if we don't do this
				allowlistNodes = new ArrayList<>(nodeFilter.get(ALLOWLIST_NODES));
			} else {
				allowlistNodes = Collections.EMPTY_LIST;
			}

			if (!denylistNodes.isEmpty()) {
				td = td.evaluator(NodeEvaluators.denylistNodeEvaluator(filterStartNode, denylistNodes));
			}

			Evaluator endAndTerminatorNodeEvaluator = NodeEvaluators.endAndTerminatorNodeEvaluator(filterStartNode, (int) minLevel, endNodes, terminatorNodes);
			if (endAndTerminatorNodeEvaluator != null) {
				td = td.evaluator(endAndTerminatorNodeEvaluator);
			}

			if (!allowlistNodes.isEmpty()) {
				// ensure endNodes and terminatorNodes are allowlisted
				allowlistNodes.addAll(endNodes);
				allowlistNodes.addAll(terminatorNodes);
				td = td.evaluator(NodeEvaluators.allowlistNodeEvaluator(filterStartNode, allowlistNodes));
			}
		}

		td = td.uniqueness(uniqueness); // this is how Cypher works !! Uniqueness.RELATIONSHIP_PATH
		// uniqueness should be set as last on the TraversalDescription
		return td.traverse(startNodes);
	}

	// keys to node filter map
	enum NodeFilter {
		ALLOWLIST_NODES,
		DENYLIST_NODES,
		END_NODES,
		TERMINATOR_NODES
	}

}
