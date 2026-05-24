package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Collections;

public class LogicalDeleteOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final String tableName;
    private final Expression whereExpr;

    public LogicalDeleteOperator(LogicalOperator child, String tableName, Expression whereExpr) {
        super(Collections.singletonList(child));
        this.child = child;
        this.tableName = tableName;
        this.whereExpr = whereExpr;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public String getTableName() {
        return tableName;
    }

    public Expression getWhereExpr() {
        return whereExpr;
    }

    @Override
    public String toString() {
        String condition = whereExpr == null ? "<all rows>" : whereExpr.toString();
        return "DeleteOperator(table=" + tableName + ", condition=" + condition + ")\n"
                + "└── " + child;
    }
}
