package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;

import java.util.ArrayList;
import java.util.List;

public class IndexScanOperator implements PhysicalOperator {
    private final DBManager dbManager;
    private final String tableName;
    private final List<RID> rids;
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private int cursor;
    private Tuple current;
    private boolean open;

    public IndexScanOperator() {
        this.dbManager = null;
        this.tableName = null;
        this.rids = List.of();
    }

    public IndexScanOperator(DBManager dbManager, String tableName, List<RID> rids) {
        this.dbManager = dbManager;
        this.tableName = tableName;
        this.rids = new ArrayList<>(rids);
    }

    @Override
    public boolean hasNext() {
        return open && cursor < rids.size();
    }

    @Override
    public void Begin() throws DBException {
        if (dbManager == null || tableName == null) {
            open = true;
            return;
        }
        tableMeta = dbManager.getMetaManager().getTable(tableName);
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        cursor = 0;
        current = null;
        open = true;
    }

    @Override
    public void Next() throws DBException {
        if (!hasNext()) {
            current = null;
            return;
        }
        RID rid = rids.get(cursor++);
        Record record = fileHandle.GetRecord(rid);
        current = new TableTuple(tableName, tableMeta, record, rid);
    }

    @Override
    public Tuple Current() {
        return current;
    }

    @Override
    public void Close() {
        if (dbManager != null && fileHandle != null) {
            try {
                dbManager.getRecordManager().CloseFile(fileHandle);
            } catch (DBException e) {
                e.printStackTrace();
            }
        }
        fileHandle = null;
        current = null;
        open = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        if (tableMeta == null) {
            return new ArrayList<>();
        }
        return tableMeta.columns_list;
    }
}
