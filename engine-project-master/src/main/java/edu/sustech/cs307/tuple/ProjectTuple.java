package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * ProjectTuple 类用于表示一个投影元组，它从输入元组中提取指定列的值。
 * 该类扩展了 Tuple 类，并提供了获取列值和元组模式的方法。
 */
public class ProjectTuple extends Tuple {
    private final List<TabCol> schema;
    private final Tuple inputTuple;

    public ProjectTuple(Tuple inputTuple, List<TabCol> schema) {
        this.schema = schema;
        this.inputTuple = inputTuple;
    }

    /**
     * 根据给定的记录和列信息，从输入元组中获取对应的值。
     * 如果指定的列在投影列表中，则返回该列的值；否则返回 null。
     *
     * @param tabCol 要获取值的列信息
     * @return 指定列的值，如果列不在投影列表中则返回 null
     */
    @Override
    public Value getValue(TabCol tabCol) throws DBException {
        for (TabCol projectColumn : schema) {
            if (matches(projectColumn, tabCol)) {
                return resolveValue(projectColumn);
            }
        }
        return null; // Column not in projection list
    }

    private Value resolveValue(TabCol projectColumn) throws DBException {
        if (projectColumn.getTableName() != null && !projectColumn.getTableName().isEmpty()) {
            return inputTuple.getValue(projectColumn);
        }
        for (TabCol inputColumn : inputTuple.getTupleSchema()) {
            if (inputColumn.getColumnName().equalsIgnoreCase(projectColumn.getColumnName())) {
                return inputTuple.getValue(inputColumn);
            }
        }
        return null;
    }

    private boolean matches(TabCol projectColumn, TabCol requestedColumn) {
        boolean columnMatches = projectColumn.getColumnName().equalsIgnoreCase(requestedColumn.getColumnName());
        if (!columnMatches) {
            return false;
        }
        String projectTable = projectColumn.getTableName();
        String requestedTable = requestedColumn.getTableName();
        return projectTable == null || projectTable.isEmpty()
                || requestedTable == null || requestedTable.isEmpty()
                || projectTable.equalsIgnoreCase(requestedTable);
    }

    /**
     * 获取当前元组的模式（Schema）。
     *
     * @return 返回一个包含列信息的 TabCol 数组，表示元组的结构。
     */
    @Override
    public TabCol[] getTupleSchema() {
        return schema.toArray(new TabCol[0]);
    }

    @Override
    public Value[] getValues() throws DBException {
        // 通过 meta 顺序和信息获取所有 Value
        ArrayList<Value> values = new ArrayList<>();
        for (var tabCol : this.schema) {
            Value value = resolveValue(tabCol);
            values.add(value);
        }
        return values.toArray(new Value[0]);
    }
}
