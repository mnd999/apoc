package org.neo4j.cypher.export;

import java.util.Comparator;
import java.util.stream.StreamSupport;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexDefinition;

import java.util.Iterator;

import static apoc.export.cypher.formatter.CypherFormatterUtils.cypherNode;
import static apoc.util.Util.quote;

public class DatabaseSubGraph implements SubGraph
{
    private final Transaction transaction;

    public DatabaseSubGraph( Transaction transaction )
    {
        this.transaction = transaction;
    }

    @Override
    public Iterable<Node> getNodes()
    {
        return transaction.getAllNodes();
    }

    @Override
    public Iterable<Relationship> getRelationships()
    {
        return transaction.getAllRelationships();
    }

    @Override
    public Iterable<IndexDefinition> getIndexes()
    {
        return transaction.schema().getIndexes();
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints()
    {
        Comparator<ConstraintDefinition> comp = Comparator.comparing(ConstraintDefinition::getName);
        return StreamSupport.stream( transaction.schema().getConstraints().spliterator(), false ).sorted(comp).toList();
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints(Label label) {
        return transaction.schema().getConstraints(label);
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints(RelationshipType type) {
        return transaction.schema().getConstraints(type);
    }

    @Override
    public Iterable<IndexDefinition> getIndexes(Label label) {
        return transaction.schema().getIndexes(label);
    }

    @Override
    public Iterable<IndexDefinition> getIndexes(RelationshipType type) {
        return transaction.schema().getIndexes(type);
    }

    @Override
    public Iterable<RelationshipType> getAllRelationshipTypesInUse() {
        return transaction.getAllRelationshipTypesInUse();
    }

    @Override
    public Iterable<Label> getAllLabelsInUse() {
        return transaction.getAllLabelsInUse();
    }

    @Override
    public long countsForRelationship(Label start, RelationshipType type, Label end) {
        String startNode = cypherNode(start);
        String endNode = cypherNode(end);
        String relationship = String.format("[r:%s]", quote(type.name()));
        return transaction.execute(String.format("MATCH %s-%s->%s RETURN count(r) AS count", startNode, relationship, endNode))
                .<Long>columnAs("count")
                .next();
    }

    @Override
    public long countsForNode(Label label) {
        return transaction.execute(String.format("MATCH (n:%s) RETURN count(n) AS count", quote(label.name())))
                .<Long>columnAs("count")
                .next();
    }

    @Override
    public Iterator<Node> findNodes(Label label) {
        return transaction.findNodes(label);
    }
}
