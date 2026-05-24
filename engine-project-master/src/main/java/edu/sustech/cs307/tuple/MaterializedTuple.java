package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.List;

public class MaterializedTuple extends Tuple {
    private final ArrayList<ColumnMeta> schema;
    private final ArrayList<Value> values;

    public MaterializedTuple(List<ColumnMeta> schema, List<Value> values) {
        this.schema = new ArrayList<>(schema);
        this.values = new ArrayList<>(values);
    }

    public ArrayList<ColumnMeta> getColumnSchema() {
        return new ArrayList<>(schema);
    }

    @Override
    public Value getValue(TabCol tabCol) throws DBException {
        for (int i = 0; i < schema.size(); i++) {
            ColumnMeta column = schema.get(i);
            if (matches(column, tabCol)) {
                return values.get(i);
            }
        }
        return null;
    }

    @Override
    public TabCol[] getTupleSchema() {
        TabCol[] result = new TabCol[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            ColumnMeta column = schema.get(i);
            result[i] = new TabCol(column.tableName, column.name);
        }
        return result;
    }

    @Override
    public Value[] getValues() {
        return values.toArray(new Value[0]);
    }

    private boolean matches(ColumnMeta column, TabCol requested) {
        boolean columnMatches = column.name.equalsIgnoreCase(requested.getColumnName());
        if (!columnMatches) {
            return false;
        }
        String requestedTable = requested.getTableName();
        return requestedTable == null
                || requestedTable.isEmpty()
                || column.tableName.equalsIgnoreCase(requestedTable);
    }
}
