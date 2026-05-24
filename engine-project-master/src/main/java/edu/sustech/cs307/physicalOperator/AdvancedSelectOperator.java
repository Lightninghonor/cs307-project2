package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.MaterializedTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AdvancedSelectOperator implements PhysicalOperator {
    private final DBManager dbManager;
    private final PlainSelect plainSelect;
    private ArrayList<ColumnMeta> resultSchema;
    private List<MaterializedTuple> results = new ArrayList<>();
    private int cursor;
    private MaterializedTuple current;

    public AdvancedSelectOperator(DBManager dbManager, PlainSelect plainSelect) {
        this.dbManager = dbManager;
        this.plainSelect = plainSelect;
    }

    @Override
    public boolean hasNext() {
        return cursor < results.size();
    }

    @Override
    public void Begin() throws DBException {
        results = executePlainSelect(plainSelect, null, true);
        if (results.isEmpty()) {
            resultSchema = buildOutputSchema(plainSelect, schemaForFromClause(plainSelect));
        } else {
            resultSchema = results.get(0).getColumnSchema();
        }
        cursor = 0;
        current = null;
    }

    @Override
    public void Next() {
        current = hasNext() ? results.get(cursor++) : null;
    }

    @Override
    public Tuple Current() {
        return current;
    }

    @Override
    public void Close() {
        cursor = 0;
        current = null;
        results = new ArrayList<>();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        if (resultSchema != null) {
            return new ArrayList<>(resultSchema);
        }
        try {
            resultSchema = buildOutputSchema(plainSelect, schemaForFromClause(plainSelect));
            return new ArrayList<>(resultSchema);
        } catch (DBException e) {
            return new ArrayList<>();
        }
    }

    private List<MaterializedTuple> executePlainSelect(PlainSelect select, MaterializedTuple outer,
                                                       boolean applyOrder) throws DBException {
        List<MaterializedTuple> rows = materializeSourceRows(select, outer);
        if (select.getWhere() != null) {
            List<MaterializedTuple> filtered = new ArrayList<>();
            for (MaterializedTuple row : rows) {
                if (evaluateCondition(select.getWhere(), row)) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }

        boolean aggregate = hasAggregate(select.getSelectItems()) || select.getGroupBy() != null;
        List<MaterializedTuple> outputRows;
        if (aggregate) {
            outputRows = aggregateRows(select, rows);
            if (select.getHaving() != null) {
                List<MaterializedTuple> filtered = new ArrayList<>();
                for (MaterializedTuple row : outputRows) {
                    if (evaluateCondition(select.getHaving(), row)) {
                        filtered.add(row);
                    }
                }
                outputRows = filtered;
            }
            if (applyOrder) {
                sortRows(outputRows, select);
            }
            return outputRows;
        }

        if (applyOrder) {
            sortRows(rows, select);
        }
        outputRows = projectRows(select, rows);
        return outputRows;
    }

    private List<MaterializedTuple> materializeSourceRows(PlainSelect select, MaterializedTuple outer)
            throws DBException {
        List<MaterializedTuple> rows;
        if (select.getFromItem() == null) {
            rows = List.of(new MaterializedTuple(List.of(), List.of()));
        } else {
            rows = materializeFromItem(select.getFromItem());
        }

        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                List<MaterializedTuple> joined = new ArrayList<>();
                List<MaterializedTuple> rightRows = materializeFromItem(join.getRightItem());
                for (MaterializedTuple left : rows) {
                    for (MaterializedTuple right : rightRows) {
                        MaterializedTuple combined = combine(left, right);
                        if (matchesJoin(join.getOnExpressions(), combined)) {
                            joined.add(combined);
                        }
                    }
                }
                rows = joined;
            }
        }

        if (outer == null) {
            return rows;
        }
        List<MaterializedTuple> combinedWithOuter = new ArrayList<>();
        for (MaterializedTuple row : rows) {
            combinedWithOuter.add(combine(outer, row));
        }
        return combinedWithOuter;
    }

    private boolean matchesJoin(Collection<Expression> onExpressions, MaterializedTuple row) throws DBException {
        if (onExpressions == null || onExpressions.isEmpty()) {
            return true;
        }
        for (Expression expression : onExpressions) {
            if (!evaluateCondition(expression, row)) {
                return false;
            }
        }
        return true;
    }

    private List<MaterializedTuple> materializeFromItem(FromItem fromItem) throws DBException {
        if (!(fromItem instanceof Table table)) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(fromItem.toString()));
        }

        String tableName = table.getName();
        String visibleTableName = table.getAlias() == null ? tableName : table.getAlias().getUnquotedName();
        SeqScanOperator scanner = new SeqScanOperator(tableName, dbManager);
        ArrayList<ColumnMeta> sourceSchema = copySchema(scanner.outputSchema(), visibleTableName);
        ArrayList<MaterializedTuple> rows = new ArrayList<>();

        scanner.Begin();
        try {
            while (scanner.hasNext()) {
                scanner.Next();
                Tuple tuple = scanner.Current();
                if (tuple != null) {
                    rows.add(new MaterializedTuple(sourceSchema, List.of(tuple.getValues())));
                }
            }
        } finally {
            scanner.Close();
        }
        return rows;
    }

    private MaterializedTuple combine(MaterializedTuple left, MaterializedTuple right) throws DBException {
        ArrayList<ColumnMeta> schema = left.getColumnSchema();
        schema.addAll(right.getColumnSchema());
        ArrayList<Value> values = new ArrayList<>(List.of(left.getValues()));
        values.addAll(List.of(right.getValues()));
        return new MaterializedTuple(schema, values);
    }

    private boolean evaluateCondition(Expression expression, MaterializedTuple row) throws DBException {
        if (expression instanceof Parenthesis parenthesis) {
            return evaluateCondition(parenthesis.getExpression(), row);
        }
        if (expression instanceof NotExpression notExpression) {
            return !evaluateCondition(notExpression.getExpression(), row);
        }
        if (expression instanceof AndExpression andExpression) {
            return evaluateCondition(andExpression.getLeftExpression(), row)
                    && evaluateCondition(andExpression.getRightExpression(), row);
        }
        if (expression instanceof OrExpression orExpression) {
            return evaluateCondition(orExpression.getLeftExpression(), row)
                    || evaluateCondition(orExpression.getRightExpression(), row);
        }
        if (expression instanceof InExpression inExpression) {
            boolean result = evaluateInExpression(inExpression, row);
            return inExpression.isNot() ? !result : result;
        }
        if (expression instanceof ExistsExpression existsExpression) {
            boolean result = !executeSelectExpression(existsExpression.getRightExpression(), row).isEmpty();
            return existsExpression.isNot() ? !result : result;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            Value leftValue = evaluateValue(binaryExpression.getLeftExpression(), row);
            Value rightValue = evaluateValue(binaryExpression.getRightExpression(), row);
            if (leftValue == null || rightValue == null) {
                return false;
            }
            int comparison = ValueComparer.compare(leftValue, rightValue);
            return switch (binaryExpression.getStringExpression()) {
                case "=" -> comparison == 0;
                case ">", ">>" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case "<>", "!=" -> comparison != 0;
                default -> false;
            };
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(expression));
    }

    private boolean evaluateInExpression(InExpression inExpression, MaterializedTuple row) throws DBException {
        Value leftValue = evaluateValue(inExpression.getLeftExpression(), row);
        Expression rightExpression = inExpression.getRightExpression();
        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList.getExpressions()) {
                Value rightValue = evaluateValue(expression, row);
                if (leftValue != null && rightValue != null && ValueComparer.compare(leftValue, rightValue) == 0) {
                    return true;
                }
            }
            return false;
        }

        List<MaterializedTuple> subqueryRows = executeSelectExpression(rightExpression, row);
        for (MaterializedTuple subqueryRow : subqueryRows) {
            Value[] values = subqueryRow.getValues();
            if (values.length > 0 && leftValue != null && ValueComparer.compare(leftValue, values[0]) == 0) {
                return true;
            }
        }
        return false;
    }

    private List<MaterializedTuple> executeSelectExpression(Expression expression, MaterializedTuple outer)
            throws DBException {
        Select select = null;
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            select = parenthesedSelect.getSelect();
        } else if (expression instanceof Select selectExpression) {
            select = selectExpression;
        }
        if (select == null || select.getPlainSelect() == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expression));
        }
        return executePlainSelect(select.getPlainSelect(), outer, false);
    }

    private Value evaluateValue(Expression expression, MaterializedTuple row) throws DBException {
        if (expression instanceof Parenthesis parenthesis) {
            return evaluateValue(parenthesis.getExpression(), row);
        }
        if (expression instanceof SignedExpression signedExpression) {
            Value value = evaluateValue(signedExpression.getExpression(), row);
            if (signedExpression.getSign() != '-' || value == null) {
                return value;
            }
            if (value.type == ValueType.INTEGER) {
                return new Value(-((Long) value.value), ValueType.INTEGER);
            }
            if (value.type == ValueType.FLOAT) {
                return new Value(-((Double) value.value), ValueType.FLOAT);
            }
            throw new DBException(ExceptionTypes.UnsupportedExpression(expression));
        }
        if (expression instanceof StringValue stringValue) {
            return new Value(stringValue.getValue(), ValueType.CHAR);
        }
        if (expression instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue(), ValueType.FLOAT);
        }
        if (expression instanceof LongValue longValue) {
            return new Value(longValue.getValue(), ValueType.INTEGER);
        }
        if (expression instanceof Column column) {
            String tableName = column.getTableName();
            return row.getValue(new TabCol(tableName == null ? "" : tableName, column.getColumnName()));
        }
        if (expression instanceof Function function) {
            Value value = row.getValue(new TabCol("", expression.toString()));
            if (value != null) {
                return value;
            }
            return row.getValue(new TabCol("", function.getName()));
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(expression));
    }

    private List<MaterializedTuple> projectRows(PlainSelect select, List<MaterializedTuple> rows) throws DBException {
        ArrayList<ColumnMeta> outputSchema = buildOutputSchema(select,
                rows.isEmpty() ? schemaForFromClause(select) : rows.get(0).getColumnSchema());
        ArrayList<MaterializedTuple> projected = new ArrayList<>();
        for (MaterializedTuple row : rows) {
            ArrayList<Value> values = new ArrayList<>();
            for (SelectItem<?> selectItem : select.getSelectItems()) {
                Expression expression = selectItem.getExpression();
                if (expression instanceof AllColumns) {
                    values.addAll(List.of(row.getValues()));
                } else {
                    values.add(evaluateValue(expression, row));
                }
            }
            projected.add(new MaterializedTuple(outputSchema, values));
        }
        return projected;
    }

    private List<MaterializedTuple> aggregateRows(PlainSelect select, List<MaterializedTuple> rows)
            throws DBException {
        ArrayList<Expression> groupExpressions = groupByExpressions(select);
        Map<String, GroupBucket> groups = new LinkedHashMap<>();

        if (rows.isEmpty() && groupExpressions.isEmpty()) {
            groups.put("", new GroupBucket(List.of(), null));
        }
        for (MaterializedTuple row : rows) {
            ArrayList<Value> keyValues = new ArrayList<>();
            for (Expression groupExpression : groupExpressions) {
                keyValues.add(evaluateValue(groupExpression, row));
            }
            groups.computeIfAbsent(groupKey(keyValues), ignored -> new GroupBucket(keyValues, row)).rows.add(row);
        }

        ArrayList<ColumnMeta> outputSchema = buildOutputSchema(select,
                rows.isEmpty() ? schemaForFromClause(select) : rows.get(0).getColumnSchema());
        ArrayList<MaterializedTuple> outputRows = new ArrayList<>();
        for (GroupBucket bucket : groups.values()) {
            ArrayList<Value> values = new ArrayList<>();
            for (SelectItem<?> selectItem : select.getSelectItems()) {
                Expression expression = selectItem.getExpression();
                if (expression instanceof Function function) {
                    values.add(computeAggregate(function, bucket.rows));
                } else if (bucket.firstRow != null) {
                    values.add(evaluateValue(expression, bucket.firstRow));
                } else {
                    values.add(null);
                }
            }
            outputRows.add(new MaterializedTuple(outputSchema, values));
        }
        return outputRows;
    }

    private Value computeAggregate(Function function, List<MaterializedTuple> rows) throws DBException {
        String name = function.getName().toLowerCase();
        if ("count".equals(name)) {
            if (function.isAllColumns() || function.getParameters() == null
                    || firstFunctionArgument(function) instanceof AllColumns) {
                return new Value((long) rows.size(), ValueType.INTEGER);
            }
            long count = 0;
            Expression argument = firstFunctionArgument(function);
            for (MaterializedTuple row : rows) {
                if (evaluateValue(argument, row) != null) {
                    count++;
                }
            }
            return new Value(count, ValueType.INTEGER);
        }
        if (!"max".equals(name) && !"min".equals(name)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }

        Expression argument = firstFunctionArgument(function);
        Value result = null;
        for (MaterializedTuple row : rows) {
            Value value = evaluateValue(argument, row);
            if (result == null) {
                result = value;
            } else if (value != null) {
                int comparison = ValueComparer.compare(value, result);
                if (("max".equals(name) && comparison > 0) || ("min".equals(name) && comparison < 0)) {
                    result = value;
                }
            }
        }
        return result;
    }

    private Expression firstFunctionArgument(Function function) throws DBException {
        ExpressionList<?> parameters = function.getParameters();
        if (parameters == null || parameters.getExpressions().isEmpty()) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        return parameters.getExpressions().get(0);
    }

    private void sortRows(List<MaterializedTuple> rows, PlainSelect select) throws DBException {
        List<OrderByElement> orderByElements = select.getOrderByElements();
        if (orderByElements == null || orderByElements.isEmpty()) {
            return;
        }
        for (int i = 1; i < rows.size(); i++) {
            MaterializedTuple currentRow = rows.get(i);
            int j = i - 1;
            while (j >= 0 && compareRows(rows.get(j), currentRow, select) > 0) {
                rows.set(j + 1, rows.get(j));
                j--;
            }
            rows.set(j + 1, currentRow);
        }
    }

    private int compareRows(MaterializedTuple left, MaterializedTuple right, PlainSelect select)
            throws DBException {
        List<OrderByElement> orderByElements = select.getOrderByElements();
        for (OrderByElement orderByElement : orderByElements) {
            Value leftValue = evaluateOrderValue(orderByElement.getExpression(), left, select);
            Value rightValue = evaluateOrderValue(orderByElement.getExpression(), right, select);
            int comparison = ValueComparer.compare(leftValue, rightValue);
            if (comparison != 0) {
                return orderByElement.isAsc() ? comparison : -comparison;
            }
        }
        return 0;
    }

    private Value evaluateOrderValue(Expression expression, MaterializedTuple row, PlainSelect select)
            throws DBException {
        Value directValue = evaluateValue(expression, row);
        if (directValue != null || !(expression instanceof Column column)) {
            return directValue;
        }
        String tableName = column.getTableName();
        if (tableName != null && !tableName.isEmpty()) {
            return directValue;
        }
        for (SelectItem<?> selectItem : select.getSelectItems()) {
            String alias = selectItem.getAliasName();
            if (alias != null && alias.equalsIgnoreCase(column.getColumnName())) {
                return evaluateValue(selectItem.getExpression(), row);
            }
        }
        return null;
    }

    private ArrayList<ColumnMeta> buildOutputSchema(PlainSelect select, List<ColumnMeta> sourceSchema)
            throws DBException {
        ArrayList<ColumnMeta> output = new ArrayList<>();
        int offset = 0;
        for (SelectItem<?> selectItem : select.getSelectItems()) {
            Expression expression = selectItem.getExpression();
            if (expression instanceof AllColumns) {
                output.addAll(sourceSchema);
                continue;
            }

            ColumnMeta columnMeta = columnMetaForSelectItem(selectItem, sourceSchema, offset);
            output.add(columnMeta);
            offset += columnMeta.len;
        }
        return output;
    }

    private ColumnMeta columnMetaForSelectItem(SelectItem<?> selectItem, List<ColumnMeta> sourceSchema, int offset)
            throws DBException {
        Expression expression = selectItem.getExpression();
        String alias = selectItem.getAliasName();
        if (expression instanceof Column column) {
            ColumnMeta sourceColumn = findColumn(sourceSchema, column);
            if (sourceColumn == null) {
                throw new DBException(ExceptionTypes.ColumnDoesNotExist(column.getColumnName()));
            }
            String outputTable = alias == null ? sourceColumn.tableName : "result";
            String outputName = alias == null ? sourceColumn.name : alias;
            return new ColumnMeta(outputTable, outputName, sourceColumn.type, sourceColumn.len, offset);
        }
        if (expression instanceof Function function) {
            ValueType type = "count".equalsIgnoreCase(function.getName()) ? ValueType.INTEGER
                    : aggregateReturnType(function, sourceSchema);
            int len = type == ValueType.CHAR ? Value.CHAR_SIZE : Value.INT_SIZE;
            if (type == ValueType.FLOAT) {
                len = Value.FLOAT_SIZE;
            }
            return new ColumnMeta("result", alias == null ? expression.toString() : alias, type, len, offset);
        }

        ValueType type = expression instanceof DoubleValue ? ValueType.FLOAT
                : expression instanceof LongValue ? ValueType.INTEGER : ValueType.CHAR;
        int len = type == ValueType.CHAR ? Value.CHAR_SIZE : Value.INT_SIZE;
        if (type == ValueType.FLOAT) {
            len = Value.FLOAT_SIZE;
        }
        return new ColumnMeta("result", alias == null ? expression.toString() : alias, type, len, offset);
    }

    private ValueType aggregateReturnType(Function function, List<ColumnMeta> sourceSchema) throws DBException {
        if ("max".equalsIgnoreCase(function.getName()) || "min".equalsIgnoreCase(function.getName())) {
            Expression argument = firstFunctionArgument(function);
            if (argument instanceof Column column) {
                ColumnMeta sourceColumn = findColumn(sourceSchema, column);
                if (sourceColumn != null) {
                    return sourceColumn.type;
                }
            }
            if (argument instanceof DoubleValue) {
                return ValueType.FLOAT;
            }
            if (argument instanceof LongValue) {
                return ValueType.INTEGER;
            }
        }
        return ValueType.CHAR;
    }

    private ColumnMeta findColumn(List<ColumnMeta> schema, Column column) {
        String tableName = column.getTableName();
        for (ColumnMeta columnMeta : schema) {
            boolean columnMatches = columnMeta.name.equalsIgnoreCase(column.getColumnName());
            boolean tableMatches = tableName == null || tableName.isEmpty()
                    || columnMeta.tableName.equalsIgnoreCase(tableName);
            if (columnMatches && tableMatches) {
                return columnMeta;
            }
        }
        return null;
    }

    private ArrayList<ColumnMeta> schemaForFromClause(PlainSelect select) throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        if (select.getFromItem() == null) {
            return schema;
        }
        schema.addAll(schemaForFromItem(select.getFromItem()));
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                schema.addAll(schemaForFromItem(join.getRightItem()));
            }
        }
        return schema;
    }

    private ArrayList<ColumnMeta> schemaForFromItem(FromItem fromItem) throws DBException {
        if (!(fromItem instanceof Table table)) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(fromItem.toString()));
        }
        String tableName = table.getName();
        String visibleTableName = table.getAlias() == null ? tableName : table.getAlias().getUnquotedName();
        return copySchema(dbManager.getMetaManager().getTable(tableName).columns_list, visibleTableName);
    }

    private ArrayList<ColumnMeta> copySchema(List<ColumnMeta> schema, String tableName) {
        ArrayList<ColumnMeta> copy = new ArrayList<>();
        for (ColumnMeta column : schema) {
            copy.add(new ColumnMeta(tableName, column.name, column.type, column.len, column.offset));
        }
        return copy;
    }

    private ArrayList<Expression> groupByExpressions(PlainSelect select) {
        ArrayList<Expression> result = new ArrayList<>();
        if (select.getGroupBy() == null || select.getGroupBy().getGroupByExpressions() == null) {
            return result;
        }
        ExpressionList<?> expressions = select.getGroupBy().getGroupByExpressions();
        result.addAll(expressions.getExpressions());
        return result;
    }

    private boolean hasAggregate(List<SelectItem<?>> selectItems) {
        if (selectItems == null) {
            return false;
        }
        for (SelectItem<?> selectItem : selectItems) {
            if (isAggregateExpression(selectItem.getExpression())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAggregateExpression(Expression expression) {
        if (!(expression instanceof Function function)) {
            return false;
        }
        String name = function.getName();
        return "count".equalsIgnoreCase(name) || "max".equalsIgnoreCase(name) || "min".equalsIgnoreCase(name);
    }

    private String groupKey(List<Value> values) {
        StringBuilder builder = new StringBuilder();
        for (Value value : values) {
            if (value == null) {
                builder.append("null");
            } else {
                builder.append(value.type).append(':').append(value.value);
            }
            builder.append('\u001f');
        }
        return builder.toString();
    }

    private static class GroupBucket {
        private final List<Value> keyValues;
        private final MaterializedTuple firstRow;
        private final ArrayList<MaterializedTuple> rows = new ArrayList<>();

        private GroupBucket(List<Value> keyValues, MaterializedTuple firstRow) {
            this.keyValues = keyValues;
            this.firstRow = firstRow;
        }
    }
}
