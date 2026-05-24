package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private PhysicalOperator leftOperator;
    private PhysicalOperator rightOperator;
    private Collection<Expression> expr;
    private final List<Tuple> rightTuples = new ArrayList<>();
    private Tuple currentLeft;
    private Tuple currentTuple;
    private Tuple nextTuple;
    private int rightIndex;
    private boolean isOpen;
    private boolean ready;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) {
            return false;
        }
        if (ready) {
            return nextTuple != null;
        }
        return findNext();
    }

    @Override
    public void Begin() throws DBException {
        rightTuples.clear();
        rightOperator.Begin();
        while (rightOperator.hasNext()) {
            rightOperator.Next();
            Tuple tuple = rightOperator.Current();
            if (tuple != null) {
                rightTuples.add(tuple);
            }
        }
        rightOperator.Close();

        leftOperator.Begin();
        currentLeft = null;
        currentTuple = null;
        nextTuple = null;
        rightIndex = 0;
        isOpen = true;
        ready = false;
    }

    @Override
    public void Next() throws DBException {
        if (!ready) {
            hasNext();
        }
        currentTuple = nextTuple;
        nextTuple = null;
        ready = false;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        rightTuples.clear();
        currentLeft = null;
        currentTuple = null;
        nextTuple = null;
        isOpen = false;
        ready = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.addAll(leftOperator.outputSchema());
        schema.addAll(rightOperator.outputSchema());
        return schema;
    }

    private boolean findNext() throws DBException {
        nextTuple = null;
        if (rightTuples.isEmpty()) {
            ready = true;
            return false;
        }

        while (true) {
            if (currentLeft == null || rightIndex >= rightTuples.size()) {
                if (!leftOperator.hasNext()) {
                    ready = true;
                    return false;
                }
                leftOperator.Next();
                currentLeft = leftOperator.Current();
                rightIndex = 0;
                if (currentLeft == null) {
                    continue;
                }
            }

            Tuple right = rightTuples.get(rightIndex++);
            nextTuple = new JoinTuple(currentLeft, right, joinSchema(currentLeft, right));
            ready = true;
            return true;
        }
    }

    private TabCol[] joinSchema(Tuple left, Tuple right) {
        TabCol[] leftSchema = left.getTupleSchema();
        TabCol[] rightSchema = right.getTupleSchema();
        TabCol[] schema = new TabCol[leftSchema.length + rightSchema.length];
        System.arraycopy(leftSchema, 0, schema, 0, leftSchema.length);
        System.arraycopy(rightSchema, 0, schema, leftSchema.length, rightSchema.length);
        return schema;
    }
}
