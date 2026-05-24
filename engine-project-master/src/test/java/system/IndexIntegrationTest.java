package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.system.TransactionManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class IndexIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("CREATE/DROP INDEX builds and maintains an in-memory B+ tree")
    void createDropIndexAndMaintainBPlusTree() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE t (id int, age int, name varchar)");
        for (int i = 1; i <= 20; i++) {
            executeStatement(dbManager,
                    String.format("INSERT INTO t (id, age, name) VALUES (%d, %d, 'name%d')", i, 20 + i, i));
        }

        executeStatement(dbManager, "CREATE INDEX idx_age ON t(age)");
        assertThat(dbManager.getMetaManager().getTable("t").getIndexColumn("idx_age")).isEqualTo("age");

        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.age >= 25 AND t.id <= 15"))
                .extracting(row -> row.get(0).value)
                .containsExactly(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L);

        executeStatement(dbManager, "DELETE FROM t WHERE t.age = 27");
        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.age = 27")).isEmpty();

        executeStatement(dbManager, "UPDATE t SET age = 99 WHERE t.id = 10");
        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.age = 99"))
                .extracting(row -> row.get(0).value)
                .containsExactly(10L);
        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.age = 30")).isEmpty();

        assertThatCode(() -> executeStatement(dbManager, "PRINT INDEX idx_age")).doesNotThrowAnyException();
        executeStatement(dbManager, "DROP INDEX idx_age");
        assertThat(dbManager.getMetaManager().getTable("t").getIndexes()).doesNotContainKey("idx_age");

        assertThat(queryRows(dbManager, "SELECT t.id FROM t WHERE t.age = 99"))
                .extracting(row -> row.get(0).value)
                .containsExactly(10L);
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(32, diskManager, replacerFactory.apply(32));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager, null,
                replacerFactory);
        dbManager.setTransactionManager(new TransactionManager(dbManager));
        return dbManager;
    }

    private void executeStatement(DBManager dbManager, String sql) throws DBException {
        queryRows(dbManager, sql);
    }

    private List<List<Value>> queryRows(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return List.of();
        }

        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<List<Value>> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
            while (physicalOperator.hasNext()) {
                physicalOperator.Next();
                Tuple tuple = physicalOperator.Current();
                if (tuple != null) {
                    rows.add(List.of(tuple.getValues()));
                }
            }
        } finally {
            physicalOperator.Close();
            dbManager.getBufferPool().FlushAllPages("");
        }
        return rows;
    }
}
