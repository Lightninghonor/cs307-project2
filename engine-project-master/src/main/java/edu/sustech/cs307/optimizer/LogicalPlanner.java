package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.pmw.tinylog.Logger;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("(?i)^COMMIT(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern DESCRIBE_TABLE_PATTERN =
            Pattern.compile("(?i)^(?:DESCRIBE|DESC)(?:\\s+TABLE)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?i)^DROP\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern CREATE_INDEX_PATTERN =
            Pattern.compile("(?i)^CREATE\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ON\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$");
    private static final Pattern DROP_INDEX_PATTERN =
            Pattern.compile("(?i)^DROP\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+ON\\s+([A-Za-z_][A-Za-z0-9_]*))?$");
    private static final Pattern PRINT_INDEX_PATTERN =
            Pattern.compile("(?i)^PRINT\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ALTER_TABLE_ADD_PATTERN =
            Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ADD(?:\\s+COLUMN)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(int|integer|float|double|char|varchar)(?:\\s*\\([^)]*\\))?$");
    private static final Pattern ALTER_TABLE_DROP_PATTERN =
            Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+DROP(?:\\s+COLUMN)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern EXPLAIN_PATTERN = Pattern.compile("(?i)^EXPLAIN\\s+(.+)$", Pattern.DOTALL);

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualDdlCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement);
            showDatabaseExecutor.execute();
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        if (requiresAdvancedSelect(plainSelect)) {
            return new LogicalAdvancedSelectOperator(plainSelect);
        }
        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        if (isCountSelect(plainSelect)) {
            return new LogicalCountOperator(root);
        }
        root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        return root;
    }

    private static boolean isCountSelect(PlainSelect plainSelect) {
        if (plainSelect.getSelectItems() == null || plainSelect.getSelectItems().size() != 1) {
            return false;
        }
        Expression expression = plainSelect.getSelectItems().get(0).getExpression();
        return expression instanceof Function function && "count".equalsIgnoreCase(function.getName());
    }

    private static boolean requiresAdvancedSelect(PlainSelect plainSelect) {
        return plainSelect.getGroupBy() != null
                || plainSelect.getHaving() != null
                || (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty())
                || hasAdvancedAggregate(plainSelect)
                || containsSubqueryPredicate(plainSelect.getWhere());
    }

    private static boolean hasAdvancedAggregate(PlainSelect plainSelect) {
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();
            if (expression instanceof Function function) {
                String name = function.getName();
                if ("max".equalsIgnoreCase(name) || "min".equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSubqueryPredicate(Expression expression) {
        if (expression == null) {
            return false;
        }
        if (expression instanceof InExpression || expression instanceof ExistsExpression) {
            return true;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return containsSubqueryPredicate(parenthesis.getExpression());
        }
        if (expression instanceof NotExpression notExpression) {
            return containsSubqueryPredicate(notExpression.getExpression());
        }
        if (expression instanceof AndExpression andExpression) {
            return containsSubqueryPredicate(andExpression.getLeftExpression())
                    || containsSubqueryPredicate(andExpression.getRightExpression());
        }
        if (expression instanceof OrExpression orExpression) {
            return containsSubqueryPredicate(orExpression.getLeftExpression())
                    || containsSubqueryPredicate(orExpression.getRightExpression());
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return containsSubqueryPredicate(binaryExpression.getLeftExpression())
                    || containsSubqueryPredicate(binaryExpression.getRightExpression());
        }
        return false;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        String tableName = deleteStmt.getTable().getName();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        if (COMMIT_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.commitTransaction();
            return true;
        }
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            dbManager.getTransactionManager().savepoint(savepointMatcher.group(1));
            return true;
        }
        Matcher rollbackToMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToMatcher.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(rollbackToMatcher.group(1));
            return true;
        }
        Matcher releaseMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseMatcher.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(releaseMatcher.group(1));
            return true;
        }
        return false;
    }

    private static boolean handleManualDdlCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.showTables();
            return true;
        }

        Matcher describeMatcher = DESCRIBE_TABLE_PATTERN.matcher(normalizedSql);
        if (describeMatcher.matches()) {
            dbManager.descTable(describeMatcher.group(1));
            return true;
        }

        Matcher dropMatcher = DROP_TABLE_PATTERN.matcher(normalizedSql);
        if (dropMatcher.matches()) {
            dbManager.dropTable(dropMatcher.group(1));
            return true;
        }

        Matcher createIndexMatcher = CREATE_INDEX_PATTERN.matcher(normalizedSql);
        if (createIndexMatcher.matches()) {
            dbManager.createIndex(createIndexMatcher.group(1), createIndexMatcher.group(2), createIndexMatcher.group(3));
            return true;
        }

        Matcher dropIndexMatcher = DROP_INDEX_PATTERN.matcher(normalizedSql);
        if (dropIndexMatcher.matches()) {
            if (dropIndexMatcher.group(2) == null) {
                dbManager.dropIndex(dropIndexMatcher.group(1));
            } else {
                dbManager.dropIndex(dropIndexMatcher.group(1), dropIndexMatcher.group(2));
            }
            return true;
        }

        Matcher printIndexMatcher = PRINT_INDEX_PATTERN.matcher(normalizedSql);
        if (printIndexMatcher.matches()) {
            dbManager.printIndex(printIndexMatcher.group(1));
            return true;
        }

        Matcher alterAddMatcher = ALTER_TABLE_ADD_PATTERN.matcher(normalizedSql);
        if (alterAddMatcher.matches()) {
            dbManager.alterTableAddColumn(alterAddMatcher.group(1), alterAddMatcher.group(2), alterAddMatcher.group(3));
            return true;
        }

        Matcher alterDropMatcher = ALTER_TABLE_DROP_PATTERN.matcher(normalizedSql);
        if (alterDropMatcher.matches()) {
            dbManager.alterTableDropColumn(alterDropMatcher.group(1), alterDropMatcher.group(2));
            return true;
        }

        Matcher explainMatcher = EXPLAIN_PATTERN.matcher(normalizedSql);
        if (explainMatcher.matches()) {
            explainSql(dbManager, explainMatcher.group(1));
            return true;
        }
        return false;
    }

    private static void explainSql(DBManager dbManager, String sql) throws DBException {
        JSqlParser parser = new CCJSqlParserManager();
        try {
            Statement stmt = parser.parse(new StringReader(sql));
            if (stmt instanceof Select selectStmt) {
                Logger.info(handleSelect(dbManager, selectStmt).toString());
                return;
            }
            throw new DBException(ExceptionTypes.UnsupportedCommand("EXPLAIN " + stmt));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
    }


}
