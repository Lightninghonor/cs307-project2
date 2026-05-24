package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.system.DBManager;

import java.util.List;

public class InMemoryIndexScanOperator extends IndexScanOperator {
    public InMemoryIndexScanOperator(InMemoryOrderedIndex index) {
        super();
    }

    public InMemoryIndexScanOperator(DBManager dbManager, String tableName, List<RID> rids) {
        super(dbManager, tableName, rids);
    }
}
