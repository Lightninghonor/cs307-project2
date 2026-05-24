package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.Collections;

public class LogicalAdvancedSelectOperator extends LogicalOperator {
    private final PlainSelect plainSelect;

    public LogicalAdvancedSelectOperator(PlainSelect plainSelect) {
        super(Collections.emptyList());
        this.plainSelect = plainSelect;
    }

    public PlainSelect getPlainSelect() {
        return plainSelect;
    }

    @Override
    public String toString() {
        return "AdvancedSelectOperator(" + plainSelect + ")";
    }
}
