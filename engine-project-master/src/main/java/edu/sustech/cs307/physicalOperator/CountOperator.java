package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;

public class CountOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private long count;
    private boolean emitted;

    public CountOperator(PhysicalOperator child) {
        this.child = child;
        this.count = 0;
        this.emitted = false;
    }

    @Override
    public boolean hasNext() {
        return !emitted;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        count = 0;
        while (child.hasNext()) {
            child.Next();
            if (child.Current() != null) {
                count++;
            }
        }
    }

    @Override
    public void Next() {
        emitted = true;
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(count, ValueType.INTEGER));
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        child.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("count", "count", ValueType.INTEGER, Value.INT_SIZE, 0));
        return schema;
    }
}
