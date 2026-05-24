package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

public class LogicalCountOperator extends LogicalOperator {
    private final LogicalOperator child;

    public LogicalCountOperator(LogicalOperator child) {
        super(Collections.singletonList(child));
        this.child = child;
    }

    public LogicalOperator getChild() {
        return child;
    }

    @Override
    public String toString() {
        return "CountOperator(count=*)\n└── " + child;
    }
}
